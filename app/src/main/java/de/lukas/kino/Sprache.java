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

/**
 * Claude am Fernseher.
 *
 *  - Mikro-Taste der Fernbedienung = Push-to-Talk: halten = zuhören, loslassen = abschicken
 *    (POST /tv auf dem Laptop). Sendet die Fernbedienung nur einen kurzen Druck, hört er bis
 *    zur Sprechpause zu (oder bis zum zweiten Druck).
 *  - Freisprechen: Solange der Fernseher an ist, geht das Mikrofon-Signal per WebSocket an den
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

    static boolean istSprachTaste(int k) {
        return k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST;
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
                    Log.i(TAG, "Bildschirm an – Mikrofon an");
                    aufnahmeStarten();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(bildschirm, f);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isInteractive()) aufnahmeStarten();
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
        Notification n = b.setContentTitle("Claude hört auf „Hey Jarvis“")
                .setSmallIcon(R.drawable.icon)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(7, n);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null) {
            SharedPreferences.Editor e = einst.edit();
            boolean geaendert = false;
            if (i.hasExtra("server")) {
                e.putString("sprach_server", i.getStringExtra("server"));
                geaendert = true;
            }
            if (i.hasExtra("wachwort")) {
                e.putBoolean("sprach_wachwort", i.getBooleanExtra("wachwort", true));
                geaendert = true;
            }
            if (geaendert) {
                e.apply();
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
        return einst.getBoolean("sprach_wachwort", true);
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
        StartseitenWaechter.rahmen(SprachRahmen.HOERT, "Ich höre zu …", "", "");
        Log.i(TAG, "Push-to-Talk Start");
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
                StartseitenWaechter.rahmen(SprachRahmen.HOERT, "Ich höre zu … (zum Senden nochmal drücken)", "", "");
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
        StartseitenWaechter.rahmen(SprachRahmen.DENKT, "Ich denke …", null, null);
        final String url = server() + "/tv";
        new Thread(() -> {
            try {
                JSONObject j = postWav(url, pcm);
                haupt.post(() -> antwortVerarbeiten(j));
            } catch (Exception e) {
                Log.w(TAG, "Senden an den Laptop fehlgeschlagen", e);
                haupt.post(() -> fehler("Laptop nicht erreichbar"));
            }
        }, "LukasKinoSenden").start();
    }

    private static JSONObject postWav(String url, byte[] pcm) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(90_000);
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
        synchronized (sperre) {
            modus = BESCHAEFTIGT;
        }
        StartseitenWaechter.rahmen(SprachRahmen.ANTWORT, "Claude", erk, ant);
        if (mp3.isEmpty()) {
            haupt.postDelayed(schliessen, 6000 + ant.length() * 40L);
            return;
        }
        spielerStoppen();
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setDataSource(mp3);
            p.setOnPreparedListener(MediaPlayer::start);
            p.setOnCompletionListener(x -> {
                spielerStoppen();
                haupt.postDelayed(schliessen, 4000);
            });
            p.setOnErrorListener((x, a, b) -> {
                spielerStoppen();
                haupt.postDelayed(schliessen, 6000);
                return true;
            });
            spieler = p;
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Antwort lässt sich nicht abspielen", e);
            haupt.postDelayed(schliessen, 6000);
        }
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
        synchronized (sperre) {
            modus = BESCHAEFTIGT;
        }
        StartseitenWaechter.rahmen(SprachRahmen.FEHLER, text, "", "");
        haupt.removeCallbacks(schliessen);
        haupt.postDelayed(schliessen, 3500);
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
        StartseitenWaechter.rahmenWeg();
        fokusAbgeben();
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
                StartseitenWaechter.rahmen(SprachRahmen.HOERT, "Ich höre zu …", "", "");
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
                StartseitenWaechter.rahmen(SprachRahmen.DENKT, "Ich denke …", null, null);
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
