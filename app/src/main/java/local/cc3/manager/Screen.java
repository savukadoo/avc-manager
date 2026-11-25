package local.cc3.manager;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.Context;
import android.content.res.TypedArray;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

abstract class Screen extends Activity {
    static final String APPEARANCE = "appearance";
    // Persisted protocol identifiers must survive a change to the display name.
    static final String STORAGE_NAME = "cc3_manager";
    static final String MANAGER_ROUTE = STORAGE_NAME + "_route";
    static final String[] THEMES = {"Obsidian", "Midnight Blue", "Dusk", "Copper"};
    LinearLayout content;
    Palette colors;
    private int appliedAccentStyle;
    private final java.util.List<Dialog> dialogs=new java.util.ArrayList<>();

    static final class Palette {
        final int background, surface, ink, muted, accent, onAccent, outline, selection;

        final int accentStyle;
        final String description;

        Palette(Context context, int accentStyle, String description, String background, String surface,
                String ink, String muted, String onAccent) {
            this.accentStyle = accentStyle;
            this.description = description;
            TypedArray accentAttributes = context.obtainStyledAttributes(accentStyle, new int[]{android.R.attr.colorAccent});
            try {
                this.accent = accentAttributes.getColor(0, Color.WHITE);
            } finally {
                accentAttributes.recycle();
            }
            this.background = Color.parseColor(background);
            this.surface = Color.parseColor(surface);
            this.ink = Color.parseColor(ink);
            this.muted = Color.parseColor(muted);
            this.onAccent = Color.parseColor(onAccent);
            outline = blend(this.surface, this.muted, 0.30f);
            selection = blend(this.surface, this.accent, 0.15f);
        }
    }

    private static int blend(int from, int to, float ratio) {
        return Color.rgb(Math.round(Color.red(from) * (1 - ratio) + Color.red(to) * ratio),
                Math.round(Color.green(from) * (1 - ratio) + Color.green(to) * ratio),
                Math.round(Color.blue(from) * (1 - ratio) + Color.blue(to) * ratio));
    }

    static Palette palette(Context context, String theme) {
        switch (theme) {
            case "Midnight Blue":
                return new Palette(context, R.style.AvcMidnightAccent, "CC3-inspired · Indigo and cyan",
                        "#10132A", "#20254A", "#F3F5FF", "#BAC6E4", "#062430");
            case "Dusk":
                return new Palette(context, R.style.AvcDuskAccent, "Plum and orchid",
                        "#1A1422", "#2B2135", "#FAF2FC", "#D6C1DB", "#35152E");
            case "Copper":
                return new Palette(context, R.style.AvcCopperAccent, "Espresso and copper",
                        "#251811", "#38251B", "#FFF2E7", "#E1C4B1", "#2B150C");
            default:
                return new Palette(context, R.style.AvcObsidianAccent, "CC4-inspired · Graphite and ice blue",
                        "#101418", "#20272C", "#F2F5F8", "#B7C4CE", "#102331");
        }
    }

    String currentTheme() {
        String saved = prefs().getString(APPEARANCE, THEMES[0]);
        if ("Daylight".equals(saved) || "Porcelain".equals(saved) || "Dark".equals(saved)) return "Copper";
        if ("Amber".equals(saved)) return "Dusk";
        for (String theme : THEMES) {
            if (theme.equals(saved)) return theme;
        }
        return THEMES[0];
    }

    String currentBackground() {
        return HomeBackground.selected(prefs());
    }

    String currentClockFace() { return DashboardStyle.face(prefs()); }
    int currentFavouriteTiles() { return DashboardStyle.tiles(prefs()); }
    boolean currentPanelHeadings() { return DashboardStyle.headings(prefs()); }

    Drawable workspaceBackground() {
        return HomeBackground.drawable(this, currentTheme(), currentBackground(), false);
    }

    SharedPreferences prefs() {
        return getSharedPreferences(STORAGE_NAME, MODE_PRIVATE);
    }

    static Intent managerIntent(android.content.Context context, String section) {
        return new Intent(context, MainActivity.class).putExtra("screen", section)
                .putExtra(MANAGER_ROUTE, android.os.SystemClock.elapsedRealtimeNanos());
    }

    Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    ResolveInfo currentHome() {
        ResolveInfo result = getPackageManager().resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        return result == null || result.activityInfo == null || "android".equals(result.activityInfo.packageName)
                || "com.android.settings.FallbackHome".equals(result.activityInfo.name) ? null : result;
    }

