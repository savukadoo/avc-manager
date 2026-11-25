package local.cc3.manager;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local launcher drawer. Its contents are independent of the Home favourites. */
final class LauncherDrawer {
    static void show(Screen screen) {
        if (screen.isFinishing() || screen.isDestroyed()) return;
        new Drawer(screen).show();
    }

    private static final class App {
        final ComponentName component;
        final String label;
        final String searchable;

        App(ComponentName component, String label) {
            this.component = component;
            this.label = label;
            searchable = (label + " " + component.getPackageName()).toLowerCase(Locale.ROOT);
        }
    }

    private static String friendlyLabel(Screen screen, ResolveInfo info) {
        String name = info.activityInfo.packageName;
        if (screen.getPackageName().equals(name)) return "AVC Manager";
        if ("com.android.launcher4".equals(name)) return "Original TEYES Home";
        if ("com.android.launcher6".equals(name)) return "TEYES CC3N Home";
        if ("com.android.launcher8".equals(name)) return "TEYES CC3 Home";
        if ("com.syu.settings".equals(name)) return "TEYES settings";
        if ("com.android.settings".equals(name)) return "Android settings";
        for (DashboardApps.App app : DashboardApps.App.values()) {
            if (app.packageName.equals(name)) return app.label;
        }
        CharSequence value = info.loadLabel(screen.getPackageManager());
        String label = value == null ? "" : value.toString().trim();
        return label.isEmpty() ? name : label;
    }

