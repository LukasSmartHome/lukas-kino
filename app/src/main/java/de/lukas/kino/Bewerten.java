package de.lukas.kino;

import org.json.JSONObject;

/**
 * Bewerten am Fernseher (Wunsch Lukas 02.11.2026): nach dem Film (ab 80 %) und über den Knopf „Bewerten“ in den Details.
 * Zähler von 1 bis 10 (Start 5 bzw. die eigene alte Note), wie im Plex-Zeitstrahl in Home Assistant.
 * Lukas und Jonas haben je einen Zähler, die Eltern zwei (links Papa = Uli, rechts Mama = Moni).
 * Gespeichert wird über den Webhook von Automation 84 → Skript 00v (Plex bewerten) → sensor.plex_bewertungen.
 */
final class Bewerten {

    private Bewerten() {
    }

    static boolean eltern() {
        return "Eltern".equals(Merkliste.WER);
    }

    /** Wer an diesem Fernseher bewertet (Namen wie in HA). */
    static String[] namen() {
        return eltern() ? new String[]{"Uli", "Moni"} : new String[]{Merkliste.WER};
    }

    static String frage() {
        return eltern() ? "Mama und Papa, wie hat euch der Film gefallen?" : Merkliste.WER + ", wie hat dir der Film gefallen?";
    }

    static String danke() {
        return eltern() ? "Danke für eure Bewertung!" : "Danke für deine Bewertung!";
    }

    /** Beschriftung unter dem Zähler – bei den Eltern klar, wer wer ist. */
    static String schild(String name) {
        if ("Uli".equals(name)) return "Papa · Uli";
        if ("Moni".equals(name)) return "Mama · Moni";
        return name;
    }

    /** Farbe der Note wie in HA: 1 rot → orange → gelb → 10 grün. */
    static int farbe(int note) {
        int[] stufen = {0xFFE5484D, 0xFFF97316, 0xFFF2B705, 0xFF22C55E};
        float t = Math.max(0f, Math.min(1f, (note - 1) / 9f)) * (stufen.length - 1);
        int i = Math.min(stufen.length - 2, (int) Math.floor(t));
        float f = t - i;
        int a = stufen[i], b = stufen[i + 1];
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Eine Note an Home Assistant schicken. Blockiert – nur im Hintergrund. */
    static void senden(JSONObject film, String wer, int note) throws Exception {
        String titel = film.optString("Name") + (film.optInt("ProductionYear", 0) > 0 ? " (" + film.optInt("ProductionYear") + ")" : "");
        Merkliste.webhookSenden("art=bewerten&wer=" + Jellyfin.enc(wer) + "&id=" + Jellyfin.enc(Merkliste.haIdFuer(film))
                + "&titel=" + Jellyfin.enc(titel) + "&wertung=" + note);
    }
}
