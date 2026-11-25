package local.cc3.manager;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.View;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import local.cc3.obd.ObdPid;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DashboardApps {
    private static final String KEY = "dashboard_apps";

    private static final String ORDER = "dashboard_shortcut_order";

    enum Function {
        VEHICLE("vehicle", "Vehicle dashboard", "Vehicle"),
        ENTERTAINMENT("entertainment", "Entertainment", "Entertainment"),
        MUSIC("music", "Music", "Entertainment"),
        VIDEO("video", "Video", "Entertainment"),
        SOUND("sound", "Sound", "Audio"),
        WIRELESS("wireless", "Wireless", "Access"),
        FIREWALL("firewall", "Firewall", "Traffic"),
        APPEARANCE("appearance", "Appearance", "Appearance"),
        CONTROLS("controls", "Controls", "Controls");

        final String id, label, section;

        Function(String key, String label, String section) {
            this.id = "avc-function:" + key;
            this.label = label;
            this.section = section;
        }
    }

    enum App {
        CARLINK("CarPlay / Android Auto", "com.syu.carlink", R.drawable.ic_shortcut_projection),
        MAPS("Maps", "com.google.android.apps.maps"),
        WAZE("Waze", "com.waze"),
        FM("FM radio", "com.syu.radio", R.drawable.ic_shortcut_radio),
        BLUETOOTH("Bluetooth", "com.syu.bt", R.drawable.ic_shortcut_bluetooth),
        SPOTIFY("Spotify", "com.spotify.music"),
        USB("USB music", "com.syu.music", R.drawable.ic_shortcut_usb_music);

        final String label;
        final String packageName;

        final int glyph;

        App(String label, String packageName) {
            this(label, packageName, 0);
        }

        App(String label, String packageName, int glyph) {
            this.glyph = glyph;
            this.label = label;
            this.packageName = packageName;
        }
    }

    static final class Choice {
        final String label;
        final String id;
        private final Function function;
        final ObdPid metric;

        Choice(String label, String packageName) {
            this.label = label;
            this.id = packageName;
            this.function = null;
            this.metric = null;
        }

        Choice(Function function) {
            this.label = function.label;
            this.id = function.id;
            this.function = function;
            this.metric = null;
        }

        Choice(String profileId, ObdPid metric) {
            this.metric = metric;
            this.label = metric.label;
            this.id = "avc-metric:" + profileId + ":" + metric.id;
            this.function = Function.VEHICLE;
        }

        boolean isMedia(Context context) {
            if (function != null) return "Entertainment".equals(function.section);
            try {
                int category = context.getPackageManager().getApplicationInfo(id, 0).category;
                return category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO
                        || category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO
                        || "com.google.android.youtube".equals(id);
            } catch (PackageManager.NameNotFoundException removed) { return false; }
        }

        Intent intent(Context context) {
            if (function == null) return context.getPackageManager().getLaunchIntentForPackage(id);
            Intent intent = Screen.managerIntent(context, function.section);
            if (function == Function.VEHICLE) intent.putExtra("vehicle_dashboard", true);
            if (function == Function.MUSIC || function == Function.VIDEO)
                intent.putExtra("media_tab", function == Function.VIDEO ? "video" : "music");
            if (metric != null) intent.putExtra("home_metric", metric.id);
            return intent;
        }

        Drawable icon(Screen screen) throws PackageManager.NameNotFoundException {
            if (function == Function.MUSIC) return screen.consoleIcon(R.drawable.ic_shortcut_usb_music);
            if (function == Function.VIDEO) return screen.consoleIcon(R.drawable.ic_media_video);
            if (function != null) return screen.consoleIcon(NavigationStyle.icon(function.section));
            for (App app : App.values()) {
                if (app.glyph != 0 && app.packageName.equals(id)) return screen.consoleIcon(app.glyph);
            }
            return screen.getPackageManager().getApplicationIcon(id);
        }
    }

    private DashboardApps() {}

    static Set<String> defaults() {
        Set<String> defaults = new HashSet<>();
        for (App app : App.values()) defaults.add(app.packageName);
        return defaults;
    }

    static Set<String> selected(SharedPreferences preferences) {
        return new HashSet<>(preferences.getStringSet(KEY, defaults()));
    }

    static List<Choice> installed(PackageManager packages, String ownPackage) {
        Set<String> homes = new HashSet<>();
        for (ResolveInfo info : packages.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            homes.add(info.activityInfo.packageName);
        }
        Map<String, Choice> apps = new HashMap<>();
        for (ResolveInfo info : packages.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String name = info.activityInfo.packageName;
            if (info.activityInfo.exported && !name.equals(ownPackage) && !homes.contains(name)) {
                apps.putIfAbsent(name, new Choice(info.loadLabel(packages).toString(), name));
            }
        }
        for (App app : App.values()) {
            if (apps.containsKey(app.packageName) || packages.getLaunchIntentForPackage(app.packageName) != null) {
                apps.put(app.packageName, new Choice(app.label, app.packageName));
            }
        }
        List<Choice> result = new ArrayList<>(apps.values());
        Collator collator = Collator.getInstance();
        result.sort(Comparator.comparing((Choice item) -> item.label, collator)
                .thenComparing(item -> item.id));
        return result;
    }

    static List<Choice> functions() {
        List<Choice> choices = new ArrayList<>();
        for (Function function : Function.values()) choices.add(new Choice(function));
        return choices;
    }

    static List<Choice> metrics(SharedPreferences prefs) {
        List<Choice> result = new ArrayList<>();
        local.cc3.obd.ObdVehicleProfile profile = VehicleProfiles.current(prefs);
        for (ObdPid pid : profile.sensors) result.add(new Choice(profile.id, pid));
        return result;
    }

    static List<Choice> available(Screen screen) {
        List<Choice> choices = metrics(screen.prefs());
        choices.addAll(installed(screen.getPackageManager(), screen.getPackageName()));
        choices.addAll(functions());
        return choices;
    }

    static List<Choice> pinned(Screen screen) {
        Set<String> wanted = selected(screen.prefs());
        List<Choice> result = new ArrayList<>();
        for (App app : App.values()) {
            if (wanted.remove(app.packageName) && screen.getPackageManager().getLaunchIntentForPackage(app.packageName) != null)
                result.add(new Choice(app.label, app.packageName));
        }
        if (!wanted.isEmpty()) {
            for (Choice choice : available(screen)) if (wanted.contains(choice.id)) result.add(choice);
        }
        result.sort(Comparator.comparing((Choice c) -> c.metric == null));
        List<String> order = savedOrder(screen.prefs());
        if (!order.isEmpty()) {
            Map<String, Integer> rank = new HashMap<>();
            for (String id : order) rank.putIfAbsent(id, rank.size());
            result.sort(Comparator.comparingInt(c -> rank.getOrDefault(c.id, Integer.MAX_VALUE)));
        }
        return result;
    }

    private static List<String> savedOrder(SharedPreferences prefs) {
        String value = prefs.getString(ORDER, "");
        return value.isEmpty() ? java.util.Collections.emptyList() : java.util.Arrays.asList(value.split("\n"));
    }

    static boolean customOrder(SharedPreferences prefs) { return prefs.contains(ORDER); }

    static int shortcutColumns(int width, int count) {
        if (width >= 1050 && (count == 4 || count > 6)) return 4;
        return width >= 760 ? 3 : 2;
    }

    static void showPicker(Screen screen, Runnable onChanged) {
        showPicker(screen, onChanged, null);
    }

    static void showPicker(Screen screen, Runnable onChanged, String movingId) {
        SharedPreferences preferences = screen.prefs();
        PackageManager packages = screen.getPackageManager();
        List<Choice> apps = installed(packages, screen.getPackageName());
        List<Choice> functions = functions();
        List<Choice> choices = new ArrayList<>(apps);
        choices.addAll(functions);
        List<Choice> metrics = metrics(preferences);
        choices.addAll(metrics);
        Set<String> wanted = selected(preferences);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String id : savedOrder(preferences)) if (wanted.contains(id)) selected.add(id);
        for (Choice choice : pinned(screen)) selected.add(choice.id);
        selected.addAll(wanted);

        Configuration display = screen.getResources().getConfiguration();
        int columns = display.screenWidthDp >= 720 ? 2 : 1;

        Dialog dialog = new Dialog(screen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout panel = screen.column();
        GradientDrawable frame = screen.shape(screen.colors.background, 24);
        frame.setStroke(screen.dp(1), screen.colors.outline);
        panel.setBackground(frame);
        panel.setPadding(screen.dp(22), screen.dp(18), screen.dp(22), screen.dp(14));

        LinearLayout header = screen.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = screen.text("Home shortcuts", 24, screen.colors.ink, false);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, 650, false));
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button count = screen.button("", false, () -> {});
        Drawable filter = screen.getDrawable(R.drawable.ic_filter_shortcuts).mutate();
        filter.setTint(screen.colors.ink);
        filter.setBounds(0, 0, screen.dp(22), screen.dp(22));
        count.setCompoundDrawablesRelative(filter, null, null, null);
        count.setCompoundDrawablePadding(screen.dp(8));
        count.setSingleLine(true);
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(count);
        panel.addView(header);

        LinearLayout sources = screen.row();
        Button avc = screen.button("AVC Manager", false, () -> {});
        Button installed = screen.button("Installed apps", false, () -> {});
        Button readings = screen.button("Vehicle metrics", false, () -> {});
        Button arrange = screen.button("Arrange", false, () -> {});
        Button[] sourceButtons = {avc, installed, readings, arrange};
        for (Button item : sourceButtons) screen.tile(sources, item);
        LinearLayout.LayoutParams sourceBounds = new LinearLayout.LayoutParams(-1, -2);
        sourceBounds.topMargin = screen.dp(10);
        panel.addView(sources, sourceBounds);

        ScrollView scroll = new ScrollView(screen);
        LinearLayout grid = screen.column();
        grid.setPadding(0, screen.dp(6), 0, screen.dp(6));
        List<List<Choice>> groups = java.util.Arrays.asList(functions, apps, metrics);
        ArrangeGrid positions = new ArrangeGrid(screen, grid, selected, choices, movingId);
        class PickerTabs {
            int source = movingId == null ? 0 : sourceButtons.length - 1;
            boolean onlySelected;

            void updateCount() {
                int size = positions.orderedChoices().size();
                String label = size + " selected";
                count.setText(label);
                count.setSelected(onlySelected);
                String action = onlySelected ? "Return to " + sourceButtons[source].getText() : "Show selected shortcuts";
                count.setContentDescription(label + ". " + action);
                count.setTooltipText(action);
                screen.styleButton(count, screen.colors, false, true, onlySelected);
            }

            void render() {
                updateCount();
                for (int i = 0; i < sourceButtons.length; i++)
                    selectSource(screen, sourceButtons[i], !onlySelected && i == source);
                if (!onlySelected && sourceButtons[source] == arrange) {
                    positions.render();
                    return;
                }
                List<Choice> visible = onlySelected ? positions.orderedChoices() : groups.get(source);
                populate(screen, grid, visible, positions, columns, onlySelected, this::selectionChanged);
            }

            void selectionChanged() {
                if (onlySelected) render();
                else updateCount();
            }
        }
        PickerTabs tabs = new PickerTabs();
        for (int i = 0; i < sourceButtons.length; i++) {
            final int index = i;
            sourceButtons[i].setOnClickListener(view -> {
                tabs.source = index;
                tabs.onlySelected = false;
                tabs.render();
                scroll.scrollTo(0, 0);
            });
        }
        count.setOnClickListener(view -> {
            tabs.onlySelected = !tabs.onlySelected;
            tabs.render();
            scroll.scrollTo(0, 0);
        });
        tabs.render();
        scroll.addView(grid);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = screen.row();
        Button reset = screen.button("Reset defaults", false, () -> {
            preferences.edit().remove(KEY).remove(ORDER).apply();
            dialog.dismiss();
            onChanged.run();
        });
        Button cancel = screen.button("Cancel", false, dialog::dismiss);
        Button save = screen.button("Save", true, () -> {
            preferences.edit().putStringSet(KEY, new HashSet<>(selected))
                    .putString(ORDER, android.text.TextUtils.join("\n", selected)).apply();
            dialog.dismiss();
            onChanged.run();
        });
        screen.styleButton(reset, screen.colors, false, true, false);
        screen.styleButton(cancel, screen.colors, false, true, false);
        for (Button action : new Button[] {reset, cancel, save}) {
            action.setMinHeight(screen.dp(64));
            screen.tile(footer, action);
        }
        panel.addView(footer);

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(0.55f);
        screen.trackDialog(dialog);
        dialog.show();
        window.setLayout(screen.dp(Math.min(display.screenWidthDp - 32, 936)),
                screen.dp(Math.min(display.screenHeightDp - 28, 580)));
    }

    private static final class ArrangeGrid {
        final Screen screen;
        final LinearLayout grid;
        final LinkedHashSet<String> selected;
        final Map<String, Choice> choices = new HashMap<>();
        String moving;
        boolean manuallyOrdered;

        ArrangeGrid(Screen screen, LinearLayout grid, LinkedHashSet<String> selected, List<Choice> all, String moving) {
            this.screen = screen;
            this.grid = grid;
            this.selected = selected;
            for (Choice choice : all) choices.put(choice.id, choice);
            this.moving = moving;
            this.manuallyOrdered = customOrder(screen.prefs());
        }

        void select(Choice choice) {
            if (manuallyOrdered || choice.metric == null) {
                selected.add(choice.id);
                return;
            }
            // Older versions put newly pinned readings ahead of apps on a full Home.
            List<String> order = new ArrayList<>(selected);
            int index = 0;
            while (index < order.size() && order.get(index).startsWith("avc-metric:")) index++;
            order.add(index, choice.id);
            selected.clear();
            selected.addAll(order);
        }

        List<Choice> orderedChoices() {
            List<Choice> ordered = new ArrayList<>();
            for (String id : selected) if (choices.containsKey(id)) ordered.add(choices.get(id));
            return ordered;
        }

        void render() {
            grid.removeAllViews();
            List<Choice> ordered = orderedChoices();
            if (!selected.contains(moving)) moving = null;
            String hint = ordered.isEmpty() ? "Choose shortcuts from the other tabs first."
                    : moving == null ? "Tap a shortcut to move it."
                    : "Move " + choices.get(moving).label + " to which position?";
            TextView prompt = screen.text(hint, 16, screen.colors.ink, 550);
            prompt.setPadding(screen.dp(6), screen.dp(6), screen.dp(6), screen.dp(10));
            prompt.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            grid.addView(prompt);
            int width = screen.getResources().getConfiguration().screenWidthDp;
            DashboardLayout layout = screen instanceof DashboardActivity
                    ? ((DashboardActivity) screen).previewLayout() : null;
            if (layout == null) layout = DashboardLayout.selected(screen.prefs());
            int columns = width >= 720 ? 2 : 1;
            if (layout == DashboardLayout.CLOCK_FAVORITES) {
                if (screen.currentFavouriteTiles() == 6 && width >= 900) columns = 3;
                if (ordered.size() > screen.currentFavouriteTiles())
                    prompt.append("  The first " + screen.currentFavouriteTiles() + " appear on Home.");
            } else if (layout == DashboardLayout.BIG_SHORTCUTS) {
                columns = shortcutColumns(width, Math.min(8, ordered.size()));
                if (ordered.size() > 8) prompt.append("  The first 8 appear on Home.");
            } else if (!ordered.isEmpty()) {
                prompt.append("  Each panel shows its own shortcuts in this order.");
            }
            LinearLayout row = null;
            for (int i = 0; i < ordered.size(); i++) {
                if (i % columns == 0) { row = screen.row(); grid.addView(row); }
                Choice choice = ordered.get(i);
                boolean picked = choice.id.equals(moving);
                String label = (i + 1) + "   " + choice.label;
                Button position = screen.button(label, false, () -> choose(choice.id));
                screen.styleButton(position, screen.colors, false, false, picked);
                position.setSelected(picked);
                position.setContentDescription("Position " + (i + 1) + ", " + choice.label);
                position.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                position.setMinHeight(screen.dp(68));
                screen.tile(row, position);
                if (i == ordered.size() - 1) {
                    for (int j = i % columns + 1; j < columns; j++)
                        row.addView(new View(screen), new LinearLayout.LayoutParams(0, 1, 1));
                }
            }
        }

        void choose(String id) {
            if (moving == null) moving = id;
            else {
                List<String> order = new ArrayList<>(selected);
                int destination = order.indexOf(id);
                if (!moving.equals(id)) manuallyOrdered = true;
                order.remove(moving);
                order.add(destination, moving);
                selected.clear();
                selected.addAll(order);
                moving = null;
            }
            render();
        }
    }

    private static void selectSource(Screen screen, Button button, boolean selected) {
        button.setSelected(selected);
        screen.styleButton(button, screen.colors, false, false, selected);
    }

    private static void populate(Screen screen, LinearLayout grid, List<Choice> visible,
            ArrangeGrid positions, int columns, boolean onlySelected, Runnable onSelectionChanged) {
        Set<String> selected = positions.selected;
        grid.removeAllViews();
        for (int start = 0; start < visible.size(); start += columns) {
            LinearLayout row = screen.row();
            grid.addView(row);
            for (int column = 0; column < columns; column++) {
                int index = start + column;
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, screen.dp(76), 1);
                cell.setMargins(screen.dp(4), screen.dp(4), screen.dp(4), screen.dp(4));
                if (index >= visible.size()) { row.addView(new View(screen), cell); continue; }
                Choice choice = visible.get(index);
                CheckedTextView tile = appTile(screen, choice, selected.contains(choice.id));
                tile.setOnClickListener(view -> {
                    if (choice.metric != null && !tile.isChecked()) {
                        int pinned = 0;
                        for (Choice existing : positions.orderedChoices()) if (existing.metric != null) pinned++;
                        if (pinned >= 4) {
                            android.widget.Toast.makeText(screen, "Choose up to four Home metrics", android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    tile.toggle();
                    if (tile.isChecked()) positions.select(choice);
                    else selected.remove(choice.id);
                    onSelectionChanged.run();
                });
                row.addView(tile, cell);
            }
        }
        if (visible.isEmpty()) {
            String hint = onlySelected ? "No shortcuts selected. Choose a tab to add one." : "No shortcuts available.";
            TextView empty = screen.text(hint, 17, screen.colors.muted, false);
            empty.setPadding(screen.dp(4), screen.dp(16), screen.dp(4), screen.dp(16));
            grid.addView(empty);
        }
    }

    private static CheckedTextView appTile(Screen screen, Choice choice, boolean selected) {
        CheckedTextView tile = new CheckedTextView(screen);
        tile.setText(choice.label);
        tile.setTextSize(17);
        tile.setTextColor(screen.colors.ink);
        tile.setTypeface(Typeface.create(Typeface.DEFAULT, 550, false));
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setMinHeight(screen.dp(76));
        tile.setPaddingRelative(screen.dp(16), screen.dp(9), screen.dp(16), screen.dp(9));
        tile.setFocusable(true);

        TypedValue indicator = new TypedValue();
        if (screen.getTheme().resolveAttribute(android.R.attr.listChoiceIndicatorMultiple, indicator, true)) {
            tile.setCheckMarkDrawable(indicator.resourceId);
        }
        int[] checked = {android.R.attr.state_checked};
        int[][] states = {checked, {}};
        tile.setCheckMarkTintList(new ColorStateList(states, new int[] {screen.colors.accent, screen.colors.muted}));

        GradientDrawable outline = screen.shape(screen.colors.surface, 14);
        outline.setStroke(screen.dp(2), screen.colors.accent);
        StateListDrawable tileBackground = new StateListDrawable();
        tileBackground.addState(checked, outline);
        tileBackground.addState(new int[0], screen.shape(screen.colors.surface, 14));
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55888888), tileBackground, null));
        tile.setChecked(selected);
        try {
            Drawable icon = choice.icon(screen);
            icon.setBounds(0, 0, screen.dp(34), screen.dp(34));
            tile.setCompoundDrawablesRelative(icon, null, null, null);
            tile.setCompoundDrawablePadding(screen.dp(12));
        } catch (PackageManager.NameNotFoundException ignored) {
            // A package can disappear while the list is being prepared.
        }
        return tile;
    }
}