    private static List<App> installed(Screen screen) {
        PackageManager packages = screen.getPackageManager();
        Map<ComponentName, App> apps = new LinkedHashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : packages.queryIntentActivities(launcher, 0)) {
            if (info.activityInfo == null || !info.activityInfo.exported) continue;
            ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            if (apps.containsKey(component)) continue;
            String label;
            try { label = friendlyLabel(screen, info); }
            catch (RuntimeException unavailable) { label = component.getPackageName(); }
            apps.put(component, new App(component, label));
        }
        List<App> result = new ArrayList<>(apps.values());
        Collator collator = Collator.getInstance(screen.getResources().getConfiguration().getLocales().get(0));
        result.sort(Comparator.comparing((App app) -> app.label, collator)
                .thenComparing(app -> app.component.flattenToString()));
        return result;
    }

    private static final class Drawer extends Dialog {
        private final Screen screen;
        private final LinearLayout panel;
        private final EditText search;
        private final TextView count;
        private final TextView empty;
        private final Button clear;
        private final GridView grid;
        private final AppsAdapter adapter;
        private final List<App> all = new ArrayList<>();
        private final List<App> filtered = new ArrayList<>();
        private final Rect visibleFrame = new Rect();
        private int currentWidth;
        private int currentHeight;
        private int columns;
        private boolean loadFailed;
        private final ViewTreeObserver.OnGlobalLayoutListener resize = this::resizeForViewport;

        // Android10 compatibility: this scoped platform path intentionally retains legacy APIs.
        @SuppressWarnings("deprecation")
        Drawer(Screen screen) {
            super(screen);
            this.screen = screen;
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            setCanceledOnTouchOutside(true);
            panel = screen.column();
            GradientDrawable background = screen.shape(screen.colors.background, 24);
            background.setStroke(screen.dp(1), screen.colors.outline);
            panel.setBackground(background);
            panel.setPadding(screen.dp(20), screen.dp(16), screen.dp(20), screen.dp(16));

            LinearLayout header = screen.row();
            TextView title = screen.text("All apps", 23, screen.colors.ink, 650);
            title.setAccessibilityHeading(true);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            count = screen.text("", 14, screen.colors.muted, 450);
            count.setPadding(screen.dp(12), 0, screen.dp(16), 0);
            count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            header.addView(count);
            Button close = screen.button("Close", false, this::dismiss);
            header.addView(close);
            panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout searchRow = screen.row();
            search = new EditText(screen);
            search.setTextSize(16);
            search.setTypeface(Typeface.create(Typeface.DEFAULT, 450, false));
            search.setTextColor(screen.colors.ink);
            search.setHintTextColor(screen.colors.muted);
            search.setHint("Search apps");
            search.setContentDescription("Search installed apps");
            search.setSingleLine(true);
            search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            search.setImeOptions(EditorInfo.IME_ACTION_DONE);
            search.setMinHeight(screen.dp(56));
            search.setPadding(screen.dp(16), screen.dp(10), screen.dp(16), screen.dp(10));
            search.setBackground(fieldBackground(screen));
            searchRow.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
            clear = screen.button("Clear", false, () -> search.setText(""));
            clear.setContentDescription("Clear app search");
            LinearLayout.LayoutParams clearBounds = new LinearLayout.LayoutParams(-2, -2);
            clearBounds.leftMargin = screen.dp(8);
            searchRow.addView(clear, clearBounds);
            LinearLayout.LayoutParams searchBounds = new LinearLayout.LayoutParams(-1, -2);
            searchBounds.topMargin = screen.dp(12);
            searchBounds.bottomMargin = screen.dp(12);
            panel.addView(searchRow, searchBounds);

            FrameLayout results = new FrameLayout(screen);
            grid = new GridView(screen);
            grid.setVerticalSpacing(screen.dp(12));
            grid.setHorizontalSpacing(screen.dp(12));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setNumColumns(4);
            grid.setClipToPadding(false);
            grid.setPadding(screen.dp(2), screen.dp(2), screen.dp(2), screen.dp(6));
            grid.setDrawSelectorOnTop(false);
            grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
            grid.setVerticalScrollBarEnabled(true);
            grid.setContentDescription("Installed apps");
            adapter = new AppsAdapter();
            grid.setAdapter(adapter);
            results.addView(grid, new FrameLayout.LayoutParams(-1, -1));
            empty = screen.text("No matching apps", 18, screen.colors.muted, 450);
            empty.setGravity(Gravity.CENTER);
            results.addView(empty, new FrameLayout.LayoutParams(-1, -1));
            grid.setEmptyView(empty);
            panel.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
            setContentView(panel);

            Window window = getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(.55f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            search.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                hideKeyboard();
                grid.requestFocus();
                return true;
            });
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) { filter(); }
                @Override public void afterTextChanged(Editable value) {}
            });
            setOnDismissListener(ignored -> {
                hideKeyboard();
                panel.getViewTreeObserver().removeOnGlobalLayoutListener(resize);
            });
            reload();
            panel.setFocusableInTouchMode(true);
            close.requestFocus();
        }

        private void reload() {
            all.clear();
            loadFailed = false;
            try { all.addAll(installed(screen)); }
            catch (RuntimeException unavailable) { loadFailed = true; }
            filter();
        }

        private void filter() {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            filtered.clear();
            for (App app : all) if (query.isEmpty() || app.searchable.contains(query)) filtered.add(app);
            count.setText(query.isEmpty() ? all.size() + " apps" : filtered.size() + " of " + all.size() + " apps");
            clear.setEnabled(!search.getText().toString().isEmpty());
            empty.setText(loadFailed ? "Apps could not be listed" : query.isEmpty() ? "No launchable apps found" : "No matching apps");
            adapter.notifyDataSetChanged();
            grid.setSelection(0);
        }

        @Override public void show() {
            super.show();
            panel.getViewTreeObserver().addOnGlobalLayoutListener(resize);
            resizeForViewport();
        }

        private void resizeForViewport() {
            Window window = getWindow();
            if (window == null || !isShowing()) return;
            window.getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
            if (visibleFrame.width() <= 0 || visibleFrame.height() <= 0) {
                screen.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
            }
            int width = Math.min(screen.dp(1120), Math.max(screen.dp(280), visibleFrame.width() - screen.dp(24)));
            int height = Math.min(screen.dp(620), Math.max(screen.dp(200), visibleFrame.height() - screen.dp(24)));
            if (width != currentWidth || height != currentHeight) {
                currentWidth = width;
                currentHeight = height;
                window.setLayout(width, height);
            }
            int available = width - panel.getPaddingLeft() - panel.getPaddingRight();
            int nextColumns = Math.max(1, Math.min(5, (available + screen.dp(12)) / screen.dp(222)));
            if (nextColumns != columns) {
                columns = nextColumns;
                grid.setNumColumns(columns);
            }
        }

        private void hideKeyboard() {
            InputMethodManager keyboard = screen.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }

        private void launch(App app) {
            try {
                // Resolve again so removal/disablement while the drawer is open is harmless.
                if (!screen.getPackageManager().getActivityInfo(app.component, 0).exported) {
                    throw new PackageManager.NameNotFoundException();
                }
                Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(app.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                screen.startActivity(intent);
                dismiss();
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                Toast.makeText(screen, "This app is no longer available.", Toast.LENGTH_LONG).show();
                reload();
            }
        }

        private final class AppsAdapter extends BaseAdapter {
            @Override public int getCount() { return filtered.size(); }
            @Override public App getItem(int position) { return filtered.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                AppTile tile = recycled instanceof AppTile ? (AppTile) recycled : new AppTile();
                tile.bind(getItem(position));
                return tile;
            }
        }

        private final class AppTile extends LinearLayout {
            private final ImageView icon;
            private final TextView label;
            private App app;

            AppTile() {
                super(screen);
                setOrientation(VERTICAL);
                setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                setPadding(screen.dp(12), screen.dp(12), screen.dp(12), screen.dp(12));
                setBackground(tileBackground(screen));
                setFocusable(true);
                setClickable(true);
                setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                    }
                });
                icon = new ImageView(screen);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(icon, new LinearLayout.LayoutParams(screen.dp(64), screen.dp(64)));
                label = screen.text("", 15, screen.colors.ink, 550);
                label.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                label.setMaxLines(2);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams textBounds = new LinearLayout.LayoutParams(-1, -2);
                textBounds.topMargin = screen.dp(10);
                addView(label, textBounds);
                int labelHeight = (int) Math.ceil(label.getPaint().getFontSpacing() * 2) + screen.dp(4);
                int height = Math.max(screen.dp(160), screen.dp(64 + 10 + 24) + labelHeight);
                setLayoutParams(new AbsListView.LayoutParams(-1, height));
                setOnClickListener(view -> { if (app != null) launch(app); });
            }

            void bind(App value) {
                app = value;
                label.setText(value.label);
                setContentDescription(value.label);
                setTooltipText(value.label);
                Drawable drawable;
                try { drawable = screen.getPackageManager().getActivityIcon(value.component); }
                catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                    drawable = screen.getPackageManager().getDefaultActivityIcon();
                }
                icon.setImageDrawable(drawable);
            }
        }
    }

    private static Drawable fieldBackground(Screen screen) {
        GradientDrawable normal = screen.shape(screen.colors.surface, 14);
        normal.setStroke(screen.dp(1), screen.colors.outline);
        GradientDrawable focus = screen.shape(screen.colors.surface, 14);
        focus.setStroke(screen.dp(2), screen.colors.accent);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focus);
        states.addState(new int[0], normal);
        return states;
    }

    private static Drawable tileBackground(Screen screen) {
        GradientDrawable normal = screen.shape(screen.colors.surface, 14);
        GradientDrawable focus = screen.shape(screen.colors.selection, 14);
        focus.setStroke(screen.dp(2), screen.colors.accent);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focus);
        states.addState(new int[0], normal);
        return new RippleDrawable(ColorStateList.valueOf((screen.colors.accent & 0xffffff) | 0x30000000), states, null);
    }

    private LauncherDrawer() {}
}
