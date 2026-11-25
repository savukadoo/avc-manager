package local.cc3.manager;

import android.content.SharedPreferences;

final class DashboardStyle {
    static final String FACE = "dashboard_clock_face";
    static final String TILES = "dashboard_favourite_tiles";
    static final String HEADINGS = "dashboard_panel_headings";
    static final String[] FACES = {"digital", "analogue", "split", "stacked"};
    static final String[] FACE_NAMES = {"Digital", "Analogue", "Split", "Stacked"};
    static String validFace(String value) {
        for (String face : FACES) if (face.equals(value)) return face;
        return FACES[0];
    }
    static String face(SharedPreferences prefs) { return validFace(prefs.getString(FACE, FACES[0])); }
    static int tiles(SharedPreferences prefs) { return prefs.getInt(TILES, 6) == 4 ? 4 : 6; }
    static boolean headings(SharedPreferences prefs) { return prefs.getBoolean(HEADINGS, true); }
    static String faceName(String face) {
        for (int i = 0; i < FACES.length; i++) if (FACES[i].equals(face)) return FACE_NAMES[i];
        return FACE_NAMES[0];
    }
    static boolean preference(String key) { return FACE.equals(key) || TILES.equals(key) || HEADINGS.equals(key); }
    private DashboardStyle() {}
}
