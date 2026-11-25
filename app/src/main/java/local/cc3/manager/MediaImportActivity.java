package local.cc3.manager;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.MediaStore;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owner of the local picker on head units that ship without DocumentsUI. */
public final class MediaImportActivity extends Screen {
    private static final int READ_MEDIA = 1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<Uri> selected = new LinkedHashSet<>();
    private final List<Entry> entries = new ArrayList<>();
    private LocalMediaLibrary library;
    private TextView location, empty;
    private Button copy, cancel, permission;
    private ListView list;
    private Rows rows;
    private File root, directory;
    private String volumeName;
    private String mediaVolume;
    private boolean indexed;
    private int generation;

    private static final class Entry {
        final String label;
        final File folder;
        final Uri media;
        final String volume;
        Entry(String label, File folder, Uri media) { this(label, folder, media, null); }
        Entry(String label, File folder, Uri media, String volume) {
            this.label = label; this.folder = folder; this.media = media; this.volume = volume;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prepareWindow();
        library = new LocalMediaLibrary(this);
        LinearLayout page = column();
        page.setBackgroundColor(colors.background);
        page.setPadding(dp(20), dp(12), dp(20), dp(12));
        LinearLayout header = row();
        header.addView(helpHeading("Import media", 22, EntertainmentPage.USB_HELP), new LinearLayout.LayoutParams(0, -2, 1));
        cancel = button("Cancel", false, this::finish);
        header.addView(cancel);
        copy = button("Import", true, this::commit);
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(dp(190), dp(60));
        action.leftMargin = dp(10);
        header.addView(copy, action);
        page.addView(header);
        LinearLayout path = row();
        Button back = button("Back", false, this::onBackPressed);
        path.addView(back);
        location = text("", 16, colors.muted, 550);
        location.setPadding(dp(12), 0, 0, 0);
        location.setSingleLine(true);
        location.setEllipsize(android.text.TextUtils.TruncateAt.START);
        path.addView(location, new LinearLayout.LayoutParams(0, dp(55), 1));
        location.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pathBounds = new LinearLayout.LayoutParams(-1, -2);
        pathBounds.topMargin = dp(8);
        pathBounds.bottomMargin = dp(16);
        page.addView(path, pathBounds);
        empty = text("", 16, colors.muted, 500);
        page.addView(empty);
        permission = button("Allow media access", true, this::requestMedia);
        page.addView(permission);
        list = new ListView(this);
        rows = new Rows();
        list.setAdapter(rows);
        list.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        list.setDividerHeight(dp(8));
        list.setOnItemClickListener((parent, view, position, id) -> choose(entries.get(position)));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        if (state != null) {
            ArrayList<String> saved = state.getStringArrayList("selected");
            if (saved != null) for (String uri : saved) selected.add(Uri.parse(uri));
        }
        refresh();
        if (state == null && !library.canReadDeviceMedia()) requestMedia();
    }

    private void requestMedia() {
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, READ_MEDIA);
    }

