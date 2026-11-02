package de.lukas.kino;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Kleiner WebSocket-Client ohne Bibliothek (nur ws://, für das Heimnetz).
 * Kann Text- und Binär-Frames senden, Text empfangen, beantwortet Pings und merkt Abbrüche.
 * Ein eigener Lese-Thread ruft {@link Hoerer} auf.
 */
final class MiniWebSocket {

    interface Hoerer {
        void text(String nachricht);

        void geschlossen(String grund);
    }

    private final URI ziel;
    private final Hoerer hoerer;
    private final SecureRandom zufall = new SecureRandom();
    private Socket socket;
    private OutputStream aus;
    private volatile boolean offen;
    private final Object schreibSperre = new Object();

    MiniWebSocket(String url, Hoerer hoerer) {
        this.ziel = URI.create(url);
        this.hoerer = hoerer;
    }

    boolean istOffen() {
        return offen;
    }

    /** Baut die Verbindung auf (blockiert, also nicht im UI-Thread aufrufen). */
    void verbinden() throws IOException {
        int port = ziel.getPort() > 0 ? ziel.getPort() : 80;
        String pfad = ziel.getRawPath() == null || ziel.getRawPath().isEmpty() ? "/" : ziel.getRawPath();
        if (ziel.getRawQuery() != null) pfad += "?" + ziel.getRawQuery();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ziel.getHost(), port), 4000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(0);
        byte[] schluessel = new byte[16];
        zufall.nextBytes(schluessel);
        String key = Base64.encodeToString(schluessel, Base64.NO_WRAP);
        String anfrage = "GET " + pfad + " HTTP/1.1\r\n"
                + "Host: " + ziel.getHost() + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(anfrage.getBytes(StandardCharsets.US_ASCII));
        o.flush();
        InputStream ein = s.getInputStream();
        String kopf = kopfLesen(ein);
        if (!kopf.startsWith("HTTP/1.1 101")) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw new IOException("Kein WebSocket: " + kopf.split("\r\n", 2)[0]);
        }
        socket = s;
        aus = o;
        offen = true;
        Thread leser = new Thread(() -> lesen(ein), "LukasKinoWsLeser");
        leser.setDaemon(true);
        leser.start();
    }

    private static String kopfLesen(InputStream ein) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int zustand = 0;
        while (b.size() < 8192) {
            int c = ein.read();
            if (c < 0) throw new IOException("Verbindung beim Handshake beendet");
            b.write(c);
            if ((zustand == 0 || zustand == 2) && c == '\r') zustand++;
            else if ((zustand == 1 || zustand == 3) && c == '\n') zustand++;
            else zustand = c == '\r' ? 1 : 0;
            if (zustand == 4) break;
        }
        return b.toString("US-ASCII");
    }

    void textSenden(String text) {
        frameSenden(0x1, text.getBytes(StandardCharsets.UTF_8), 0, -1);
    }

    void binaerSenden(byte[] daten, int laenge) {
        frameSenden(0x2, daten, 0, laenge);
    }

    private void frameSenden(int opcode, byte[] daten, int start, int laenge) {
        if (!offen) return;
        int n = laenge < 0 ? daten.length : laenge;
        byte[] maske = new byte[4];
        zufall.nextBytes(maske);
        int kopfLaenge = 2 + (n < 126 ? 0 : (n < 65536 ? 2 : 8)) + 4;
        byte[] frame = new byte[kopfLaenge + n];
        int i = 0;
        frame[i++] = (byte) (0x80 | opcode);
        if (n < 126) {
            frame[i++] = (byte) (0x80 | n);
        } else if (n < 65536) {
            frame[i++] = (byte) (0x80 | 126);
            frame[i++] = (byte) (n >>> 8);
            frame[i++] = (byte) n;
        } else {
            frame[i++] = (byte) (0x80 | 127);
            for (int k = 7; k >= 0; k--) frame[i++] = (byte) (((long) n) >>> (8 * k));
        }
        System.arraycopy(maske, 0, frame, i, 4);
        i += 4;
        for (int k = 0; k < n; k++) frame[i + k] = (byte) (daten[start + k] ^ maske[k & 3]);
        synchronized (schreibSperre) {
            try {
                aus.write(frame);
                aus.flush();
            } catch (IOException e) {
                schliessen("Senden: " + e.getMessage());
            }
        }
    }

    private void lesen(InputStream ein) {
        ByteArrayOutputStream teile = new ByteArrayOutputStream();
        int teilOpcode = 0;
        try {
            while (offen) {
                int b0 = ein.read();
                int b1 = ein.read();
                if (b0 < 0 || b1 < 0) throw new IOException("Server hat beendet");
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean maskiert = (b1 & 0x80) != 0;
                long n = b1 & 0x7F;
                if (n == 126) n = ((long) lies(ein) << 8) | lies(ein);
                else if (n == 127) {
                    n = 0;
                    for (int k = 0; k < 8; k++) n = (n << 8) | lies(ein);
                }
                if (n > 1_000_000) throw new IOException("Frame zu gross");
                byte[] m = new byte[4];
                if (maskiert) for (int k = 0; k < 4; k++) m[k] = (byte) lies(ein);
                byte[] nutz = new byte[(int) n];
                int gelesen = 0;
                while (gelesen < n) {
                    int r = ein.read(nutz, gelesen, (int) n - gelesen);
                    if (r < 0) throw new IOException("Server hat beendet");
                    gelesen += r;
                }
                if (maskiert) for (int k = 0; k < nutz.length; k++) nutz[k] ^= m[k & 3];
                switch (opcode) {
                    case 0x0: // Fortsetzung
                        teile.write(nutz);
                        if (fin && teilOpcode == 0x1) hoerer.text(teile.toString("UTF-8"));
                        if (fin) teile.reset();
                        break;
                    case 0x1:
                        if (fin) hoerer.text(new String(nutz, StandardCharsets.UTF_8));
                        else {
                            teile.reset();
                            teile.write(nutz);
                            teilOpcode = 0x1;
                        }
                        break;
                    case 0x2: // Binär vom Server wird nicht gebraucht
                        if (!fin) teilOpcode = 0x2;
                        break;
                    case 0x8:
                        frameSenden(0x8, new byte[0], 0, 0);
                        schliessen("Server hat geschlossen");
                        return;
                    case 0x9:
                        frameSenden(0xA, nutz, 0, nutz.length);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            schliessen(e.getMessage());
        }
    }

    private static int lies(InputStream ein) throws IOException {
        int c = ein.read();
        if (c < 0) throw new IOException("Server hat beendet");
        return c;
    }

    void schliessen(String grund) {
        boolean warOffen;
        synchronized (this) {
            warOffen = offen;
            offen = false;
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        if (warOffen) hoerer.geschlossen(grund == null ? "" : grund);
    }
}