    boolean dashboardIsHome() {
        ResolveInfo home = currentHome();
        return home != null && getPackageName().equals(home.activityInfo.packageName);
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    // Android10 compatibility: this scoped platform path intentionally retains legacy APIs.
    @SuppressWarnings("deprecation")
    void prepareWindow() {
        colors = palette(this, currentTheme());
        if (appliedAccentStyle != colors.accentStyle) {
            getTheme().applyStyle(colors.accentStyle, true);
            appliedAccentStyle = colors.accentStyle;
        }
        // The CC3 overlays white vehicle indicators on the system bar.
        int bar = blend(colors.background, Color.BLACK, 0.25f);
        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(bar);
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    TextView text(String value, int size, int color, boolean bold) {
        return text(value, size, color, bold ? 650 : 450);
    }

    TextView text(String value, int size, int color, int weight) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    Drawable consoleIcon(int glyph) {
        GradientDrawable plate = new GradientDrawable();
        plate.setShape(GradientDrawable.OVAL);
        plate.setColor(colors.selection);
        Drawable artwork = getDrawable(glyph).mutate();
        artwork.setTint(colors.accent);
        return new LayerDrawable(new Drawable[] {plate, new InsetDrawable(artwork, 0.16f)});
    }

    Button button(String name, boolean primary, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(name);
        b.setTextSize(15);
        b.setTypeface(Typeface.create(Typeface.DEFAULT, 550, false));
        b.setLetterSpacing(0);
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setMinHeight(dp(56));
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        styleButton(b, colors, primary, false, false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    void inlineButtonIcon(Button button, Drawable icon) {
        String label = button.getText().toString();
        icon.setBounds(0, 0, dp(28), dp(28));
        SpannableString text = new SpannableString("\ufffc  " + label);
        text.setSpan(new ButtonIconSpan(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(text);
        button.setContentDescription(label);
        button.setGravity(Gravity.CENTER);
        button.setMaxLines(2);
    }

    /** Centre inline dock icons on the label's font, rather than its baseline. */
    static final class ButtonIconSpan extends ImageSpan {
        ButtonIconSpan(Drawable icon) { super(icon); }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            Drawable icon = getDrawable();
            Paint.FontMetricsInt font = paint.getFontMetricsInt();
            if (metrics != null) {
                int top = Math.round((font.ascent + font.descent - icon.getBounds().height()) / 2f);
                metrics.ascent = Math.min(font.ascent, top);
                metrics.descent = Math.max(font.descent, top + icon.getBounds().height());
                metrics.top = Math.min(font.top, metrics.ascent);
                metrics.bottom = Math.max(font.bottom, metrics.descent);
                metrics.leading = font.leading;
            }
            return icon.getBounds().width();
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int baseline, int bottom, Paint paint) {
            Drawable icon = getDrawable();
            Paint.FontMetricsInt font = paint.getFontMetricsInt();
            float centre = baseline + (font.ascent + font.descent) / 2f;
            canvas.save();
            canvas.translate(x - icon.getBounds().left, centre - icon.getBounds().height() / 2f - icon.getBounds().top);
            icon.draw(canvas); canvas.restore();
        }
    }

    void styleButton(Button button, Palette palette, boolean primary, boolean outline, boolean selected) {
        int fill = primary ? palette.accent : selected ? palette.selection
                : blend(palette.surface, palette.ink, outline ? 0.045f : 0.08f);
        int ink = primary ? palette.onAccent : palette.ink;
        int stroke = selected ? palette.accent : outline ? palette.outline : 0;
        styleButton(button, fill, ink, stroke, selected ? 2 : 1,
                primary ? palette.onAccent : palette.accent);
    }

    void styleButton(Button button, int fill, int ink, int stroke, int strokeWidth, int focusColor) {
        GradientDrawable shape = shape(fill, 12);
        if (stroke != 0) shape.setStroke(dp(strokeWidth), stroke);
        GradientDrawable focus = shape(fill, 12);
        focus.setStroke(dp(2), focusColor);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focus);
        states.addState(new int[0], shape);
        button.setTextColor(new ColorStateList(new int[][] {
                {-android.R.attr.state_enabled}, {}
        }, new int[] {blend(fill, ink, 0.56f), ink}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                (ink & 0x00ffffff) | 0x2e000000), states, null));
    }

    void tile(LinearLayout line, Button b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        line.addView(b, p);
    }

    void buttons(Button... actions) {
        int width = getResources().getConfiguration().screenWidthDp;
        int columns = width < 720 ? 2 : width >= 1000 && actions.length == 4 ? 4 : 3;
        for (int i = 0; i < actions.length; i += columns) {
            LinearLayout line = row();
            for (int j = i; j < Math.min(i + columns, actions.length); j++) tile(line, actions[j]);
            content.addView(line);
        }
    }

    LinearLayout helpHeading(String title, int size, String help) {
        return helpHeading(title, title, size, help);
    }

    LinearLayout helpHeading(String label, String title, int size, String help) {
        LinearLayout group = row();
        TextView heading = text(label, size, colors.ink, 650);
        heading.setAccessibilityHeading(true);
        group.addView(heading, new LinearLayout.LayoutParams(-2, -2, 1));
        group.addView(infoButton(title, help), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout wrapper = row();
        wrapper.addView(group, new LinearLayout.LayoutParams(-2, -2));
        return wrapper;
    }

    ImageButton infoButton(String title, String help) {
        ImageButton info = new ImageButton(this);
        info.setImageResource(R.drawable.ic_info_outline);
        info.setImageTintList(ColorStateList.valueOf(colors.ink));
        info.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        info.setPadding(0, 0, 0, 0);
        info.setStateListAnimator(null);
        info.setContentDescription("About " + title);
        GradientDrawable focus = shape(colors.selection, 24);
        focus.setStroke(dp(2), colors.accent);
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, focus);
        background.addState(new int[0], shape(Color.TRANSPARENT, 24));
        info.setBackground(new RippleDrawable(ColorStateList.valueOf((colors.accent & 0xFFFFFF) | 0x33000000),
                background, shape(Color.WHITE, 24)));
        info.setOnClickListener(view -> {
            PanelDialog panel = new PanelDialog(title, 530);
            panel.message(help);
            panel.action("Close", false, panel::dismiss);
            panel.show();
        });
        return info;
    }

    void showText(TextView view, String value) {
        view.setText(value);
        view.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    final class PanelDialog extends Dialog {
        final LinearLayout body;
        private final LinearLayout footer;
        private final LinearLayout panel;
        private final ScrollView scroll;
        private final int width;

        PanelDialog(String title, int maxWidth) {
            this(title, maxWidth, null);
        }

        PanelDialog(String title, int maxWidth, String help) {
            super(Screen.this);
            width = Math.min(maxWidth, getResources().getConfiguration().screenWidthDp - 32);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            panel = column();
            GradientDrawable frame = shape(colors.background, 24);
            frame.setStroke(dp(1), colors.outline);
            panel.setBackground(frame);
            panel.setPadding(dp(22), dp(18), dp(22), dp(14));
            View heading;
            if (help == null) {
                TextView label = text(title, 22, colors.ink, 650);
                label.setAccessibilityHeading(true);
                heading = label;
            } else {
                heading = helpHeading(title, 22, help);
            }
            heading.setPadding(dp(4), 0, dp(4), dp(12));
            panel.addView(heading, new LinearLayout.LayoutParams(-2, -2));
            scroll = new ScrollView(Screen.this);
            body = column();
            scroll.addView(body);
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            footer = row();
            footer.setPadding(0, dp(9), 0, 0);
            panel.addView(footer);
            setContentView(panel);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setDimAmount(0.55f);
        }

        void message(String value) {
            TextView info = text(value, 15, colors.muted, 450);
            info.setPadding(dp(4), 0, dp(4), dp(10));
            body.addView(info);
        }

        Button action(String label, boolean primary, Runnable handler) {
            Button action = button(label, primary, handler);
            if (!primary) styleButton(action, colors, false, true, false);
            tile(footer, action);
            return action;
        }

        void option(String label, Runnable handler) {
            Button action = button(label, false, handler);
            action.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            body.addView(action, params);
        }

        @Override public void show() {
            int maxHeight = dp(getResources().getConfiguration().screenHeightDp - 30);
            panel.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int overflow = panel.getMeasuredHeight() - maxHeight;
            if (overflow > 0) scroll.getLayoutParams().height = Math.max(dp(86), scroll.getMeasuredHeight() - overflow);
            super.show();
            if(!dialogs.contains(this))dialogs.add(this);
            getWindow().setLayout(dp(width), -2);
        }
        @Override public void dismiss(){dialogs.remove(this);super.dismiss();}
    }

    void trackDialog(Dialog dialog) {
        if (!dialogs.contains(dialog)) dialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
    }

    @Override protected void onDestroy(){
        for(Dialog dialog:new java.util.ArrayList<>(dialogs))dialog.dismiss();
        super.onDestroy();
    }

    void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this, "This action is unavailable on this unit.", Toast.LENGTH_LONG).show();
        }
    }

    void launch(String pkg) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) open(intent);
        else Toast.makeText(this, "That app is unavailable.", Toast.LENGTH_SHORT).show();
    }

}
