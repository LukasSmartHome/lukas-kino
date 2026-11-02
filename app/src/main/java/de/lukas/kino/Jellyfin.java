package de.lukas.kino;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Verbindung zum Jellyfin-Server auf dem Pi (Lukas Kino, Filmbereich).
 * Anmeldung nur per Quick Connect, der Schluessel liegt in den SharedPreferences "lukaskino".
 * Alle Methoden blockieren – nur aus Hintergrund-Threads aufrufen.
 */
final class Jellyfin {

    static final String SERVER = "http://192.168.178.44:8096";
    static final String CLIENT = "Lukas Kino";
    static final String GERAET = "TCL Fernseher";
    static final String GERAET_ID = "lukaskino-tcl";
    static String VERSION = "1";

    static final String FELDER = "Overview,Genres,OfficialRating,CommunityRating,ProductionYear,RunTimeTicks,"
            + "ImageTags,BackdropImageTags,UserData,ChildCount,OriginalTitle,ParentBackdropImageTags,DateCreated,PremiereDate,EndDate";
    static final String FELDER_FOTO = "ImageTags,ChildCount,DateCreated,PremiereDate,RunTimeTicks,UserData,Width,Height";

    private static Jellyfin instanz;

    private final SharedPreferences prefs;
    volatile String token;
    volatile String benutzer;

