package local.cc3.manager.mediaqa;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Emulator-only observation runner, packaged separately from AVC Manager.
 * Plays no media and injects no file/state fixtures. Host QA uploads synthetic files
 * and exercises the real native player using adb taps while this runner observes.
 * Optional debugFixture injects presentation-only status on the emulator; it never arms a lease.
 */
public final class EntertainmentUiHarness extends Instrumentation {
    private static final String TARGET = "local.cc3.manager";
    private static final String DUMP = TARGET + ".mediaqa.DUMP", FINISH = TARGET + ".mediaqa.FINISH";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Bundle arguments;
    private Activity mainActivity, resumedActivity;
    private ActivityMonitor transferMonitor;
    private boolean registered, finished;
    private volatile boolean stopRequested;
    private int dumpEveryMs = 500, sequence;
    private String lastError = "";
    private Field debugFixtureField;
    private Object debugFixtureOriginal;
    private WebView observedMap;
    private JSONObject mapDocument;

    @Override public void onCreate(Bundle supplied) {
        arguments = supplied == null ? new Bundle() : new Bundle(supplied);
        super.onCreate(supplied); start();
    }

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (TARGET.equals(activity.getPackageName())) resumedActivity = activity;
    }

    @Override public void onStart() {
        Bundle result = new Bundle(); int outcome = Activity.RESULT_OK;
        try {
            requireEmulator();
            int seconds = integer("durationSec", 600, 10, 900);
            dumpEveryMs = integer("dumpEveryMs", 500, 100, 10000);
            String debugFixture = arguments.getString("debugFixture", "none");
            if (!"none".equals(debugFixture)) {
                if (!java.util.Arrays.asList("on", "busy", "expired").contains(debugFixture))
                    throw new IllegalArgumentException("Unknown debugging presentation fixture");
                ClassLoader loader = getTargetContext().getClassLoader();
                Class<?> service = loader.loadClass(TARGET + ".WirelessDebugService");
                Class<?> state = loader.loadClass(TARGET + ".WirelessDebugService$State");
                java.lang.reflect.Constructor<?> constructor = state.getDeclaredConstructor(boolean.class, boolean.class, String.class, long.class);
                constructor.setAccessible(true);
                debugFixtureField = service.getDeclaredField("state"); debugFixtureField.setAccessible(true);
                debugFixtureOriginal = debugFixtureField.get(null);
                boolean on = "on".equals(debugFixture), busy = "busy".equals(debugFixture);
                debugFixtureField.set(null, constructor.newInstance(on, busy,
                        on ? "Wi-Fi debugging is on" : busy ? "Turning off Wi-Fi debugging…" : "Debugging timer ended",
                        on ? 732000L : 0L));
            }
            transferMonitor = addMonitor(TARGET + ".MediaTransferActivity", null, false);
            mainActivity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("screen", arguments.getString("screen","Entertainment")));
            runOnMain(() -> {
                IntentFilter filter = new IntentFilter(DUMP); filter.addAction(FINISH);
                if (Build.VERSION.SDK_INT >= 33) mainActivity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                else mainActivity.registerReceiver(receiver, filter);
                registered = true; dump();
            });
            main.post(periodic);
            status("Entertainment QA observing real native UI and player state; durationSec=" + seconds
                    + ". No media or playback state has been fabricated. Dump: files/mediaqa-ui.json");
            long until = System.nanoTime() + seconds * 1000000000L;
            while (!stopRequested && System.nanoTime() < until) Thread.sleep(250);
            result.putString("stream", "Entertainment UI QA complete.\n");
            result.putInt("dumps", sequence); result.putBoolean("finish_requested", stopRequested);
        } catch (Throwable failure) {
            outcome = Activity.RESULT_CANCELED;
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            result.putString("stream", "Entertainment UI QA failed: " + cause + "\n");
            result.putString("error", cause.toString());
        } finally {
            finished = true; main.removeCallbacks(periodic);
            try {
                runOnMain(() -> {
                    if (debugFixtureField != null) {
                        try { debugFixtureField.set(null, debugFixtureOriginal); }
                        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
                    }
                    if (registered) { mainActivity.unregisterReceiver(receiver); registered = false; }
                    if(mainActivity!=null && "true".equalsIgnoreCase(arguments.getString("cleanupTlsIdentity","false"))){
                        try{java.security.KeyStore keys=java.security.KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("cc3-media-https-v1");keys.deleteEntry("cc3-media-https-v2");
                            if(keys.containsAlias("cc3-media-https-v1") || keys.containsAlias("cc3-media-https-v2"))throw new IllegalStateException("TLS fixture key still present");
                            result.putBoolean("tls_fixture_keys_removed",true);}
                        catch(Exception error){throw new RuntimeException("TLS fixture cleanup failed",error);}
                    }
                    if (mainActivity != null && !"false".equalsIgnoreCase(arguments.getString("stopPlaybackOnFinish", "true"))) {
                        mainActivity.stopService(new Intent().setComponent(new ComponentName(TARGET, TARGET + ".EntertainmentPlaybackService")));
                        mainActivity.stopService(new Intent().setComponent(new ComponentName(TARGET, TARGET + ".MediaTransferService")));
                    }
                });
            } catch (Exception cleanupFailure) {
                outcome = Activity.RESULT_CANCELED;
                result.putString("cleanup_error", cleanupFailure.toString());
            }
            if (transferMonitor != null) removeMonitor(transferMonitor);
            finish(outcome, result);
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (finished) return;
            if (DUMP.equals(intent.getAction())) dump(); else if (FINISH.equals(intent.getAction())) stopRequested = true;
        }
    };
    private final Runnable periodic = new Runnable() {
        @Override public void run() { if (!finished) { dump(); main.postDelayed(this, dumpEveryMs); } }
    };

    private void dump() {
        if (mainActivity == null) return;
        try {
            Activity activity = resumedActivity;
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Activity monitored = transferMonitor == null ? null : transferMonitor.getLastActivity();
                activity = monitored != null && !monitored.isFinishing() && !monitored.isDestroyed() ? monitored : mainActivity;
            }
            JSONObject output = new JSONObject();
            output.put("emulator_qa", true); output.put("fabricated_media", false);
            output.put("debug_presentation_fixture", arguments.getString("debugFixture", "none"));
            output.put("captured_at_ms", System.currentTimeMillis()); output.put("sequence", ++sequence);
            output.put("activity", activity.getClass().getName());
            if(activity.getClass().getName().endsWith("DashboardActivity") || activity.getClass().getName().endsWith("DashboardPreviewActivity")){
                Field widgetField=activity.getClass().getSuperclass().getName().endsWith("DashboardActivity")
                        ? activity.getClass().getSuperclass().getDeclaredField("weatherWidget")
                        : activity.getClass().getDeclaredField("weatherWidget");
                widgetField.setAccessible(true);Object widget=widgetField.get(activity);
                if(widget!=null){
                    Field repositoryField=widget.getClass().getDeclaredField("repository");repositoryField.setAccessible(true);
                    Object repository=repositoryField.get(widget);JSONObject weather=new JSONObject();
                    for(String name:new String[]{"active","closed","generation","needsNetworkRefresh","failedUpdates"}){
                        Field field=repository.getClass().getDeclaredField(name);field.setAccessible(true);weather.put(name,field.get(repository));
                    }
                    Field stateField=repository.getClass().getDeclaredField("state");stateField.setAccessible(true);Object weatherState=stateField.get(repository);
                    for(String name:new String[]{"status","loading"}){Field field=weatherState.getClass().getDeclaredField(name);field.setAccessible(true);weather.put(name,field.get(weatherState));}
                    output.put("weather",weather);
                }
            }
            output.put("font_scale", activity.getResources().getConfiguration().fontScale);
            output.put("width_dp", activity.getResources().getConfiguration().screenWidthDp);
            output.put("height_dp", activity.getResources().getConfiguration().screenHeightDp);
            output.put("density_dpi", activity.getResources().getDisplayMetrics().densityDpi);
            output.put("playback", serviceState("EntertainmentPlaybackService"));
            JSONObject transfer=serviceState("MediaTransferService");
            Class<?> transferType=mainActivity.getClassLoader().loadClass(TARGET+".MediaTransferService");
            java.lang.reflect.Method fingerprint=transferType.getDeclaredMethod("fingerprint");fingerprint.setAccessible(true);
            transfer.put("certificate_sha256",fingerprint.invoke(null));
            output.put("transfer",transfer);
            output.put("debug_lease",serviceState("WirelessDebugService"));
            JSONObject firewall=new JSONObject();
            Class<?> firewallType=mainActivity.getClassLoader().loadClass(TARGET+".FirewallService");
            for(String method:new String[]{"modes","status","alwaysOn","lockdown"}){
                java.lang.reflect.Method accessor=firewallType.getDeclaredMethod(method);accessor.setAccessible(true);firewall.put(method,accessor.invoke(null));
            }
            android.content.SharedPreferences preferences=mainActivity.getSharedPreferences("cc3_manager",Context.MODE_PRIVATE);
            for(String key:new String[]{"firewall_persistent","firewall_wanted","firewall_block_unknown"})firewall.put(key,preferences.getBoolean(key,false));
            output.put("firewall",firewall);
            java.security.KeyStore keys=java.security.KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            output.put("tls_key_present",keys.containsAlias("cc3-media-https-v2"));
            JSONArray windows = new JSONArray();
            windows.put(window(activity.getWindow().getDecorView(), "activity", activity.getClass().getName()));
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            findDialogs(activity, windows, visited, 0);
            if (activity != mainActivity) findDialogs(mainActivity, windows, visited, 0);
            output.put("windows", windows);
            // Convenience field compatible with existing host-side hierarchy check scripts.
            JSONObject foreground = windows.getJSONObject(windows.length() - 1);
            output.put("window", foreground.getString("kind")); output.put("window_class", foreground.getString("class"));
            output.put("nodes", foreground.getJSONArray("nodes"));
            if (activity.getClass().getName().endsWith("WeatherMapActivity")) {
                Field mapField = activity.getClass().getDeclaredField("map"); mapField.setAccessible(true);
                WebView web = (WebView) mapField.get(activity);
                JSONObject info = new JSONObject(); info.put("present", web != null);
                if (web != null) {
                    if (observedMap != web) { observedMap = web; mapDocument = null; }
                    info.put("url", web.getUrl());
                    info.put("javascript_enabled", web.getSettings().getJavaScriptEnabled());
                    info.put("file_access", web.getSettings().getAllowFileAccess());
                    info.put("content_access", web.getSettings().getAllowContentAccess());
                    info.put("mixed_content_mode", web.getSettings().getMixedContentMode());
                    info.put("provider", WebView.getCurrentWebViewPackage() == null ? "unavailable" : WebView.getCurrentWebViewPackage().versionName);
                    if (mapDocument != null) info.put("document", mapDocument);
                    web.evaluateJavascript("(function(){return JSON.stringify({title:document.title,ready:document.readyState,"
                            + "text:document.body?document.body.innerText.slice(0,3500):'',canvases:document.querySelectorAll('canvas').length,"
                            + "controls:Array.from(document.querySelectorAll('#embed-zoom .zoom-ctrl,.play-pause')).map(function(e){"
                            + "var r=e.getBoundingClientRect(),b=e.closest('#plugin-radar,#plugin-satellite,#plugin-progress-bar');"
                            + "return {role:e.classList.contains('play-pause')?'play':e.outerHTML.slice(0,180),"
                            + "state:e.className,layer:b?b.id:'',x:r.x,y:r.y,w:r.width,h:r.height};}).filter(function(e){return e.w&&e.h}),"
                            + "map_class:document.body?document.body.className:'',"
                            + "loaded_images:Array.from(document.images).filter(function(i){return i.complete&&i.naturalWidth>0}).length});})()", value -> {
                        try { if (observedMap == web) mapDocument = new JSONObject(new JSONArray("[" + value + "]").getString(0)); }
                        catch (Exception ignored) { /* A navigating document may not be ready for observation. */ }
                    });
                } else { observedMap = null; mapDocument = null; }
                output.put("weather_map", info);
            }
            File target = new File(mainActivity.getFilesDir(), "mediaqa-ui.json");
            File temporary = new File(mainActivity.getFilesDir(), ".mediaqa-ui.json.tmp");
            try (FileOutputStream stream = new FileOutputStream(temporary)) { stream.write(output.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!temporary.renameTo(target)) throw new IOException("Could not replace QA hierarchy dump");
            lastError = "";
        } catch (Exception failure) {
            String message = failure.toString(); if (!message.equals(lastError)) { status("Entertainment dump failed: " + message); lastError = message; }
        }
    }

    private JSONObject serviceState(String simpleName) throws Exception {
        Class<?> type = mainActivity.getClassLoader().loadClass(TARGET + "." + simpleName);
        java.lang.reflect.Method stateMethod=type.getDeclaredMethod("state");stateMethod.setAccessible(true);
        Object state = stateMethod.invoke(null);
        JSONObject result = new JSONObject();
        if (state == null) return result;
        for (Field field : state.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(state);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) result.put(field.getName(), value);
        }
        return result;
    }

    /** Application fields only. Never accesses hidden system/window internals. */
    private void findDialogs(Object owner, JSONArray windows, Set<Object> visited, int depth) throws Exception {
        if (owner == null || depth > 3 || !visited.add(owner)) return;
        if (owner instanceof Dialog) {
            Dialog dialog = (Dialog) owner;
            if (dialog.isShowing() && dialog.getWindow() != null)
                windows.put(window(dialog.getWindow().getDecorView(), "dialog", dialog.getClass().getName()));
            return;
        }
        if (owner instanceof List<?>) { for (Object entry : (List<?>)owner) findDialogs(entry, windows, visited, depth + 1); return; }
        Class<?> type = owner.getClass();
        while (type != null && type.getName().startsWith(TARGET + ".")) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                String name = field.getName();
                if (!(name.endsWith("Page") || name.endsWith("Widget") || name.toLowerCase(Locale.ROOT).contains("dialog") || name.equals("dialogs"))) continue;
                field.setAccessible(true); findDialogs(field.get(owner), windows, visited, depth + 1);
            }
            type = type.getSuperclass();
        }
    }

    private JSONObject window(View decor, String kind, String type) throws Exception {
        JSONObject window = new JSONObject(); window.put("kind", kind); window.put("class", type);
        JSONArray nodes = new JSONArray(); dumpView(decor, -1, nodes); window.put("nodes", nodes); return window;
    }

    private void dumpView(View view, int parent, JSONArray nodes) throws Exception {
        if (!view.isShown() || view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        Rect visible = new Rect(); if (!view.getLocalVisibleRect(visible) || visible.isEmpty()) return;
        int[] location = new int[2]; view.getLocationOnScreen(location);
        visible.offset(location[0] - view.getScrollX(), location[1] - view.getScrollY());
        JSONObject node = new JSONObject(); int index = nodes.length();
        node.put("index", index); node.put("parent", parent); node.put("class", view.getClass().getName());
        node.put("text", view instanceof TextView ? ((TextView)view).getText().toString() : "");
        node.put("contentDesc", view.getContentDescription() == null ? "" : view.getContentDescription().toString());
        node.put("tooltip", view.getTooltipText() == null ? "" : view.getTooltipText().toString());
        node.put("bounds", new JSONArray(new int[]{location[0],location[1],location[0]+view.getWidth(),location[1]+view.getHeight()}));
        node.put("visibleBounds", new JSONArray(new int[]{visible.left,visible.top,visible.right,visible.bottom}));
        node.put("enabled", view.isEnabled()); node.put("selected", view.isSelected()); node.put("activated", view.isActivated());
        node.put("clickable", view.isClickable()); node.put("focusable", view.isFocusable()); node.put("focused", view.isFocused());
        node.put("scrollable", view.canScrollVertically(1) || view.canScrollVertically(-1) || view.canScrollHorizontally(1) || view.canScrollHorizontally(-1));
        if (view instanceof SeekBar) {
            node.put("progress", ((SeekBar)view).getProgress()); node.put("max", ((SeekBar)view).getMax());
        }
        if (view instanceof TextView) {
            TextView text = (TextView)view; node.put("text_size_px", text.getTextSize());
            node.put("text_color", text.getCurrentTextColor()); node.put("max_lines", text.getMaxLines());
            if (text.getLayout() != null) {
                JSONArray ellipsis = new JSONArray();
                for (int n = 0; n < text.getLayout().getLineCount(); n++) ellipsis.put(text.getLayout().getEllipsisCount(n));
                node.put("ellipsis_counts", ellipsis); node.put("text_layout_height", text.getLayout().getHeight());
                node.put("text_area_height", text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
            }
        }
        nodes.put(node);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view; for (int n = 0; n < group.getChildCount(); n++) dumpView(group.getChildAt(n), index, nodes);
        }
    }

    private interface CheckedAction { void run() throws Exception; }
    private void runOnMain(CheckedAction action) throws Exception {
        Throwable[] failure = new Throwable[1]; runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new Exception(failure[0]);
    }
    private int integer(String key, int fallback, int min, int max) {
        String value = arguments.getString(key); if (value == null || value.isEmpty()) return fallback;
        int parsed = Integer.parseInt(value); if (parsed < min || parsed > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max); return parsed;
    }
    private void status(String message) { Bundle output = new Bundle(); output.putString("stream", message + "\n"); sendStatus(0, output); }
    private static void requireEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT), fingerprint = Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        if (!hardware.contains("ranchu") && !hardware.contains("goldfish") && !fingerprint.startsWith("generic"))
            throw new IllegalStateException("Entertainment UI QA refuses physical devices, including the CC3.");
    }
}
