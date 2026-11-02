package de.lukas.kino;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Claude am Fernseher.
 *
 *  - Mikro-Taste der Fernbedienung = Push-to-Talk: halten = zuhören, loslassen = abschicken
 *    (POST /tv auf dem Laptop). Sendet die Fernbedienung nur einen kurzen Druck, hört er bis
 *    zur Sprechpause zu (oder bis zum zweiten Druck).
 *  - Live-Text: Während des Sprechens geht etwa jede Sekunde das bisher Gesprochene an POST /tv/zwischen,
 *    der erkannte Text erscheint sofort in der „du“-Blase.
 *  - Freisprechen (aus, Standard): Mikrofon-Signal per WebSocket an den
 *    Laptop (ws://…:10600/wake). Der erkennt „Hey Jarvis“, das Sprechende und schickt die Antwort.
 *  - Anzeige: oranger Rahmen + Glas-Karte als Overlay des StartseitenWaechters.
 *
 * Läuft als Vordergrunddienst (Typ Mikrofon), gestartet von Lukas Kino (Startseite).
 * Einstellungen per ADB:  am start-foreground-service -n de.lukas.kino/.Sprache
 *                         --es server http://192.168.178.48:10600 --ez wachwort true
 */
public class Sprache extends Service {

    static final String TAG = "LukasKinoSprache";
    static final String STANDARD_SERVER = "http://192.168.178.48:10600";

    private static final int RATE = 16000;
    private static final int STUECK = 2560;              // 80 ms PCM16 mono
    private static final long PTT_MIN_MS = 400;
    private static final long MAX_AUFNAHME_MS = 30_000;
    private static final long TIPP_MAX_MS = 15_000;
    private static final long TIPP_NICHTS_MS = 6_000;
    private static final long TIPP_STILLE_MS = 1_200;

    // Modi
    private static final int LAUSCHEN = 0;   // Wachwort-Stream läuft (falls an)
    private static final int PTT = 1;        // Taste gehalten
    private static final int TIPP = 2;       // kurzer Druck, Ende über Sprechpause
    private static final int WACH = 3;       // Wachwort erkannt, Laptop hört zu
    private static final int BESCHAEFTIGT = 4; // denkt oder spricht, kein Audio raus

    static volatile Sprache instanz;

    private final Handler haupt = new Handler(Looper.getMainLooper());
    private final Object sperre = new Object();
    private SharedPreferences einst;
    private AudioManager audio;
    private AudioFocusRequest fokus;

    private volatile boolean aufnahmeLaeuft;
    private AudioRecord rec;
    private Thread leser;
    private volatile boolean dienstLaeuft;

    private int modus = LAUSCHEN;
    private boolean ausWachwort;
    private final ByteArrayOutputStream aufnahme = new ByteArrayOutputStream();
    private final ArrayDeque<byte[]> vorlauf = new ArrayDeque<>();
    private long modusStart;
    // Sprechpause im Tipp-Modus
    private double grundRauschen = -1;
    private int rauschStuecke;
    private boolean spracheGehoert;
    private long letzteSprache;

    /** Gesprächsverlauf, solange der Rahmen offen ist: {"du"|"claude", text}. Zurück beendet das Gespräch. */
    private final List<String[]> verlauf = new ArrayList<>();
    private String sitzung;
    /** Die „du“-Blase der laufenden Frage (wird live mitgeschrieben), sonst null. */
    private String[] duLive;
    /** Zählt hoch bei jedem neuen Druck/Senden/Abbruch – alte Live- oder Antwort-Ergebnisse werden verworfen. */
    private volatile int liveRunde;
    private int sendeRunde;
    private long sendeZeit;
    private String hoerStatus = "";
    /** Claudes Antworttext, der erst erscheint, wenn die Stimme einsetzt. */
    private String wartendeAntwort;
    private final Runnable antwortZeigen = this::wartendeAntwortZeigen;

    /** Jarvis (Voice PE) spricht gerade und der Rahmen zeigt das Gespräch. */
    private boolean jarvisAnzeige;
    private volatile int jarvisRunde;
    private String jarvisAntwort;
    private boolean jarvisSpricht;
    /** Die Jarvis-Antwort läuft über den TV (Teufel hängt am TV, Voice-PE-Ton wäre nicht zu hören). */
    private boolean jarvisTonAmTv;
    private static final long LIVE_TAKT_MS = 900;
    private static final int LIVE_MAX_BYTES = RATE * 2 * 30;
    private static final long GESPRAECH_OFFEN_MS = 10 * 60_000;
    private static final String HINWEIS = "Info-Taste halten für Rückfrage · Zurück beendet";

    private MiniWebSocket ws;
    private Thread wsSchleife;
    private MediaPlayer spieler;
    private final Runnable schliessen = this::zurueckZumLauschen;
    private BroadcastReceiver bildschirm;

    // ------------------------------------------------------------------ Start

    static boolean mikrofonErlaubt(Context c) {
        return Build.VERSION.SDK_INT < 23
                || c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Startet den Dienst, falls er nicht läuft. Gibt false zurück, wenn es nicht ging. */
    static boolean starten(Context c) {
        if (instanz != null) return true;
        if (!mikrofonErlaubt(c)) {
            Log.w(TAG, "Mikrofon-Berechtigung fehlt (pm grant de.lukas.kino android.permission.RECORD_AUDIO)");
            return false;
        }
        try {
            Intent i = new Intent(c, Sprache.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Sprachdienst konnte nicht starten", e);
            return false;
        }
    }

    /** Tasten für Push-to-Talk (per ADB änderbar: --es tasten "84,231,219"). */
    private static volatile int[] tasten = {KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST};

    static boolean istSprachTaste(int k) {
        for (int t : tasten) if (t == k) return true;
        return false;
    }

    private void tastenLaden() {
        String s = einst.getString("sprach_tasten", "");
        if (s == null || s.trim().isEmpty()) return;
        try {
            String[] teile = s.split(",");
            int[] neu = new int[teile.length];
            for (int i = 0; i < teile.length; i++) {
                String t = teile[i].trim();
                neu[i] = t.matches("\\d+") ? Integer.parseInt(t) : KeyEvent.keyCodeFromString(t);
            }
            tasten = neu;
            Log.i(TAG, "Sprach-Tasten: " + s);
        } catch (Exception e) {
            Log.w(TAG, "Sprach-Tasten ungültig: " + s);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        einst = getSharedPreferences("lukaskino", MODE_PRIVATE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            vordergrund();
        } catch (Exception e) {
            Log.w(TAG, "Vordergrund nicht erlaubt – Dienst beendet sich", e);
            stopSelf();
            return;
        }
        instanz = this;
        dienstLaeuft = true;
        bildschirm = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                    Log.i(TAG, "Bildschirm aus – Mikrofon aus");
                    abbrechen();
                    aufnahmeStoppen();
                    wsZu("Bildschirm aus");
                } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                    if (wachwortAn()) {
                        Log.i(TAG, "Bildschirm an – Mikrofon an (Wachwort)");
                        aufnahmeStarten();
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(bildschirm, f);
        tastenLaden();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        // Mikrofon nur dauerhaft offen, wenn das Wachwort an ist (sonst nur, solange die Taste gedrückt ist).
        if ((pm == null || pm.isInteractive()) && wachwortAn()) aufnahmeStarten();
        wsSchleifeStarten();
        Log.i(TAG, "Sprachdienst läuft, Server " + server() + ", Wachwort " + wachwortAn());
    }

    private void vordergrund() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel k = new NotificationChannel("sprache", "Claude am Fernseher", NotificationManager.IMPORTANCE_MIN);
            k.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(k);
            b = new Notification.Builder(this, "sprache");
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setContentTitle("Claude am Fernseher")
                .setSmallIcon(R.drawable.icon)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(7, n);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        // Jeder startForegroundService()-Aufruf verlangt erneut startForeground(), sonst beendet Android die App.
        try {
            vordergrund();
        } catch (Exception e) {
            Log.w(TAG, "Vordergrund erneut nicht möglich", e);
        }
        if (i != null && i.hasExtra("jarvis")) {
            jarvisPhase(i.getStringExtra("jarvis"));
            return START_STICKY;
        }
        if (i != null && einst != null) {
            SharedPreferences.Editor e = einst.edit();
            boolean geaendert = false;
            if (i.hasExtra("server")) {
                e.putString("sprach_server", i.getStringExtra("server"));
                geaendert = true;
            }
            if (i.hasExtra("wachwort")) {
                e.putBoolean("sprach_wachwort", i.getBooleanExtra("wachwort", false));
                geaendert = true;
            }
            if (i.hasExtra("tasten")) {
                e.putString("sprach_tasten", i.getStringExtra("tasten"));
                geaendert = true;
            }
            if (geaendert) {
                e.commit();
                tastenLaden();
                if (wachwortAn()) aufnahmeStarten();
                else if (!istModus(PTT) && !istModus(TIPP)) aufnahmeStoppen();
                Log.i(TAG, "Einstellungen: Server " + server() + ", Wachwort " + wachwortAn());
                wsZu("Einstellungen geändert");
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        dienstLaeuft = false;
        instanz = null;
        try {
            if (bildschirm != null) unregisterReceiver(bildschirm);
        } catch (Exception ignored) {
        }
        abbrechen();
        aufnahmeStoppen();
        wsZu("Dienst beendet");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private String server() {
        String s = einst.getString("sprach_server", STANDARD_SERVER);
        return s == null || s.isEmpty() ? STANDARD_SERVER : s.replaceAll("/+$", "");
    }

    private boolean wachwortAn() {
        return einst.getBoolean("sprach_wachwort", false);
    }

    // ------------------------------------------------------------------ Mikrofon

    private synchronized void aufnahmeStarten() {
        if (aufnahmeLaeuft) return;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int puffer = Math.max(min, STUECK * 8);
        AudioRecord r = null;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, puffer);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, puffer);
            }
            if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord nicht bereit");
            r.startRecording();
        } catch (Exception e) {
            Log.w(TAG, "Mikrofon lässt sich nicht öffnen", e);
            if (r != null) r.release();
            return;
        }
        rec = r;
        aufnahmeLaeuft = true;
        final AudioRecord quelle = r;
        leser = new Thread(() -> {
            byte[] b = new byte[STUECK];
            while (aufnahmeLaeuft) {
                int n = 0;
                while (n < STUECK && aufnahmeLaeuft) {
                    int g = quelle.read(b, n, STUECK - n);
                    if (g <= 0) break;
                    n += g;
                }
                if (n == STUECK) verteilen(b);
                else if (aufnahmeLaeuft) SystemClock.sleep(20);
            }
        }, "LukasKinoMikro");
        leser.start();
        Log.i(TAG, "Mikrofon offen");
    }

    private synchronized void aufnahmeStoppen() {
        aufnahmeLaeuft = false;
        synchronized (sperre) {
            vorlauf.clear();
        }
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            rec.release();
            rec = null;
        }
        leser = null;
    }

    /** Jedes 80-ms-Stück landet hier (Mikro-Thread). */
    private void verteilen(byte[] stueck) {
        byte[] kopie = stueck.clone();
        boolean senden;
        boolean tippPruefen = false;
        synchronized (sperre) {
            vorlauf.addLast(kopie);
            while (vorlauf.size() > 3) vorlauf.removeFirst();
            senden = modus == LAUSCHEN || modus == WACH;
            if (modus == PTT || modus == TIPP) {
                aufnahme.write(kopie, 0, kopie.length);
                tippPruefen = modus == TIPP;
            }
        }
        if (senden && wachwortAn()) {
            MiniWebSocket w = ws;
            if (w != null && w.istOffen()) w.binaerSenden(kopie, kopie.length);
        }
        long jetzt = SystemClock.uptimeMillis();
        long dauer;
        synchronized (sperre) {
            dauer = jetzt - modusStart;
        }
        if (tippPruefen) tippPruefen(kopie, jetzt, dauer);
        else if (dauer > MAX_AUFNAHME_MS && istModus(PTT)) haupt.post(this::senden);
    }

    private boolean istModus(int m) {
        synchronized (sperre) {
            return modus == m;
        }
    }

    private void tippPruefen(byte[] b, long jetzt, long dauer) {
        double summe = 0;
        for (int i = 0; i + 1 < b.length; i += 2) {
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            summe += (double) s * s;
        }
        double rms = Math.sqrt(summe / (b.length / 2.0));
        if (rauschStuecke < 4) {
            grundRauschen = grundRauschen < 0 ? rms : (grundRauschen * rauschStuecke + rms) / (rauschStuecke + 1);
            rauschStuecke++;
        }
        double schwelle = Math.max(500, grundRauschen * 3);
        if (rms > schwelle) {
            spracheGehoert = true;
            letzteSprache = jetzt;
        }
        if (spracheGehoert && jetzt - letzteSprache > TIPP_STILLE_MS) haupt.post(this::senden);
        else if (!spracheGehoert && dauer > TIPP_NICHTS_MS) haupt.post(() -> fehler("Nichts gehört"));
        else if (dauer > TIPP_MAX_MS) haupt.post(this::senden);
    }

    // ------------------------------------------------------------------ Taste (vom StartseitenWaechter)

    private long druckBeginn;

    /** Mikro-Taste gedrückt (nur erstes DOWN). */
    void tasteRunter() {
        haupt.removeCallbacks(schliessen);
        synchronized (sperre) {
            if (modus == TIPP) {
                // Zweiter kurzer Druck im Tipp-Modus = abschicken.
                modus = PTT;
                druckBeginn = 0;
                haupt.post(this::senden);
                return;
            }
        }
        spielerStoppen();
        wartendeAntwortZeigen();
        sendeRunde++;   // eine noch ausstehende Antwort gilt nicht mehr
        jarvisBeenden();
        duLive = null;
        if (!aufnahmeLaeuft) aufnahmeStarten();
        synchronized (sperre) {
            modus = PTT;
            ausWachwort = false;
            modusStart = SystemClock.uptimeMillis();
            druckBeginn = modusStart;
            aufnahme.reset();
            for (byte[] v : vorlauf) aufnahme.write(v, 0, v.length);
        }
        fokusHolen();
        neueSitzungFallsNoetig();
        hoeren("");
        liveStarten();
        Log.i(TAG, "Push-to-Talk Start, Gespräch " + sitzung);
    }

    /** Mikro-Taste losgelassen. */
    void tasteHoch() {
        long dauer;
        synchronized (sperre) {
            if (modus != PTT || druckBeginn == 0) return;
            dauer = SystemClock.uptimeMillis() - druckBeginn;
            if (dauer < PTT_MIN_MS) {
                // Fernbedienung schickt nur einen kurzen Druck: bis zur Sprechpause zuhören.
                modus = TIPP;
                grundRauschen = -1;
                rauschStuecke = 0;
                spracheGehoert = false;
                Log.i(TAG, "Kurzer Druck – Tipp-Modus (Ende über Sprechpause)");
                haupt.post(() -> hoeren("Zum Senden nochmal drücken"));
                return;
            }
        }
        senden();
    }

    // ------------------------------------------------------------------ Senden (Push-to-Talk)

    private void senden() {
        final byte[] pcm;
        synchronized (sperre) {
            if (modus != PTT && modus != TIPP) return;
            modus = BESCHAEFTIGT;
            pcm = aufnahme.toByteArray();
            aufnahme.reset();
        }
        if (pcm.length < STUECK * 4) {
            fehler("Zu kurz – halte die Taste, solange du sprichst");
            return;
        }
        liveRunde++;    // Live-Text stoppen
        if (!wachwortAn()) aufnahmeStoppen();
        duAnlegen();
        zeige(SprachRahmen.DENKT, "");
        final String url = server() + "/tv?sitzung=" + sitzung;
        final int runde = ++sendeRunde;
        sendeZeit = SystemClock.uptimeMillis();
        new Thread(() -> {
            try {
                JSONObject j = postWav(url, pcm, 90_000);
                haupt.post(() -> {
                    if (runde == sendeRunde) antwortVerarbeiten(j);
                });
            } catch (Exception e) {
                Log.w(TAG, "Senden an den Laptop fehlgeschlagen", e);
                haupt.post(() -> {
                    if (runde == sendeRunde) fehler("Laptop nicht erreichbar");
                });
            }
        }, "LukasKinoSenden").start();
    }

    private static JSONObject postWav(String url, byte[] pcm, int leseZeit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(leseZeit);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "audio/wav");
        byte[] kopf = wavKopf(pcm.length);
        c.setFixedLengthStreamingMode(kopf.length + pcm.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(kopf);
            o.write(pcm);
        }
        int code = c.getResponseCode();
        InputStream ein = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (ein != null) {
            byte[] p = new byte[8192];
            int n;
            while ((n = ein.read(p)) > 0) b.write(p, 0, n);
            ein.close();
        }
        c.disconnect();
        String text = b.toString("UTF-8");
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject().put("fehler", "Antwort " + code);
        }
    }

    // ------------------------------------------------------------------ Live-Text beim Sprechen

    /** Schickt etwa jede Sekunde das bisher Gesprochene an /tv/zwischen (immer nur eine Anfrage gleichzeitig). */
    private void liveStarten() {
        final int runde = ++liveRunde;
        final String url = server() + "/tv/zwischen?sitzung=" + sitzung;
        Thread t = new Thread(() -> {
            int letzteLaenge = 0;
            SystemClock.sleep(600);
            while (liveRunde == runde && dienstLaeuft) {
                byte[] pcm;
                synchronized (sperre) {
                    if (modus != PTT && modus != TIPP) break;
                    pcm = aufnahme.toByteArray();
                }
                long start = SystemClock.uptimeMillis();
                if (pcm.length >= STUECK * 8 && pcm.length > letzteLaenge) {
                    letzteLaenge = pcm.length;
                    if (pcm.length > LIVE_MAX_BYTES) {
                        byte[] ende = new byte[LIVE_MAX_BYTES];
                        System.arraycopy(pcm, pcm.length - LIVE_MAX_BYTES, ende, 0, LIVE_MAX_BYTES);
                        pcm = ende;
                    }
                    try {
                        JSONObject j = postWav(url, pcm, 6000);
                        final String text = j.optString("text", "").trim();
                        Log.i(TAG, "Live-Text nach " + (SystemClock.uptimeMillis() - start) + " ms (" + text.length() + " Zeichen)");
                        if (!text.isEmpty()) haupt.post(() -> liveZeigen(runde, text));
                    } catch (Exception e) {
                        Log.i(TAG, "Live-Text fehlgeschlagen: " + e.getMessage());
                    }
                }
                long rest = LIVE_TAKT_MS - (SystemClock.uptimeMillis() - start);
                if (rest > 0) SystemClock.sleep(rest);
            }
        }, "LukasKinoLive");
        t.setDaemon(true);
        t.start();
    }

    private void liveZeigen(int runde, String text) {
        if (runde != liveRunde || !(istModus(PTT) || istModus(TIPP))) return;
        if (duLive == null) {
            duLive = new String[]{"du", text};
            verlauf.add(duLive);
        } else {
            duLive[1] = text;
        }
        zeige(SprachRahmen.HOERT, hoerStatus);
    }

    private void hoeren(String status) {
        hoerStatus = status;
        zeige(SprachRahmen.HOERT, status);
    }

    private void duAnlegen() {
        if (duLive == null) {
            duLive = new String[]{"du", "…"};
            verlauf.add(duLive);
        }
    }

    private static byte[] wavKopf(int datenLaenge) {
        byte[] h = new byte[44];
        schreib(h, 0, "RIFF");
        int32(h, 4, 36 + datenLaenge);
        schreib(h, 8, "WAVE");
        schreib(h, 12, "fmt ");
        int32(h, 16, 16);
        h[20] = 1;
        h[22] = 1;
        int32(h, 24, RATE);
        int32(h, 28, RATE * 2);
        h[32] = 2;
        h[34] = 16;
        schreib(h, 36, "data");
        int32(h, 40, datenLaenge);
        return h;
    }

    private static void schreib(byte[] h, int o, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, o, b.length);
    }

    private static void int32(byte[] h, int o, int v) {
        h[o] = (byte) v;
        h[o + 1] = (byte) (v >> 8);
        h[o + 2] = (byte) (v >> 16);
        h[o + 3] = (byte) (v >> 24);
    }

    // ------------------------------------------------------------------ Antwort

    private void antwortVerarbeiten(JSONObject j) {
        haupt.removeCallbacks(schliessen);
        if (j.has("fehler")) {
            fehler(j.optString("fehler", "Fehler"));
            return;
        }
        String erk = j.optString("erkannt", "");
        String ant = j.optString("antwort", "");
        String mp3 = j.optString("audio", "");
        final long gesendet = sendeZeit;
        Log.i(TAG, "Antwort nach " + (SystemClock.uptimeMillis() - gesendet) + " ms");
        synchronized (sperre) {
            modus = BESCHAEFTIGT;
        }
        duErsetzen(erk);
        wartendeAntwort = ant.isEmpty() ? null : ant;
        if (mp3.isEmpty()) {
            wartendeAntwortZeigen();
            gespraechWartet();
            return;
        }
        // Text und Stimme zeitgleich: bis der erste Ton kommt, tippt Claude (drei Punkte).
        zeige(SprachRahmen.DENKT, "");
        abspielen(mp3, gesendet, this::gespraechWartet);
    }

    /** Spielt die Antwort ab; der wartende Antworttext erscheint mit dem ersten Ton (spätestens nach 8 s). */
    private void abspielen(String mp3, final long seit, final Runnable danach) {
        haupt.removeCallbacks(antwortZeigen);
        haupt.postDelayed(antwortZeigen, 8000);
        spielerStoppen();
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setDataSource(mp3);
            p.setOnPreparedListener(x -> {
                Log.i(TAG, "Ton ab " + (SystemClock.uptimeMillis() - seit) + " ms");
                x.start();
                wartendeAntwortZeigen();
            });
            p.setOnCompletionListener(x -> {
                spielerStoppen();
                danach.run();
            });
            p.setOnErrorListener((x, a, b) -> {
                Log.w(TAG, "Wiedergabe-Fehler " + a + "/" + b);
                wartendeAntwortZeigen();
                spielerStoppen();
                danach.run();
                return true;
            });
            spieler = p;
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Antwort lässt sich nicht abspielen", e);
            wartendeAntwortZeigen();
            danach.run();
        }
    }

    /** Setzt das endgültig Erkannte in die „du“-Blase (oder entfernt sie, wenn nichts erkannt wurde). */
    private void duErsetzen(String erkannt) {
        String[] e = duLive;
        duLive = null;
        if (e == null) return;
        if (erkannt == null || erkannt.isEmpty()) verlauf.remove(e);
        else e[1] = erkannt;
    }

    private void wartendeAntwortZeigen() {
        haupt.removeCallbacks(antwortZeigen);
        String a = wartendeAntwort;
        wartendeAntwort = null;
        if (a == null) return;
        verlauf.add(new String[]{"claude", a});
        zeige(SprachRahmen.ANTWORT, "");
    }

    /** Antwort fertig: Rahmen bleibt offen für Rückfragen. Video/Musik bleibt pausiert, bis Zurück gedrückt wird. */
    private void gespraechWartet() {
        zeige(SprachRahmen.ANTWORT, HINWEIS);
        haupt.removeCallbacks(schliessen);
        haupt.postDelayed(schliessen, GESPRAECH_OFFEN_MS);
    }

    private void spielerStoppen() {
        MediaPlayer p = spieler;
        spieler = null;
        if (p != null) {
            try {
                p.stop();
            } catch (Exception ignored) {
            }
            p.release();
        }
    }

    private void fehler(String text) {
        liveRunde++;
        synchronized (sperre) {
            modus = BESCHAEFTIGT;
        }
        duErsetzen("");
        if (!wachwortAn()) aufnahmeStoppen();
        zeige(SprachRahmen.FEHLER, text);
        haupt.removeCallbacks(schliessen);
        if (verlauf.isEmpty()) {
            haupt.postDelayed(schliessen, 3500);
        } else {
            haupt.postDelayed(() -> zeige(SprachRahmen.ANTWORT, HINWEIS), 3500);
            haupt.postDelayed(schliessen, GESPRAECH_OFFEN_MS);
        }
    }

    /** Zurück-Taste oder Bildschirm aus: alles sofort beenden. */
    void abbrechen() {
        haupt.removeCallbacks(schliessen);
        spielerStoppen();
        zurueckZumLauschen();
    }

    private void zurueckZumLauschen() {
        synchronized (sperre) {
            modus = LAUSCHEN;
            ausWachwort = false;
            aufnahme.reset();
        }
        liveRunde++;
        sendeRunde++;
        duLive = null;
        wartendeAntwort = null;
        haupt.removeCallbacks(antwortZeigen);
        jarvisBeenden();
        verlauf.clear();
        sitzung = null;
        StartseitenWaechter.rahmenWeg();
        fokusAbgeben();
        // Ohne Wachwort das Mikrofon wieder schließen (dann ist auch der grüne Punkt weg).
        if (!wachwortAn()) aufnahmeStoppen();
    }

    private void zeige(int zustand, String status) {
        StartseitenWaechter.rahmen(zustand, status, new ArrayList<>(verlauf));
    }

    private void neueSitzungFallsNoetig() {
        if (sitzung == null) {
            sitzung = UUID.randomUUID().toString().substring(0, 8);
            verlauf.clear();
            duLive = null;
        }
    }

    // ------------------------------------------------------------------ Jarvis (Voice PE) am TV anzeigen

    /**
     * Phasen kommen von Home Assistant per ADB:
     * am start-foreground-service -n de.lukas.kino/.Sprache --es jarvis hoert|denkt|antwortet|fertig
     * Die Texte holt die App beim Laptop (GET /jarvis/letzte). Ton kommt vom Voice PE, der TV pausiert nur.
     */
    private void jarvisPhase(String phase) {
        if (phase == null) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        if (istModus(PTT) || istModus(TIPP)) return;   // Push-to-Talk hat Vorrang
        Log.i(TAG, "Jarvis: " + phase);
        switch (phase) {
            case "hoert": {
                haupt.removeCallbacks(schliessen);
                spielerStoppen();
                wartendeAntwortZeigen();
                sendeRunde++;
                neueSitzungFallsNoetig();
                jarvisAnzeige = true;
                jarvisAntwort = null;
                jarvisSpricht = false;
                jarvisTonAmTv = false;
                duLive = null;
                fokusHolen();
                zeige(SprachRahmen.HOERT, "");
                jarvisAbfragen(++jarvisRunde);
                haupt.postDelayed(schliessen, 40_000);
                break;
            }
            case "denkt":
                if (!jarvisAnzeige) return;
                duAnlegen();
                zeige(SprachRahmen.DENKT, "");
                haupt.removeCallbacks(schliessen);
                haupt.postDelayed(schliessen, 90_000);
                break;
            case "antwortet":
                if (!jarvisAnzeige || jarvisTonAmTv) return;
                jarvisSpricht = true;
                if (jarvisAntwort != null) jarvisAntwortZeigen();
                break;
            case "fertig":
                if (!jarvisAnzeige || jarvisTonAmTv) return;   // spielt der TV, schließt dessen Ende
                jarvisSpricht = false;
                if (jarvisAntwort != null) jarvisAntwortZeigen();
                haupt.removeCallbacks(schliessen);
                // Nichts gesagt (Fehlauslöser) → gleich weg, sonst kurz stehen lassen und dann Video weiter.
                haupt.postDelayed(schliessen, verlauf.isEmpty() ? 500 : 9000);
                break;
            default:
                break;
        }
    }

    private void jarvisBeenden() {
        jarvisAnzeige = false;
        jarvisRunde++;
        jarvisAntwort = null;
        jarvisSpricht = false;
        jarvisTonAmTv = false;
    }

    /** Holt erst den aktuellen Stand (Basis), dann alle 0,3 s, bis eine neue Anfrage samt Antwort da ist. */
    private void jarvisAbfragen(final int runde) {
        final String url = server() + "/jarvis/letzte";
        new Thread(() -> {
            JSONObject b = holeJson(url);
            if (b == null) {
                Log.i(TAG, "Jarvis-Text nicht abrufbar (Laptop?)");
                return;
            }
            int basis = b.optInt("nr", 0);
            long ende = SystemClock.uptimeMillis() + 90_000;
            String erkZuletzt = "";
            while (runde == jarvisRunde && SystemClock.uptimeMillis() < ende) {
                SystemClock.sleep(300);
                JSONObject j = holeJson(url);
                if (j == null || j.optInt("nr", 0) <= basis) continue;
                final String erk = j.optString("erkannt", "").trim();
                final String ant = j.optString("antwort", "").trim();
                final String ton = j.optString("audio", "").trim();
                if (!erk.equals(erkZuletzt) || !ant.isEmpty()) {
                    erkZuletzt = erk;
                    haupt.post(() -> jarvisText(runde, erk, ant, ton));
                }
                if (!ant.isEmpty()) break;
            }
        }, "LukasKinoJarvis").start();
    }

    private void jarvisText(int runde, String erk, String ant, String ton) {
        if (runde != jarvisRunde || !jarvisAnzeige) return;
        if (!erk.isEmpty()) {
            if (duLive == null) {
                duLive = new String[]{"du", erk};
                verlauf.add(duLive);
            } else {
                duLive[1] = erk;
            }
            zeige(SprachRahmen.DENKT, "");
        }
        if (ant.isEmpty() || jarvisTonAmTv) return;
        if (!ton.isEmpty()) {
            // Stimme über den TV: Text erscheint mit dem ersten Ton, danach noch kurz stehen lassen.
            jarvisTonAmTv = true;
            if (duLive != null && "…".equals(duLive[1])) verlauf.remove(duLive);
            duLive = null;
            wartendeAntwort = ant;
            zeige(SprachRahmen.DENKT, "");
            haupt.removeCallbacks(schliessen);
            abspielen(ton, SystemClock.uptimeMillis(), () -> {
                haupt.removeCallbacks(schliessen);
                haupt.postDelayed(schliessen, 9000);
            });
            return;
        }
        jarvisAntwort = ant;
        if (jarvisSpricht) jarvisAntwortZeigen();
    }

    private void jarvisAntwortZeigen() {
        String a = jarvisAntwort;
        jarvisAntwort = null;
        if (a == null) return;
        if (duLive != null && "…".equals(duLive[1])) verlauf.remove(duLive);
        duLive = null;
        verlauf.add(new String[]{"claude", a});
        zeige(SprachRahmen.ANTWORT, "");
    }

    private static JSONObject holeJson(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2500);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream ein = c.getInputStream()) {
                byte[] p = new byte[4096];
                int n;
                while ((n = ein.read(p)) > 0) b.write(p, 0, n);
            }
            return new JSONObject(b.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ Audiofokus

    private void fokusHolen() {
        if (audio == null || Build.VERSION.SDK_INT < 26) return;
        if (fokus == null) {
            fokus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build();
        }
        audio.requestAudioFocus(fokus);
    }

    private void fokusAbgeben() {
        if (audio != null && fokus != null && Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(fokus);
    }

    // ------------------------------------------------------------------ Wachwort (WebSocket zum Laptop)

    private void wsSchleifeStarten() {
        wsSchleife = new Thread(() -> {
            while (dienstLaeuft) {
                MiniWebSocket w = ws;
                if (wachwortAn() && aufnahmeLaeuft && (w == null || !w.istOffen())) {
                    String url = server().replaceFirst("^http", "ws") + "/wake";
                    MiniWebSocket neu = new MiniWebSocket(url, new MiniWebSocket.Hoerer() {
                        @Override
                        public void text(String nachricht) {
                            haupt.post(() -> wachEreignis(nachricht));
                        }

                        @Override
                        public void geschlossen(String grund) {
                            Log.i(TAG, "Wachwort-Verbindung zu: " + grund);
                        }
                    });
                    try {
                        neu.verbinden();
                        ws = neu;
                        Log.i(TAG, "Wachwort-Verbindung steht: " + url);
                    } catch (Exception e) {
                        Log.d(TAG, "Wachwort-Verbindung nicht möglich: " + e.getMessage());
                    }
                }
                SystemClock.sleep(5000);
            }
        }, "LukasKinoWachwort");
        wsSchleife.setDaemon(true);
        wsSchleife.start();
    }

    private void wsZu(String grund) {
        MiniWebSocket w = ws;
        ws = null;
        if (w != null) w.schliessen(grund);
    }

    private void wachEreignis(String nachricht) {
        JSONObject j;
        try {
            j = new JSONObject(nachricht);
        } catch (Exception e) {
            return;
        }
        String typ = j.optString("typ");
        int m;
        boolean vomWachwort;
        synchronized (sperre) {
            m = modus;
            vomWachwort = ausWachwort;
        }
        switch (typ) {
            case "wach":
                if (m != LAUSCHEN) return;
                haupt.removeCallbacks(schliessen);
                synchronized (sperre) {
                    modus = WACH;
                    ausWachwort = true;
                    modusStart = SystemClock.uptimeMillis();
                }
                fokusHolen();
                neueSitzungFallsNoetig();
                zeige(SprachRahmen.HOERT, "");
                // Sicherheitsnetz, falls vom Laptop nichts mehr kommt.
                haupt.postDelayed(schliessen, 20_000);
                Log.i(TAG, "Wachwort erkannt");
                break;
            case "denke":
                if (m != WACH) return;
                synchronized (sperre) {
                    modus = BESCHAEFTIGT;
                }
                haupt.removeCallbacks(schliessen);
                haupt.postDelayed(schliessen, 90_000);
                duAnlegen();
                zeige(SprachRahmen.DENKT, "");
                break;
            case "antwort":
                if (!vomWachwort) return;
                antwortVerarbeiten(j);
                break;
            case "nichts":
                if (vomWachwort && m == WACH) zurueckZumLauschen();
                break;
            case "fehler":
                if (vomWachwort) fehler(j.optString("text", "Fehler"));
                break;
            default:
                break;
        }
    }
}