    private Jellyfin(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("lukaskino", Context.MODE_PRIVATE);
        token = prefs.getString("jf_token", null);
        benutzer = prefs.getString("jf_user", null);
        try {
            VERSION = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
    }

    static synchronized Jellyfin get(Context c) {
        if (instanz == null) instanz = new Jellyfin(c);
        return instanz;
    }

    boolean angemeldet() {
        return token != null && benutzer != null;
    }

    void abmelden() {
        token = null;
        benutzer = null;
        prefs.edit().remove("jf_token").remove("jf_user").apply();
    }

    String authHeader() {
        StringBuilder sb = new StringBuilder("MediaBrowser Client=\"").append(CLIENT)
                .append("\", Device=\"").append(GERAET)
                .append("\", DeviceId=\"").append(GERAET_ID)
                .append("\", Version=\"").append(VERSION).append('"');
        if (token != null) sb.append(", Token=\"").append(token).append('"');
        return sb.toString();
    }

    // ---------------------------------------------------------------- Quick Connect

    /** Liefert {Code, Secret}. */
    JSONObject quickConnectStart() throws Exception {
        return new JSONObject(anfrage("POST", "/QuickConnect/Initiate", null));
    }

    boolean quickConnectBestaetigt(String secret) throws Exception {
        JSONObject o = new JSONObject(anfrage("GET", "/QuickConnect/Connect?secret=" + enc(secret), null));
        return o.optBoolean("Authenticated", false);
    }

    void quickConnectAbschliessen(String secret) throws Exception {
        JSONObject body = new JSONObject().put("Secret", secret);
        JSONObject o = new JSONObject(anfrage("POST", "/Users/AuthenticateWithQuickConnect", body.toString()));
        token = o.getString("AccessToken");
        benutzer = o.getJSONObject("User").getString("Id");
        prefs.edit().putString("jf_token", token).putString("jf_user", benutzer).apply();
    }

    // ---------------------------------------------------------------- Listen

    JSONArray weiterschauen() throws Exception {
        return liste("/UserItems/Resume?userId=" + benutzer + "&mediaTypes=Video&includeItemTypes=Movie&limit=24&fields=" + FELDER
                + "&enableImageTypes=Primary,Backdrop,Logo");
    }

    JSONArray neu() throws Exception {
        return liste("/Items?userId=" + benutzer + "&includeItemTypes=Movie&recursive=true&sortBy=DateCreated&sortOrder=Descending"
                + "&limit=40&fields=" + FELDER);
    }

    JSONArray sammlungen() throws Exception {
        return liste("/Items?userId=" + benutzer + "&includeItemTypes=BoxSet&recursive=true&sortBy=SortName&fields=ChildCount");
    }

    JSONArray inhalt(String elternId) throws Exception {
        return liste("/Items?userId=" + benutzer + "&parentId=" + elternId
                + "&includeItemTypes=Movie&sortBy=PremiereDate,SortName&sortOrder=Descending&fields=" + FELDER);
    }

    JSONArray alle() throws Exception {
        return liste("/Items?userId=" + benutzer + "&includeItemTypes=Movie&recursive=true&sortBy=SortName&fields=" + FELDER);
    }

    // ---------------------------------------------------------------- Bibliotheken, Serien, Fotos, Suche

    /** Bibliotheken des Benutzers (Filme, Serien, Fotos, Sammlungen). */
    JSONArray bibliotheken() throws Exception {
        return liste("/UserViews?userId=" + benutzer);
    }

    JSONArray serienWeiterschauen() throws Exception {
        return liste("/UserItems/Resume?userId=" + benutzer + "&mediaTypes=Video&includeItemTypes=Episode&limit=24&fields=" + FELDER);
    }

    JSONArray naechsteFolgen() throws Exception {
        return liste("/Shows/NextUp?userId=" + benutzer + "&limit=24&fields=" + FELDER);
    }

    JSONArray serien() throws Exception {
        return liste("/Items?userId=" + benutzer + "&includeItemTypes=Series&recursive=true&sortBy=SortName&fields=" + FELDER);
    }

    JSONArray neueSerien() throws Exception {
        return liste("/Items?userId=" + benutzer + "&includeItemTypes=Series&recursive=true&sortBy=DateLastContentAdded,DateCreated"
                + "&sortOrder=Descending&limit=30&fields=" + FELDER);
    }

    JSONArray staffeln(String serie) throws Exception {
        return liste("/Shows/" + serie + "/Seasons?userId=" + benutzer + "&fields=" + FELDER);
    }

    JSONArray folgen(String serie, String staffel) throws Exception {
        return liste("/Shows/" + serie + "/Episodes?userId=" + benutzer + "&seasonId=" + staffel + "&fields=" + FELDER);
    }

    /** Inhalt eines Foto-Ordners: erst Unterordner, dann Bilder und Videos nach Aufnahmedatum. */
    JSONArray ordner(String id) throws Exception {
        return liste("/Items?userId=" + benutzer + "&parentId=" + id + "&sortBy=IsFolder,PremiereDate,DateCreated,SortName"
                + "&sortOrder=Ascending&fields=" + FELDER_FOTO);
    }

    /** Alles Durchsuchbare (Filme und Serien) für die Suche, einmal laden und im Speicher halten. */
    JSONArray suchIndex() throws Exception {
        return liste("/Items?userId=" + benutzer + "&includeItemTypes=Movie,Series&recursive=true&sortBy=SortName&fields=" + FELDER);
    }

    static String foto(String id, String tag) {
        return SERVER + "/Items/" + id + "/Images/Primary?maxWidth=1920&maxHeight=1080&quality=90" + (tag != null ? "&tag=" + tag : "");
    }

    static String quer(String id, String tag, int breite) {
        return SERVER + "/Items/" + id + "/Images/Primary?fillWidth=" + breite + "&quality=85" + (tag != null ? "&tag=" + tag : "");
    }

    JSONObject film(String id) throws Exception {
        return new JSONObject(anfrage("GET", "/Items/" + id + "?userId=" + benutzer + "&fields=" + FELDER + ",MediaSources", null));
    }

    private JSONArray liste(String pfad) throws Exception {
        JSONObject o = new JSONObject(anfrage("GET", pfad, null));
        JSONArray a = o.optJSONArray("Items");
        return a != null ? a : new JSONArray();
    }

    // ---------------------------------------------------------------- Bilder

    static String poster(String id, String tag, int hoehe) {
        return SERVER + "/Items/" + id + "/Images/Primary?fillHeight=" + hoehe + "&quality=90" + (tag != null ? "&tag=" + tag : "");
    }

    static String hintergrund(String id, String tag) {
        return SERVER + "/Items/" + id + "/Images/Backdrop/0?fillWidth=1920&quality=85" + (tag != null ? "&tag=" + tag : "");
    }

    static String logo(String id, String tag) {
        return SERVER + "/Items/" + id + "/Images/Logo?fillHeight=220&quality=90" + (tag != null ? "&tag=" + tag : "");
    }

    // ---------------------------------------------------------------- Wiedergabe

    String streamUrl(String id, String quelle) {
        return SERVER + "/Videos/" + id + "/stream?static=true&mediaSourceId=" + quelle + "&deviceId=" + GERAET_ID;
    }

    void meldeStart(String id, String quelle, long ticks, String sitzung) {
        melde("/Sessions/Playing", id, quelle, ticks, false, sitzung);
    }

    void meldeFortschritt(String id, String quelle, long ticks, boolean pause, String sitzung) {
        melde("/Sessions/Playing/Progress", id, quelle, ticks, pause, sitzung);
    }

    void meldeStopp(String id, String quelle, long ticks, String sitzung) {
        melde("/Sessions/Playing/Stopped", id, quelle, ticks, false, sitzung);
    }

    private void melde(String pfad, String id, String quelle, long ticks, boolean pause, String sitzung) {
        try {
            JSONObject b = new JSONObject()
                    .put("ItemId", id)
                    .put("MediaSourceId", quelle)
                    .put("PositionTicks", ticks)
                    .put("IsPaused", pause)
                    .put("CanSeek", true)
                    .put("PlayMethod", "DirectPlay")
                    .put("PlaySessionId", sitzung);
            anfrage("POST", pfad, b.toString());
        } catch (Exception ignored) {
            // Melden ist nicht kritisch
        }
    }

    // ---------------------------------------------------------------- HTTP

    String anfrage(String methode, String pfad, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(SERVER + pfad).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestMethod(methode);
        c.setRequestProperty("Authorization", authHeader());
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        } else if ("POST".equals(methode)) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
        }
        int code = c.getResponseCode();
        if (code == 401 && token != null) abmelden();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : lesen(in);
        c.disconnect();
        if (code >= 400) throw new Exception("Jellyfin " + code + " " + pfad);
        return text.isEmpty() ? "{}" : text;
    }

    static String lesen(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------- kleine Helfer fuer Film-JSON

    static String tag(JSONObject film, String typ) {
        JSONObject t = film.optJSONObject("ImageTags");
        return t != null ? t.optString(typ, null) : null;
    }

    static String hintergrundTag(JSONObject film) {
        JSONArray a = film.optJSONArray("BackdropImageTags");
        return a != null && a.length() > 0 ? a.optString(0, null) : null;
    }

    /** Fortschritt 0..1 aus UserData. */
    static float fortschritt(JSONObject film) {
        JSONObject u = film.optJSONObject("UserData");
        if (u == null) return 0f;
        double p = u.optDouble("PlayedPercentage", 0);
        if (p <= 0) {
            long pos = u.optLong("PlaybackPositionTicks", 0);
            long ges = film.optLong("RunTimeTicks", 0);
            if (pos > 0 && ges > 0) p = 100.0 * pos / ges;
        }
        return (float) Math.max(0, Math.min(1, p / 100.0));
    }

    static long position(JSONObject film) {
        JSONObject u = film.optJSONObject("UserData");
        return u != null ? u.optLong("PlaybackPositionTicks", 0) : 0;
    }

    static boolean gesehen(JSONObject film) {
        JSONObject u = film.optJSONObject("UserData");
        return u != null && u.optBoolean("Played", false);
    }
}
