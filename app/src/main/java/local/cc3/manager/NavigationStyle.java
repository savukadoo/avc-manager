package local.cc3.manager;

import android.content.SharedPreferences;

/** Manager navigation presentation; independent of the selected Home layout. */
final class NavigationStyle {
    static final String KEY = "manager_navigation_style";
    static boolean icons(SharedPreferences prefs) { return "icons".equals(prefs.getString(KEY, "words")); }
    static int icon(String page) {
        switch (page) {
            case "Access": return R.drawable.ic_nav_wireless;
            case "Traffic": return R.drawable.ic_nav_firewall;
            case "Audio": return R.drawable.ic_nav_sound;
            case "Appearance": return R.drawable.ic_nav_appearance;
            case "Controls": return R.drawable.ic_nav_controls;
            case "Vehicle": return R.drawable.ic_nav_vehicle;
            case "Entertainment": return R.drawable.ic_nav_entertainment;
            default: return R.drawable.ic_home_outline;
        }
    }
    private NavigationStyle() {}
}