    @Override public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        if (request == READ_MEDIA) refresh();
    }

    private void choose(Entry entry) {
        if (entry.media != null) {
            if (!selected.remove(entry.media)) {
                if (selected.size() >= 100) {
                    Toast.makeText(this, "Choose up to 100 files", Toast.LENGTH_SHORT).show();
                    return;
                }
                selected.add(entry.media);
            }
            count();
            rows.notifyDataSetChanged();
            return;
        }
        if (entry.folder == null) indexed = true;
        else {
            if (root == null) { root = entry.folder; volumeName = entry.label; mediaVolume = entry.volume; }
            directory = entry.folder;
        }
        refresh();
    }

    private void up() {
        if (directory != null && !directory.equals(root)) directory = directory.getParentFile();
        else { directory = null; root = null; indexed = false; }
        refresh();
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (directory != null || indexed) up(); else super.onBackPressed();
    }

    private void count() {
        copy.setText(selected.isEmpty() ? "Import" : "Import (" + selected.size() + ")");
        copy.setEnabled(!selected.isEmpty());
    }

    private void refresh() {
        final int request = ++generation;
        File folder = directory;
        File sourceRoot = root;
        String volumeId = mediaVolume;
        boolean fromIndex = indexed;
        cancel.setVisibility(folder == null && !fromIndex ? View.GONE : View.VISIBLE);
        count();
        location.setText(folder == null ? (fromIndex ? "Indexed music and video" : "Choose a storage location")
                : volumeName + folder.getPath().substring(root.getPath().length()));
        entries.clear();
        rows.notifyDataSetChanged();
        boolean allowed = library.canReadDeviceMedia();
        permission.setVisibility(allowed ? View.GONE : View.VISIBLE);
        empty.setVisibility(View.VISIBLE);
        empty.setText(allowed ? "Loading…" : "Allow media access to browse files on this unit and connected drives.");
        if (!allowed) return;
        worker.execute(() -> {
            List<Entry> result = new ArrayList<>();
            String message = "No supported music or video files here.";
            try {
                if (fromIndex) {
                    for (LocalMediaLibrary.Item item : library.scan())
                        if (item.file == null) result.add(new Entry(item.name, null, item.uri));
                } else if (folder == null) {
                    for (StorageVolume volume : getSystemService(StorageManager.class).getStorageVolumes()) {
                        if (!Environment.MEDIA_MOUNTED.equals(volume.getState())
                                && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState())) continue;
                        File path = volumePath(volume);
                        if (path != null) {
                            String id = volume.isPrimary() ? MediaStore.VOLUME_EXTERNAL_PRIMARY : volume.getUuid();
                            result.add(new Entry(volume.getDescription(this), path.getAbsoluteFile(), null,
                                    id == null ? null : id.toLowerCase(Locale.ROOT)));
                        }
                    }
                    // Some vendor builds expose a volume to MediaStore before the storage service.
                    Set<String> listed = new LinkedHashSet<>();
                    for (Entry source : result) listed.add(source.volume);
                    for (String id : MediaStore.getExternalVolumeNames(this)) {
                        if (!listed.add(id)) continue;
                        boolean primary = MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(id);
                        if (!primary && !id.matches("[a-zA-Z0-9-]+")) continue;
                        File path = primary ? primaryPath() : new File("/storage", id);
                        result.add(new Entry(primary ? "Internal storage" : "USB storage · " + id, path, null, id));
                    }
                    result.add(new Entry("Indexed music and video", null, null));
                } else {
                    File[] files = folder.listFiles();
                    if (files == null) message = "This folder is unavailable. Go back or try Indexed music and video.";
                    else for (File file : files) {
                        if (file.isHidden() || file.getName().equals("Android") || !file.canRead()
                                || !folder.equals(file.getCanonicalFile().getParentFile())) continue;
                        if (file.isDirectory()) result.add(new Entry(file.getName(), file, null));
                        else if (file.isFile() && LocalMediaLibrary.isMediaFileName(file.getName()))
                            result.add(new Entry(file.getName(), null, Uri.fromFile(file)));
                    }
                    if (volumeId != null) {
                        Map<String, Entry> merged = new LinkedHashMap<>();
                        for (Entry entry : result) merged.put(entry.label, entry);
                        addIndexedFolder(merged, volumeId, sourceRoot, folder);
                        result.clear();
                        result.addAll(merged.values());
                        if (result.isEmpty()) message = "No music or video is visible in this folder.";
                    }
                    result.sort(Comparator.comparing((Entry item) -> item.folder == null)
                            .thenComparing(item -> item.label, String.CASE_INSENSITIVE_ORDER));
                }
            } catch (IOException | SecurityException unavailable) {
                message = "Storage is unavailable. Reconnect the drive and reopen its folder.";
            }
            String feedback = message;
            runOnUiThread(() -> {
                if (request != generation || isFinishing() || isDestroyed()) return;
                entries.addAll(result);
                empty.setText(feedback);
                empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                rows.notifyDataSetChanged();
                list.setSelection(0);
            });
        });
    }

    /** Scoped storage on Android 10 can expose a volume through MediaStore alone. */
    private void addIndexedFolder(Map<String, Entry> entries, String volume, File sourceRoot, File folder) {
        String path = folder.equals(sourceRoot) ? "" : folder.getPath().substring(sourceRoot.getPath().length() + 1) + "/";
        String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH};
        Uri table = MediaStore.Files.getContentUri(volume);
        String where = "media_type IN (?,?)";
        String[] args = {String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO),
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)};
        try (Cursor cursor = getContentResolver().query(table, columns, where, args, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) return;
                String name = cursor.getString(1);
                if (name == null || !LocalMediaLibrary.isMediaFileName(name) || name.startsWith(".")) continue;
                String relative = cursor.getString(2);
                if (relative == null) relative = "";
                if (!relative.startsWith(path) || relative.startsWith("Android/")) continue;
                String rest = relative.substring(path.length());
                if (rest.isEmpty()) {
                    entries.put(name, new Entry(name, null, MediaStore.Files.getContentUri(volume, cursor.getLong(0))));
                } else {
                    int end = rest.indexOf('/');
                    String child = end < 0 ? rest : rest.substring(0, end);
                    if (!child.isEmpty() && !child.startsWith("."))
                        entries.putIfAbsent(child, new Entry(child, new File(folder, child), null));
                }
            }
        } catch (RuntimeException unavailable) {
            // Firmware may expose this drive only through paths; keep its readable entries.
        }
    }

    @SuppressWarnings("deprecation")
    private static File primaryPath() { return Environment.getExternalStorageDirectory(); }

    // Android 10 does not expose StorageVolume.getDirectory().
    private static File volumePath(StorageVolume volume) {
        if (Build.VERSION.SDK_INT >= 30) return volume.getDirectory();
        if (volume.isPrimary()) return primaryPath();
        String id = volume.getUuid();
        return id != null && id.matches("[a-zA-Z0-9-]+") ? new File("/storage", id) : null;
    }

    private void commit() {
        if (selected.isEmpty()) return;
        android.content.Context app = getApplicationContext();
        library.importDocuments(new ArrayList<>(selected), (done, skipped, message) -> {
            Toast.makeText(app, message, Toast.LENGTH_LONG).show();
            app.sendBroadcast(new Intent("local.cc3.manager.MEDIA_LIBRARY_CHANGED").setPackage(app.getPackageName()));
        });
        Toast.makeText(this, "Importing into the CC3 library…", Toast.LENGTH_SHORT).show();
        finish();
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Entry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Entry entry = getItem(position);
            TextView label = recycled instanceof TextView ? (TextView) recycled : text("", 17, colors.ink, 550);
            boolean checked = entry.media != null && selected.contains(entry.media);
            label.setText(entry.media == null ? entry.label + "   ›" : (checked ? "✓   " : "□   ") + entry.label);
            label.setContentDescription(entry.label + (checked ? ", selected" : ""));
            label.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            label.setMinHeight(dp(60));
            label.setPadding(dp(16), dp(6), dp(16), dp(6));
            label.setBackground(shape(checked ? colors.selection : colors.surface, 10));
            return label;
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        ArrayList<String> uris = new ArrayList<>();
        for (Uri uri : selected) uris.add(uri.toString());
        state.putStringArrayList("selected", uris);
    }

    @Override protected void onDestroy() {
        generation++;
        worker.shutdownNow();
        super.onDestroy();
    }
}
