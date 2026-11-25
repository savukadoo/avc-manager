package local.cc3.manager;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A Home shortcut to the real Entertainment player; no duplicate player or playback state. */
final class HomeNowPlayingView extends LinearLayout {
    private final Screen screen;
    private final TextView status, title;
    private String rendered = "";

    HomeNowPlayingView(Screen screen) {
        super(screen);
        this.screen = screen;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(screen.dp(12), screen.dp(6), screen.dp(10), screen.dp(6));
        setMinimumHeight(screen.dp(56));
        setClickable(true); setFocusable(true);
        setTooltipText("Open music player");
        setOnClickListener(view -> screen.open(Screen.managerIntent(screen, "Entertainment").putExtra("media_tab", "music")));
        GradientDrawable normal = screen.shape(screen.colors.surface, 12);
        normal.setStroke(screen.dp(1), screen.colors.outline);
        GradientDrawable focused = screen.shape(screen.colors.selection, 12);
        focused.setStroke(screen.dp(2), screen.colors.accent);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[0], normal);
        setBackground(new RippleDrawable(ColorStateList.valueOf((screen.colors.accent & 0xFFFFFF) | 0x33000000), states, null));
        TextView note = screen.text("♪", 22, screen.colors.accent, 650);
        note.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(note, new LinearLayout.LayoutParams(screen.dp(30), -2));
        LinearLayout labels = screen.column();
        status = line("", 11, screen.colors.muted, 500);
        title = line("", 15, screen.colors.ink, 650);
        labels.addView(status); labels.addView(title);
        addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = screen.text("›", 22, screen.colors.muted, 500);
        arrow.setPadding(screen.dp(8), 0, 0, 0);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(arrow);
        refresh();
    }

    private TextView line(String value, int size, int color, int weight) {
        TextView line = screen.text(value, size, color, weight);
        line.setSingleLine(true); line.setEllipsize(TextUtils.TruncateAt.END);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return line;
    }

    void refresh() {
        EntertainmentPlaybackService.State state = EntertainmentPlaybackService.state();
        boolean visible = !state.id.isEmpty() && !state.video;
        setVisibility(visible ? VISIBLE : GONE);
        if (!visible) { rendered = ""; return; }
        String label = state.error ? "Playback problem" : state.preparing ? "Opening…" : state.playing ? "Now playing" : "Paused";
        if (!state.artist.isEmpty()) label += " · " + state.artist;
        String key = label + "\n" + state.title;
        if (key.equals(rendered)) return;
        rendered = key;
        status.setText(label); title.setText(state.title);
        setContentDescription(label + ". " + state.title + ". Open music player");
    }

    @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
}
