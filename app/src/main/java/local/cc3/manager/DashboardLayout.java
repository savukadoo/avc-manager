package local.cc3.manager;

import android.content.SharedPreferences;

enum DashboardLayout {
    CC3_CLASSIC("cc3-classic", "Radio & Media", "Audio first, navigation below"),
    CC4_CARDS("cc4-cards", "Four-Panel Grid", "Navigation, phone, audio and apps"),
    CARPLAY("carplay", "Maps & Side Dock", "Maps beside phone and audio"),
    ANDROID_AUTO("android-auto", "Maps & Bottom Dock", "Maps and media above a dock"),
    BIG_SHORTCUTS("big-shortcuts", "Large Shortcuts", "A grid of your chosen apps"),
    CLOCK_FAVORITES("clock-favorites", "Clock & Favourites", "A clock beside your shortcuts");

    private static final String KEY = "dashboard_layout";
    private static final String PENDING = "dashboard_layout_pending";
    private static final String PENDING_THEME = "dashboard_theme_pending";
    private static final String PENDING_BACKGROUND = "dashboard_background_pending";
    private static final String PENDING_FACE = "dashboard_clock_pending";
    private static final String PENDING_TILES = "dashboard_tiles_pending";
    private static final String PENDING_HEADINGS = "dashboard_headings_pending";
    final String id;
    final String title;
    final String description;

    DashboardLayout(String id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
    }

    static DashboardLayout selected(SharedPreferences preferences) {
        return fromId(preferences.getString(KEY, CC3_CLASSIC.id));
    }

    static DashboardLayout fromId(String id) {
        for (DashboardLayout layout : values()) {
            if (layout.id.equals(id)) return layout;
        }
        return CC3_CLASSIC;
    }

    static void applyColour(SharedPreferences preferences, String theme) {
        SharedPreferences.Editor edit = preferences.edit().putString(Screen.APPEARANCE, theme);
        if (preferences.contains(PENDING)) edit.putString(PENDING_THEME, theme);
        edit.apply();
    }

    void save(SharedPreferences preferences) {
        save(preferences, preferences.getString(Screen.APPEARANCE, Screen.THEMES[0]), HomeBackground.selected(preferences));
    }

    void save(SharedPreferences preferences, String theme, String background) {
        save(preferences, theme, background, DashboardStyle.face(preferences), DashboardStyle.tiles(preferences));
    }

    void save(SharedPreferences preferences, String theme, String background, String face, int tiles) {
        save(preferences, theme, background, face, tiles, DashboardStyle.headings(preferences));
    }

    void save(SharedPreferences preferences, String theme, String background, String face, int tiles, boolean headings) {
        preferences.edit().putString(KEY, id).putString(Screen.APPEARANCE, theme)
                .putString(HomeBackground.KEY, HomeBackground.validStyle(background))
                .putString(DashboardStyle.FACE, DashboardStyle.validFace(face)).putInt(DashboardStyle.TILES, tiles == 4 ? 4 : 6)
                .putBoolean(DashboardStyle.HEADINGS, headings)
                .remove(PENDING).remove(PENDING_THEME).remove(PENDING_BACKGROUND).remove(PENDING_FACE).remove(PENDING_TILES)
                .remove(PENDING_HEADINGS).apply();
    }

    boolean stageForHome(SharedPreferences preferences, String theme, String background, String face, int tiles, boolean headings) {
        return preferences.edit().putString(PENDING, id).putString(PENDING_THEME, theme)
                .putString(PENDING_BACKGROUND, HomeBackground.validStyle(background))
                .putString(PENDING_FACE, DashboardStyle.validFace(face)).putInt(PENDING_TILES, tiles == 4 ? 4 : 6)
                .putBoolean(PENDING_HEADINGS, headings).commit();
    }

    static void finishHomeSelection(SharedPreferences preferences, boolean dashboardIsHome) {
        String pending = preferences.getString(PENDING, null);
        if (pending == null) return;
        if (dashboardIsHome) fromId(pending).save(preferences,
                preferences.getString(PENDING_THEME, preferences.getString(Screen.APPEARANCE, Screen.THEMES[0])),
                preferences.getString(PENDING_BACKGROUND, HomeBackground.selected(preferences)),
                preferences.getString(PENDING_FACE, DashboardStyle.face(preferences)),
                preferences.getInt(PENDING_TILES, DashboardStyle.tiles(preferences)),
                preferences.getBoolean(PENDING_HEADINGS, DashboardStyle.headings(preferences)));
        else preferences.edit().remove(PENDING).remove(PENDING_THEME).remove(PENDING_BACKGROUND).remove(PENDING_FACE).remove(PENDING_TILES)
                .remove(PENDING_HEADINGS).apply();
    }

    static boolean isLayoutPreference(String key) {
        return KEY.equals(key);
    }
}
