package local.cc3.manager;

import android.content.Context;
import android.content.Intent;

public final class DashboardPreviewActivity extends DashboardActivity {
    private static final String LAYOUT = "layout";
    private static final String THEME = "preview_theme";
    private static final String BACKGROUND = "preview_background";

    static Intent intent(Context context, DashboardLayout layout, String theme, String background, String face, int tiles, boolean headings) {
        return new Intent(context, DashboardPreviewActivity.class).putExtra(LAYOUT, layout.id)
                .putExtra(THEME, theme).putExtra(BACKGROUND, background)
                .putExtra("preview_clock", face).putExtra("preview_tiles", tiles).putExtra("preview_headings", headings);
    }

    @Override String currentTheme() {
        String theme = getIntent().getStringExtra(THEME);
        for (String candidate : THEMES) if (candidate.equals(theme)) return candidate;
        return super.currentTheme();
    }

    @Override String currentBackground() {
        String background = getIntent().getStringExtra(BACKGROUND);
        return background == null ? super.currentBackground() : HomeBackground.validStyle(background);
    }

    @Override String currentClockFace() { return DashboardStyle.validFace(getIntent().getStringExtra("preview_clock")); }
    @Override int currentFavouriteTiles() { return getIntent().getIntExtra("preview_tiles", super.currentFavouriteTiles()) == 4 ? 4 : 6; }
    @Override boolean currentPanelHeadings() { return getIntent().getBooleanExtra("preview_headings", super.currentPanelHeadings()); }

    @Override DashboardLayout previewLayout() {
        return DashboardLayout.fromId(getIntent().getStringExtra(LAYOUT));
    }
}
