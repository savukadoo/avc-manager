package local.cc3.manager;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-device media only. App-library files survive updates and are removed on uninstall. */
public final class LocalMediaLibrary {
    public static final String[] AUDIO_EXTENSIONS = {"mp3","m4a","aac","flac","wav","ogg","oga","opus","amr"};
    public static final String[] VIDEO_EXTENSIONS = {"mp4","m4v","mkv","webm","3gp","3gpp","ts","m2ts"};
    private static final Set<String> AUDIO = new HashSet<>(Arrays.asList(AUDIO_EXTENSIONS));
    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList(VIDEO_EXTENSIONS));
    private static final long RESERVE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ITEMS = 10000;
    private static final ExecutorService IMPORTS = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cc3-media-import"); thread.setDaemon(true); return thread;
    });
    private final Context context;
    private final File directory;

    public static final class Item {
        public final String id, name, title, artist, folder;
        public final Uri uri;
        public final File file;
        public final boolean video;
        public final long bytes, modified, durationMillis;
        Item(String id, String name, String title, String artist, String folder, Uri uri,
             File file, boolean video, long bytes, long modified, long durationMillis) {
            this.id = id; this.name = name; this.title = cleanMetadata(title, baseName(name));
            this.artist = cleanMetadata(artist, ""); this.folder = folder == null ? "" : folder;
            this.uri = uri; this.file = file; this.video = video;
            this.bytes = Math.max(0, bytes); this.modified = Math.max(0, modified);
            this.durationMillis = Math.max(0, durationMillis);
        }
        public String subtitle() {
            String type = extension(name).toUpperCase(Locale.ROOT);
            return (artist.isEmpty() ? "" : artist + " · ") + type + " · " + sizeText(bytes)
                    + (file == null ? " · On device" : " · CC3 library");
        }
    }

    public interface ImportCallback { void complete(int imported, int skipped, String message); }

    public LocalMediaLibrary(Context context) {
        this.context = context.getApplicationContext();
        File external = this.context.getExternalFilesDir("entertainment");
        directory = external == null ? new File(this.context.getFilesDir(), "entertainment") : external;
    }

    public File root() {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IllegalStateException("CC3 media storage is unavailable");
        return directory;
    }

    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    public static boolean isMediaFileName(String name) { return AUDIO.contains(extension(name)) || VIDEO.contains(extension(name)); }
    public static boolean isVideoFileName(String name) { return VIDEO.contains(extension(name)); }

    /** Must run on a worker; filesystem and MediaStore metadata only, no decoder/network access. */
    public List<Item> scan() {
        List<Item> items = new ArrayList<>();
        Set<String> files = new HashSet<>();
        try { scanFolder(root(), root().getCanonicalPath(), 0, items, files); }
        catch (IOException | IllegalStateException error) { Diagnostics.event("Media","LibraryScan",error); }
        if (canReadDeviceMedia()) {
            readMediaStore(false, items, files, null);
            readMediaStore(true, items, files, null);
        }
        items.sort(Comparator.comparing((Item item) -> item.title.toLowerCase(Locale.ROOT))
                .thenComparing(item -> item.name.toLowerCase(Locale.ROOT)).thenComparing(item -> item.id));
        return Collections.unmodifiableList(items);
    }

    public boolean canReadDeviceMedia() {
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void scanFolder(File folder, String rootPath, int depth, List<Item> items, Set<String> files) throws IOException {
        if (depth > 6 || items.size() >= MAX_ITEMS || !folder.getCanonicalPath().startsWith(rootPath)) return;
        File[] children = folder.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File file : children) {
            if (items.size() >= MAX_ITEMS) break;
            if (file.getName().startsWith(".")) continue;
            String path = file.getCanonicalPath();
            if (!path.startsWith(rootPath + File.separator)) continue;
            if (file.isDirectory()) { scanFolder(file, rootPath, depth + 1, items, files); continue; }
            if (!file.isFile() || !isMediaFileName(file.getName()) || !files.add(path)) continue;
            String id = path.substring(rootPath.length() + 1);
            items.add(fileItem(id, file.getCanonicalFile()));
        }
    }

    private static Item fileItem(String id, File file) {
        return new Item(id, file.getName(), baseName(file.getName()), "", file.getParentFile().getName(),
                Uri.fromFile(file), file, isVideoFileName(file.getName()), file.length(), file.lastModified(), 0);
    }

    private void readMediaStore(boolean video, List<Item> items, Set<String> files, String wantedId) {
        Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = video
                ? new String[]{"_id", "_display_name", "title", "duration", "_size", "date_modified", "_data"}
                : new String[]{"_id", "_display_name", "title", "duration", "_size", "date_modified", "_data", "artist"};
        try (Cursor cursor = context.getContentResolver().query(collection, projection,
                wantedId == null ? null : "_id = ?", wantedId == null ? null : new String[]{wantedId}, "_id ASC")) {
            if (cursor == null) return;
            while (cursor.moveToNext() && items.size() < MAX_ITEMS) {
                String name = cursor.getString(1);
                if (!isMediaFileName(name)) continue;
                String data = cursor.getString(6);
                String folder = "On-device media";
                if (data != null && !data.isEmpty()) {
                    File physical = new File(data);
                    if (!files.add(physical.getCanonicalPath())) continue;
                    if (physical.getParentFile() != null) folder = physical.getParentFile().getName();
                }
                long id = cursor.getLong(0);
                items.add(new Item("media:" + (video ? "video:" : "audio:") + id, name, cursor.getString(2), video ? "" : cursor.getString(7), folder,
                        ContentUris.withAppendedId(collection, id), null, video, cursor.getLong(4),
                        cursor.getLong(5) * 1000L, cursor.getLong(3)));
            }
        } catch (RuntimeException | IOException ignored) {
            // A firmware/provider that rejects columns or a revoked permission cannot prevent
            // playback from the private CC3 library. Existing files are never modified.
        }
    }

    public Item find(String id) {
        if (id == null || id.length() > 1024 || id.indexOf('\0') >= 0) return null;
        if (id.startsWith("media:")) {
            if (!canReadDeviceMedia() || !id.matches("media:(audio|video):[0-9]{1,18}")) return null;
            List<Item> found = new ArrayList<>();
            readMediaStore(id.startsWith("media:video:"), found, new HashSet<>(), id.substring(id.lastIndexOf(':') + 1));
            return found.isEmpty() ? null : found.get(0);
        }
        try {
            File root = root().getCanonicalFile();
            File file = new File(root, id).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile()
                    || !isMediaFileName(file.getName())) return null;
            return fileItem(file.getPath().substring(root.getPath().length() + 1), file);
        } catch (IOException | IllegalStateException ignored) { return null; }
    }

    /** Copies explicitly chosen local files into the CC3 library, atomically and without overwrite. */
    public void importDocuments(List<Uri> documents, ImportCallback callback) {
        List<Uri> selected = documents == null ? Collections.emptyList() : new ArrayList<>(documents);
        IMPORTS.execute(() -> {
            int imported = 0, skipped = 0;
            String error = "";
            for (int index = 0; index < selected.size(); index++) {
                if (index >= 100) { skipped += selected.size() - index; error = "Choose up to 100 files at a time."; break; }
                try { importOne(selected.get(index)); imported++; }
                catch (IOException | RuntimeException failure) { skipped++; error = safeError(failure); }
            }
            final int done = imported, missing = skipped;
            final String message = done + " imported" + (missing == 0 ? "" : " · " + missing + " skipped. " + error);
            new Handler(Looper.getMainLooper()).post(() -> { if (callback != null) callback.complete(done, missing, message); });
        });
    }

    private void importOne(Uri uri) throws IOException {
        if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme())))
            throw new IOException("Choose an on-device audio or video file.");
        String name = null; long expected = -1;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) { name = cursor.getString(0); if (!cursor.isNull(1)) expected = cursor.getLong(1); }
            }
        } else if (uri.getPath() != null) { File original = new File(uri.getPath()); name = original.getName(); expected = original.length(); }
        if (name == null) name = uri.getLastPathSegment();
        name = MediaFileStore.safeName(name);
        if (!isMediaFileName(name)) throw new IOException("That file type is not supported by this library.");
        File root = root();
        if (expected >= 0 && expected > root.getUsableSpace() - RESERVE_BYTES) throw new IOException("Not enough free storage.");
        File partial = MediaFileStore.incoming(root, "import");
        boolean committed = false;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(partial)) {
                if (input == null) throw new IOException("The selected file could not be opened.");
                byte[] buffer = new byte[65536]; long written = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    if (root.getUsableSpace() < RESERVE_BYTES + count) throw new IOException("Not enough free storage.");
                    output.write(buffer, 0, count); written += count;
                }
                if (expected >= 0 && written != expected) throw new IOException("The selected file changed during import.");
                output.getFD().sync();
            }
            MediaFileStore.commit(root, partial, name);
            committed = true;
        } finally { if (!committed) partial.delete(); }
    }

    private static String cleanMetadata(String value, String fallback) {
        return value == null || value.trim().isEmpty() || value.equals("<unknown>") ? fallback : value.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
    private static String baseName(String name) {
        if (name == null) return "Media";
        int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name;
    }
    private static String safeError(Throwable failure) {
        String text = failure.getMessage(); return text == null || text.length() > 160 ? "The file could not be imported." : text;
    }
    public static String sizeText(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
    }
}
