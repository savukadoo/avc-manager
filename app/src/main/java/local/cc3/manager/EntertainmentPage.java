package local.cc3.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Landscape local-media screen. Only the library scrolls; transport controls stay in view. */
final class EntertainmentPage {
    private final MainActivity activity;
    private final LocalMediaLibrary library;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService scans = Executors.newSingleThreadExecutor(r -> new Thread(r, "cc3-media-library"));
    private final LibraryAdapter adapter = new LibraryAdapter();
    private final List<Screen.PanelDialog> dialogs = new ArrayList<>();
    private List<LocalMediaLibrary.Item> all = Collections.emptyList();
    private final List<LocalMediaLibrary.Item> shown = new ArrayList<>();
    private Runnable uploadAction, importAction, deviceAccessAction;
    private LinearLayout root, body, libraryHeading, header, navigation, playerPanel, libraryPanel, fullscreenControls;
    private ImageButton fullscreenButton;
    private LinearLayout emptyLibrary, acquisitionActions;
    private TextView acquisitionTitle, acquisitionStatus;
    private Button headerImport, headerUpload, findMedia;
    private boolean libraryLoaded;
    private int kindCount;
    private Button fullscreenPlay, fullscreenPrevious, fullscreenNext;
    private Button musicTab, videoTab, refreshButton, playButton, previous, next, shuffle, repeat, stop, folders, deviceMedia;
    private TextView title, artist, status, elapsed, total, libraryCount, empty;
    private EditText search;
    private TactileSlider progress;
    private SurfaceView surface;
    private VideoFrame videoFrame;
    private TextView videoPlaceholder;
    private ListView list;
    private boolean video, active, closed, dragging, scanning, scanAgain, receiverRegistered, fullscreen, fullscreenWasPlaying;
    private int scanGeneration;
    private String query = "", folder = "", renderedId = "";

    EntertainmentPage(MainActivity activity) {
        this.activity = activity; library = new LocalMediaLibrary(activity);
    }

    void setUploadAction(Runnable action) { uploadAction = action; }
    void setImportAction(Runnable action) { importAction = action; }
    /** Optional root hook: request READ_EXTERNAL_STORAGE for existing on-device media. */
    void setDeviceAccessAction(Runnable action) { deviceAccessAction = action; }
    void setStorageAccessAction(Runnable action) { setDeviceAccessAction(action); }

    static final String USB_HELP = "For a USB stick, try FAT32 first. Each file must be smaller than 4 GiB. "
            + "exFAT and NTFS depend on your head unit’s firmware. A mounted drive must appear in the picker. "
            + "Select your files, then Open or Import. Keep the drive connected until copying finishes.";

    View build() {
        root = activity.column();
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        header = activity.row();
        header.addView(activity.helpHeading("Entertainment", 24,
                "Import copies music and video from internal storage or USB for offline playback. "
                + USB_HELP + "\n\nFind media plays existing files in place. Upload copies files from a laptop. "
                + "Imported files survive updates and are removed if you uninstall AVC Manager. Use video while parked."),
                new LinearLayout.LayoutParams(0, dp(56), 1));
        headerUpload = addHeaderButton(header, "Upload", this::upload, 132);
        headerImport = addHeaderButton(header, "Import", this::importFiles, 124);
        refreshButton = addHeaderButton(header, "Refresh", this::refreshLibrary, 132);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        navigation = activity.row();
        musicTab = activity.button("Music", false, () -> showVideo(false));
        videoTab = activity.button("Video", false, () -> showVideo(true));
        tabIcon(musicTab, R.drawable.ic_shortcut_usb_music);
        tabIcon(videoTab, R.drawable.ic_media_video);
        addButton(navigation, musicTab, 1); addButton(navigation, videoTab, 1);
        LinearLayout.LayoutParams navigationBounds = new LinearLayout.LayoutParams(-1, dp(64));
        navigationBounds.topMargin = dp(5); root.addView(navigation, navigationBounds);

        body = activity.row(); body.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(-1, 0, 1);
        fill.topMargin = dp(10); root.addView(body, fill);
        buildBody(); return root;
    }

    private Button addHeaderButton(LinearLayout row, String label, Runnable action, int width) {
        Button button = activity.button(label, false, action);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(dp(width), dp(56));
        bounds.leftMargin = dp(8); row.addView(button, bounds); return button;
    }

    private void tabIcon(Button tab, int resource) {
        android.graphics.drawable.Drawable glyph = activity.getDrawable(resource).mutate();
        glyph.setTint(activity.colors.ink);
        activity.inlineButtonIcon(tab, glyph);
    }


    void showVideo(boolean selected) {
        if (video == selected || body == null) return;
        exitFullscreen();
        hideKeyboard(); detachVideo(); video = selected; query = ""; folder = ""; renderedId = "";
        buildBody();
    }

    private void buildBody() {
        exitFullscreen();
        detachVideo(); body.removeAllViews();
        surface = null; videoFrame = null; videoPlaceholder = null;
        shuffle = repeat = deviceMedia = null;
        activity.styleButton(musicTab, activity.colors, false, false, !video);
        activity.styleButton(videoTab, activity.colors, false, false, video);
        musicTab.setSelected(!video); videoTab.setSelected(video);

        playerPanel = panel();
        LinearLayout.LayoutParams playerBounds = new LinearLayout.LayoutParams(0, -1, video ? 1.16f : 1f);
        playerBounds.rightMargin = dp(10); body.addView(playerPanel, playerBounds);
        if (video) buildVideoPlayer(playerPanel); else buildMusicPlayer(playerPanel);
        libraryPanel = panel();
        body.addView(libraryPanel, new LinearLayout.LayoutParams(0, -1, video ? 1f : 1.16f));
        buildLibrary(libraryPanel);
        buildEmptyLibrary();
        applyFilter(); render();
    }

    private void upload() {
        if (uploadAction != null) uploadAction.run(); else notice("Upload is unavailable.");
    }

    private void importFiles() {
        if (importAction != null) importAction.run(); else notice("File import is unavailable on this unit.");
    }

    private void buildEmptyLibrary() {
        emptyLibrary = panel();
        emptyLibrary.setGravity(Gravity.CENTER);
        acquisitionTitle = activity.managerText("", 22, activity.colors.ink, 650);
        acquisitionTitle.setGravity(Gravity.CENTER);
        acquisitionTitle.setAccessibilityHeading(true);
        emptyLibrary.addView(acquisitionTitle);
        acquisitionStatus = activity.managerText("", 15, activity.colors.muted, 500);
        acquisitionStatus.setGravity(Gravity.CENTER);
        acquisitionStatus.setPadding(0, dp(8), 0, dp(18));
        emptyLibrary.addView(acquisitionStatus);
        acquisitionActions = activity.column();
        Button choose = activity.button(video ? "Import video" : "Import music", true, this::importFiles);
        acquisitionActions.addView(choose, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout.LayoutParams secondary = new LinearLayout.LayoutParams(-1, dp(56));
        secondary.topMargin = dp(8);
        acquisitionActions.addView(activity.button("Upload from laptop", false, this::upload), secondary);
        findMedia = activity.button("Find on-device media", false, () -> {
            if (deviceAccessAction != null) deviceAccessAction.run();
        });
        acquisitionActions.addView(findMedia, new LinearLayout.LayoutParams(secondary));
        emptyLibrary.addView(acquisitionActions, new LinearLayout.LayoutParams(dp(Math.min(360, activity.paneWidth() - 48)), -2));
        body.addView(emptyLibrary, new LinearLayout.LayoutParams(-1, -1));
    }

    private void updateLibraryPresentation(boolean matches, boolean otherPlaying, EntertainmentPlaybackService.State state) {
        if (fullscreen) return;
        boolean emptyKind = kindCount == 0 && !matches && query.trim().isEmpty() && folder.isEmpty();
        emptyLibrary.setVisibility(emptyKind ? View.VISIBLE : View.GONE);
        playerPanel.setVisibility(emptyKind ? View.GONE : View.VISIBLE);
        libraryPanel.setVisibility(emptyKind ? View.GONE : View.VISIBLE);
        headerImport.setVisibility(emptyKind ? View.GONE : View.VISIBLE);
        headerUpload.setVisibility(emptyKind ? View.GONE : View.VISIBLE);
        if (!emptyKind) return;
        acquisitionTitle.setText(!libraryLoaded ? "Looking for local media…" : video ? "No videos yet" : "No music yet");
        String hint = otherPlaying && state.playing ? (state.video ? "Video is playing." : "Music is playing.")
                : video ? "Add a local video to watch while parked." : "Add tracks to listen offline.";
        acquisitionStatus.setText(libraryLoaded ? hint : "");
        acquisitionActions.setVisibility(libraryLoaded ? View.VISIBLE : View.INVISIBLE);
        findMedia.setVisibility(deviceAccessAction != null && !library.canReadDeviceMedia() ? View.VISIBLE : View.GONE);
    }

    private LinearLayout panel() {
        LinearLayout panel = activity.column(); panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(activity.shape(activity.colors.surface, 16)); return panel;
    }

    private void buildMusicPlayer(LinearLayout parent) {
        LinearLayout heading = activity.row();
        MediaGlyph glyph = new MediaGlyph(false);
        LinearLayout.LayoutParams glyphBounds = new LinearLayout.LayoutParams(dp(34), dp(34));
        glyphBounds.rightMargin = dp(10); heading.addView(glyph, glyphBounds);
        heading.addView(line("Now playing", 17, activity.colors.ink, 650), new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(heading, new LinearLayout.LayoutParams(-1, dp(36)));
        title = line("Choose something to play", 18, activity.colors.ink, 650);
        title.setSingleLine(false); title.setMaxLines(2); title.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(title, new LinearLayout.LayoutParams(-1, dp(60)));
        artist = line("", 12, activity.colors.muted, 450);
        parent.addView(artist, new LinearLayout.LayoutParams(-1, dp(22)));
        status = line("Your music stays on this CC3.", 12, activity.colors.muted, 450);
        status.setSingleLine(false); status.setMaxLines(2); status.setGravity(Gravity.CENTER_VERTICAL);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        parent.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));
        parent.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, 1));
        buildProgress(parent);
        LinearLayout transport = activity.row();
        previous = activity.button("Previous", false, () -> command(EntertainmentPlaybackService.PREVIOUS));
        playButton = activity.button("Play", true, this::togglePlay);
        next = activity.button("Next", false, () -> command(EntertainmentPlaybackService.NEXT));
        addButton(transport, previous, 1); addButton(transport, playButton, 1); addButton(transport, next, 1);
        parent.addView(transport, new LinearLayout.LayoutParams(-1, dp(64)));
        LinearLayout modes = activity.row();
        shuffle = activity.button("Shuffle", false, () -> command(EntertainmentPlaybackService.SHUFFLE));
        repeat = activity.button("Repeat: Off", false, () -> command(EntertainmentPlaybackService.REPEAT));
        stop = activity.button("Stop", false, () -> command(EntertainmentPlaybackService.STOP));
        addButton(modes, shuffle, 1); addButton(modes, repeat, 1); addButton(modes, stop, 1);
        parent.addView(modes, new LinearLayout.LayoutParams(-1, dp(64)));
    }

    private void buildVideoPlayer(LinearLayout parent) {
        videoFrame = new VideoFrame(); videoFrame.setBackgroundColor(android.graphics.Color.BLACK);
        surface = new SurfaceView(activity);
        videoFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        videoPlaceholder = line("Select a video", 16, activity.colors.muted, 500);
        videoPlaceholder.setGravity(Gravity.CENTER);
        videoFrame.addView(videoPlaceholder, new FrameLayout.LayoutParams(-1, -1));
        fullscreenButton = new ImageButton(activity);
        fullscreenButton.setImageResource(R.drawable.ic_fullscreen);
        fullscreenButton.setImageTintList(ColorStateList.valueOf(activity.colors.ink));
        fullscreenButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080),
                activity.shape(activity.colors.surface, 12), null));
        fullscreenButton.setContentDescription("Full screen video");
        fullscreenButton.setTooltipText("Full screen");
        fullscreenButton.setOnClickListener(view -> setFullscreen(true));
        FrameLayout.LayoutParams expandBounds = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        expandBounds.setMargins(dp(8), dp(8), dp(8), dp(8));
        videoFrame.addView(fullscreenButton, expandBounds);
        fullscreenControls = activity.row();
        fullscreenControls.setPadding(dp(8), dp(8), dp(8), dp(8));
        fullscreenControls.setBackgroundColor(0xe6101417);
        fullscreenPrevious = activity.button("Previous", false, () -> fullscreenCommand(EntertainmentPlaybackService.PREVIOUS));
        addButton(fullscreenControls, fullscreenPrevious, 1);
        fullscreenPlay = activity.button("Play", true, () -> { togglePlay(); showFullscreenControls(); });
        addButton(fullscreenControls, fullscreenPlay, 1);
        fullscreenNext = activity.button("Next", false, () -> fullscreenCommand(EntertainmentPlaybackService.NEXT));
        addButton(fullscreenControls, fullscreenNext, 1);
        addButton(fullscreenControls, activity.button("Exit full screen", false, this::exitFullscreen), 1);
        fullscreenControls.setVisibility(View.GONE);
        videoFrame.addView(fullscreenControls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (active && video) EntertainmentPlaybackService.attachSurface(holder);
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (active && video) EntertainmentPlaybackService.attachSurface(holder);
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { EntertainmentPlaybackService.detachSurface(holder); }
        });
        parent.addView(videoFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        title = line("Choose a local video", 15, activity.colors.ink, 650);
        title.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(title, new LinearLayout.LayoutParams(-1, dp(36)));
        artist = null;
        status = line("Video for use while parked", 12, activity.colors.muted, 450);
        status.setSingleLine(false); status.setMaxLines(2); status.setGravity(Gravity.CENTER_VERTICAL);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        parent.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));
        buildProgress(parent);
        LinearLayout transport = activity.row();
        previous = activity.button("Previous", false, () -> command(EntertainmentPlaybackService.PREVIOUS));
        playButton = activity.button("Play", true, this::togglePlay);
        next = activity.button("Next", false, () -> command(EntertainmentPlaybackService.NEXT));
        stop = activity.button("Stop", false, () -> command(EntertainmentPlaybackService.STOP));
        addButton(transport, previous, 1); addButton(transport, playButton, 1);
        addButton(transport, next, 1); addButton(transport, stop, 1);
        parent.addView(transport, new LinearLayout.LayoutParams(-1, dp(64)));
    }

    private final Runnable hideFullscreenControls = () -> {
        if (fullscreen && fullscreenControls != null && EntertainmentPlaybackService.state().playing)
            fullscreenControls.setVisibility(View.GONE);
    };

    private void fullscreenCommand(String action) {
        command(action);
        showFullscreenControls();
    }

    private void showFullscreenControls() {
        if (!fullscreen || fullscreenControls == null) return;
        fullscreenControls.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideFullscreenControls);
        ui.postDelayed(hideFullscreenControls, 3000);
    }

    private void setFullscreen(boolean enabled) {
        if (fullscreen == enabled || videoFrame == null || (enabled && !video)) return;
        fullscreen = enabled;
        fullscreenWasPlaying = false;
        activity.setMediaFullscreen(enabled);
        root.setPadding(enabled ? 0 : dp(16), enabled ? 0 : dp(12), enabled ? 0 : dp(16), enabled ? 0 : dp(16));
        header.setVisibility(enabled ? View.GONE : View.VISIBLE);
        navigation.setVisibility(enabled ? View.GONE : View.VISIBLE);
        libraryPanel.setVisibility(enabled ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams bodyBounds = (LinearLayout.LayoutParams) body.getLayoutParams();
        bodyBounds.topMargin = enabled ? 0 : dp(10); body.setLayoutParams(bodyBounds);
        LinearLayout.LayoutParams playerBounds = (LinearLayout.LayoutParams) playerPanel.getLayoutParams();
        playerBounds.rightMargin = enabled ? 0 : dp(10); playerPanel.setLayoutParams(playerBounds);
        playerPanel.setPadding(enabled ? 0 : dp(12), enabled ? 0 : dp(10), enabled ? 0 : dp(12), enabled ? 0 : dp(10));
        for (int i = 0; i < playerPanel.getChildCount(); i++) {
            View child = playerPanel.getChildAt(i);
            if (child != videoFrame) child.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
        fullscreenButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
        fullscreenControls.setVisibility(enabled ? View.VISIBLE : View.GONE);
        videoFrame.setOnClickListener(enabled ? view -> showFullscreenControls() : null);
        ui.removeCallbacks(hideFullscreenControls);
        if (enabled) { showFullscreenControls(); render(); }
        root.requestLayout();
    }

    boolean exitFullscreen() {
        if (!fullscreen) return false;
        setFullscreen(false);
        return true;
    }

    private void buildProgress(LinearLayout parent) {
        progress = new TactileSlider(activity, false); progress.setMax(1000);
        progress.setContentDescription("Playback position");
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) elapsed.setText(time(EntertainmentPlaybackService.state().duration * value / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                EntertainmentPlaybackService.command(activity, EntertainmentPlaybackService.SEEK,
                        EntertainmentPlaybackService.state().duration * bar.getProgress() / 1000); render();
            }
        });
        parent.addView(progress, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout times = activity.row();
        elapsed = line("0:00", 12, activity.colors.muted, 500);
        total = line("0:00", 12, activity.colors.muted, 500); total.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -1, 1));
        times.addView(total, new LinearLayout.LayoutParams(0, -1, 1));
        parent.addView(times, new LinearLayout.LayoutParams(-1, dp(22)));
    }

    private void buildLibrary(LinearLayout parent) {
        LinearLayout heading = activity.row();
        libraryHeading = heading;
        heading.addView(activity.helpHeading(video ? "Video library" : "Music library", 17,
                "Choose a file to play it. The current filtered list becomes the play queue, up to 1,000 files around your selection. "
                + "Search matches titles, artists and filenames; Folders limits the list to one folder. "
                + "MP3, M4A/AAC, FLAC, WAV, Ogg/Opus and AMR audio, and MP4, MKV, WebM, 3GP and transport-stream video can be added. "
                + "A file’s actual codec must also be supported by this head unit. Unsupported or damaged files show a playback error. "
                + "Files are never downloaded from music or video services."),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        boolean needsAccess = !library.canReadDeviceMedia() && deviceAccessAction != null;
        if (needsAccess) {
            Button access = activity.button("Find media", false, () -> deviceAccessAction.run());
            access.setSingleLine(true);
            deviceMedia = access;
            LinearLayout.LayoutParams accessBounds = new LinearLayout.LayoutParams(dp(160), dp(56));
            accessBounds.leftMargin = dp(8); heading.addView(access, accessBounds);
        }
        parent.addView(heading, new LinearLayout.LayoutParams(-1, dp(needsAccess ? 56 : 48)));
        LinearLayout filtering = activity.row();
        search = new EditText(activity); search.setSingleLine(true); search.setTextSize(14);
        search.setTextColor(activity.colors.ink); search.setHintTextColor(activity.colors.muted);
        search.setHint(video ? "Search videos" : "Search music"); search.setContentDescription(video ? "Search videos" : "Search music");
        search.setSelectAllOnFocus(false); search.setPadding(dp(12), dp(5), dp(12), dp(5));
        search.setBackground(activity.shape(activity.colors.background, 10)); search.setText(query);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); return true; }
            return false;
        });
        filtering.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        folders = activity.button("Folders", false, this::showFolders);
        LinearLayout.LayoutParams folderBounds = new LinearLayout.LayoutParams(dp(112), dp(56));
        folderBounds.leftMargin = dp(8); filtering.addView(folders, folderBounds);
        parent.addView(filtering, new LinearLayout.LayoutParams(-1, dp(60)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { query = value.toString(); applyFilter(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        libraryCount = line("Loading library…", 12, activity.colors.muted, 500);
        libraryCount.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(libraryCount, new LinearLayout.LayoutParams(-1, dp(30)));
        FrameLayout collection = new FrameLayout(activity);
        list = new ListView(activity); list.setAdapter(adapter); list.setDivider(null); list.setDividerHeight(dp(6));
        list.setClipToPadding(false); list.setPadding(0, dp(2), 0, dp(2)); list.setSelector(android.R.color.transparent);
        list.setOnItemClickListener((parentList, view, selected, id) -> select(shown.get(selected)));
        collection.addView(list, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout emptyPanel = activity.column(); emptyPanel.setGravity(Gravity.CENTER);
        empty = line("", 14, activity.colors.muted, 500); empty.setSingleLine(false);
        empty.setMaxLines(5); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(12), dp(12), dp(12), dp(12));
        emptyPanel.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        empty.setTag(emptyPanel);
        collection.addView(emptyPanel, new FrameLayout.LayoutParams(-1, -1));
        parent.addView(collection, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void applyFilter() {
        shown.clear(); String match = query.toLowerCase(Locale.ROOT).trim();
        int count = 0;
        for (LocalMediaLibrary.Item item : all) {
            if (item.video != video) continue; count++;
            if (!folder.isEmpty() && !folder.equals(item.folder)) continue;
            if (!match.isEmpty() && !(item.title + " " + item.artist + " " + item.name).toLowerCase(Locale.ROOT).contains(match)) continue;
            shown.add(item);
        }
        kindCount = count;
        adapter.notifyDataSetChanged();
        if (libraryCount == null) return;
        if (deviceMedia != null) {
            boolean needsAccess = !library.canReadDeviceMedia();
            deviceMedia.setVisibility(needsAccess ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams headingBounds = libraryHeading.getLayoutParams();
            if (headingBounds.height != dp(needsAccess ? 56 : 48)) { headingBounds.height = dp(needsAccess ? 56 : 48); libraryHeading.setLayoutParams(headingBounds); }
        }
        String qualifier = folder.isEmpty() ? "" : " · " + folder;
        libraryCount.setText(scanning ? "Refreshing local library…" : shown.size() + " of " + count + (video ? " videos" : " tracks") + qualifier);
        activity.styleButton(folders, activity.colors, false, false, !folder.isEmpty()); folders.setSelected(!folder.isEmpty());
        if (shown.isEmpty()) {
            empty.setText(scanning ? "Looking for local media…" : !query.trim().isEmpty() || !folder.isEmpty()
                    ? "No matching files. Change the search or choose All folders."
                    : "No " + (video ? "videos" : "music") + " yet. Upload from a laptop or import files already on this CC3.");
            ((View) empty.getTag()).setVisibility(View.VISIBLE);
        } else ((View) empty.getTag()).setVisibility(View.GONE);
        if (refreshButton != null) { refreshButton.setText(scanning ? "Refreshing…" : "Refresh"); refreshButton.setEnabled(!scanning); }
    }

    private void showFolders() {
        hideKeyboard();
        Screen.PanelDialog dialog = activity.new PanelDialog("Choose a folder", 660);
        Set<String> unique = new LinkedHashSet<>();
        for (LocalMediaLibrary.Item item : all) if (item.video == video) unique.add(item.folder);
        Button every = activity.button("All folders" + (folder.isEmpty() ? "  ✓" : ""), false,
                () -> { folder = ""; dialog.dismiss(); applyFilter(); });
        activity.styleButton(every, activity.colors, false, false, folder.isEmpty());
        dialog.body.addView(every, new LinearLayout.LayoutParams(-1, dp(56)));
        for (String name : unique) {
            Button option = activity.button(name + (folder.equals(name) ? "  ✓" : ""), false,
                    () -> { folder = name; dialog.dismiss(); applyFilter(); });
            option.setSingleLine(true); option.setEllipsize(TextUtils.TruncateAt.END);
            activity.styleButton(option, activity.colors, false, false, folder.equals(name));
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, dp(56)); bounds.topMargin = dp(7);
            dialog.body.addView(option, bounds);
        }
        dialog.action("Close", false, dialog::dismiss); dialogs.add(dialog); dialog.show();
    }

    private void select(LocalMediaLibrary.Item item) {
        hideKeyboard();
        if (item.video && (surface == null || !surface.getHolder().getSurface().isValid())) {
            notice("The video screen is opening. Please try again."); return;
        }
        if (item.video) EntertainmentPlaybackService.attachSurface(surface.getHolder());
        List<String> queue = new ArrayList<>(); for (LocalMediaLibrary.Item entry : shown) queue.add(entry.id);
        try { EntertainmentPlaybackService.play(activity, item.id, queue); }
        catch (RuntimeException error) { notice("The local player could not start."); }
        ui.postDelayed(this::render, 100);
    }

    private void togglePlay() {
        EntertainmentPlaybackService.State state = EntertainmentPlaybackService.state();
        if (state.id.isEmpty() || state.video != video) {
            if (shown.isEmpty()) notice("Add a local file first."); else select(shown.get(0)); return;
        }
        if (video && state.video && surface != null) EntertainmentPlaybackService.attachSurface(surface.getHolder());
        command(EntertainmentPlaybackService.TOGGLE);
    }
    private void command(String action) {
        EntertainmentPlaybackService.command(activity, action); ui.postDelayed(this::render, 100);
    }

    private void render() {
        if (closed || title == null) return;
        EntertainmentPlaybackService.State state = EntertainmentPlaybackService.state();
        boolean matches = !state.id.isEmpty() && state.video == video;
        boolean otherPlaying = !state.id.isEmpty() && state.video != video;
        updateLibraryPresentation(matches, otherPlaying, state);
        title.setText(matches ? state.title : video ? "Choose a local video" : "Choose something to play");
        if (artist != null) artist.setText(matches ? state.artist : "");
        status.setText(matches ? state.message : otherPlaying ? (state.video ? "Video is paused on the Video tab."
                : state.playing || state.preparing ? "Music is playing on the Music tab." : "Music is paused on the Music tab.")
                : video ? "Video for use while parked" : "Select a track from your library.");
        status.setTextColor(matches && state.error ? activity.colors.accent : activity.colors.muted);
        playButton.setText(matches && (state.playing || state.preparing) ? state.preparing ? "Opening…" : "Pause" : "Play");
        boolean playingHere = matches && (state.playing || state.preparing);
        activity.styleButton(playButton, activity.colors, matches || !shown.isEmpty(), false, playingHere);
        playButton.setSelected(playingHere);
        playButton.setContentDescription(playingHere ? state.preparing ? "Opening, tap to cancel" : "Playing, tap to pause" : "Play");
        playButton.setEnabled(matches || !shown.isEmpty());
        if (fullscreen && fullscreenPlay != null) {
            fullscreenPlay.setText(playingHere ? state.preparing ? "Opening…" : "Pause" : "Play");
            fullscreenPlay.setSelected(playingHere);
            fullscreenPlay.setEnabled(matches || !shown.isEmpty());
            fullscreenPrevious.setEnabled(matches && !state.preparing);
            fullscreenNext.setEnabled(matches && state.queueSize > 1 && !state.preparing);
            if (!state.playing) {
                ui.removeCallbacks(hideFullscreenControls);
                fullscreenControls.setVisibility(View.VISIBLE);
                fullscreenWasPlaying = false;
            } else if (!fullscreenWasPlaying) {
                fullscreenWasPlaying = true;
                showFullscreenControls();
            }
        }
        previous.setEnabled(matches && !state.preparing); next.setEnabled(matches && state.queueSize > 1 && !state.preparing);
        stop.setEnabled(!state.id.isEmpty() || state.preparing);
        progress.setEnabled(matches && state.duration > 0 && !state.preparing);
        if (!dragging) {
            progress.setProgress(matches && state.duration > 0 ? (int) Math.min(1000, state.position * 1000 / state.duration) : 0);
            elapsed.setText(time(matches ? state.position : 0));
        }
        total.setText(time(matches ? state.duration : 0));
        if (shuffle != null) {
            shuffle.setText("Shuffle" + (state.shuffle ? "  ✓" : ""));
            shuffle.setEnabled(matches); repeat.setEnabled(matches);
            activity.styleButton(shuffle, activity.colors, false, false, state.shuffle); shuffle.setSelected(state.shuffle);
            repeat.setText("Repeat: " + state.repeat);
            activity.styleButton(repeat, activity.colors, false, false, !"Off".equals(state.repeat));
            repeat.setSelected(!"Off".equals(state.repeat));
        }
        if (videoFrame != null) {
            videoFrame.setVideoSize(matches ? state.videoWidth : 0, matches ? state.videoHeight : 0);
            videoPlaceholder.setVisibility(matches && !state.error ? View.GONE : View.VISIBLE);
            videoPlaceholder.setText(matches && state.error ? "Video unavailable" : "Select a video");
        }
        if (!renderedId.equals(state.id)) {
            renderedId = state.id;
            adapter.notifyDataSetChanged();
            revealPlayingItem(state.id);
        }
    }

    private void revealPlayingItem(String id) {
        ListView target = list;
        if (target == null || id.isEmpty()) return;
        target.post(() -> {
            if (closed || !active || list != target || !id.equals(EntertainmentPlaybackService.state().id)) return;
            int position = -1;
            for (int i = 0; i < shown.size(); i++) if (shown.get(i).id.equals(id)) { position = i; break; }
            // Preserve search and folder filters if the playing item is outside this list.
            if (position < 0 || target.getHeight() <= 0) return;
            View row = target.getChildAt(position - target.getFirstVisiblePosition());
            int top = target.getPaddingTop(), bottom = target.getHeight() - target.getPaddingBottom();
            if (row == null || row.getTop() < top) {
                target.setSelectionFromTop(position, 0);
            } else if (row.getBottom() > bottom) {
                target.setSelectionFromTop(position, Math.max(0, bottom - top - row.getHeight()));
            }
        });
    }

    void refreshLibrary() {
        if (closed) return;
        if (scanning) { scanAgain = true; return; }
        scanning = true; int generation = ++scanGeneration; applyFilter();
        scans.execute(() -> {
            List<LocalMediaLibrary.Item> result = library.scan();
            ui.post(() -> {
                if (closed || generation != scanGeneration) return;
                scanning = false; libraryLoaded = true; all = result;
                if (body != null) applyFilter(); render();
                if (scanAgain) { scanAgain = false; refreshLibrary(); }
            });
        });
    }

    void importDocuments(List<Uri> documents) {
        if (documents == null || documents.isEmpty()) return;
        notice("Importing into the CC3 library…");
        library.importDocuments(documents, (imported, skipped, message) -> {
            if (closed) return; notice(message); refreshLibrary();
        });
    }

    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshLibrary(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (!active || closed) return; render(); ui.postDelayed(this, 500); }
    };
    void onResume() {
        if (closed) return; active = true;
        if (!receiverRegistered) {
            activity.registerReceiver(changed, new IntentFilter("local.cc3.manager.MEDIA_LIBRARY_CHANGED")); receiverRegistered = true;
        }
        if (video && surface != null && surface.getHolder().getSurface().isValid())
            EntertainmentPlaybackService.attachSurface(surface.getHolder());
        refreshLibrary(); ui.removeCallbacks(tick); ui.post(tick);
    }
    void onLeave() {
        exitFullscreen();
        ui.removeCallbacks(hideFullscreenControls);
        active = false; ui.removeCallbacks(tick); detachVideo(); hideKeyboard();
        if (receiverRegistered) { activity.unregisterReceiver(changed); receiverRegistered = false; }
        for (Screen.PanelDialog dialog : dialogs) dialog.dismiss(); dialogs.clear();
    }
    void close() { onLeave(); closed = true; scanGeneration++; ui.removeCallbacksAndMessages(null); scans.shutdownNow(); }
    private void detachVideo() { if (surface != null) EntertainmentPlaybackService.detachSurface(surface.getHolder()); }
    private void hideKeyboard() {
        if (search == null) return;
        InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }
    private TextView line(String value, int size, int color, int weight) {
        TextView view = activity.text(value, size, color, weight); view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END); return view;
    }
    private void addButton(LinearLayout row, Button button, float weight) {
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(0, dp(56), weight);
        bounds.setMargins(dp(3), dp(4), dp(3), dp(4)); row.addView(button, bounds);
    }
    private int dp(int value) { return activity.dp(value); }
    private void notice(String value) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show(); }
    private static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds >= 3600 ? String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private final class LibraryAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LocalMediaLibrary.Item item = shown.get(position);
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = activity.row(); row.setPadding(dp(10), dp(10), dp(10), dp(10));
                row.addView(new MediaGlyph(item.video), new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout labels = activity.column(); labels.setPadding(dp(12), 0, dp(4), 0);
                labels.addView(line("", 15, activity.colors.ink, 550), new LinearLayout.LayoutParams(-1, -2));
                TextView detail = line("", 11, activity.colors.muted, 450);
                LinearLayout.LayoutParams detailBounds = new LinearLayout.LayoutParams(-1, -2); detailBounds.topMargin = dp(5);
                labels.addView(detail, detailBounds); row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                row.setMinimumHeight(dp(80));
            }
            ((MediaGlyph) row.getChildAt(0)).video = item.video; row.getChildAt(0).invalidate();
            LinearLayout labels = (LinearLayout) row.getChildAt(1);
            ((TextView) labels.getChildAt(0)).setText(item.title);
            ((TextView) labels.getChildAt(1)).setText(item.subtitle());
            boolean selected = item.id.equals(EntertainmentPlaybackService.state().id);
            GradientDrawable fill = activity.shape(selected ? activity.colors.selection : activity.colors.background, 12);
            if (selected) fill.setStroke(dp(2), activity.colors.accent);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf((activity.colors.accent & 0x00ffffff) | 0x33000000), fill, null));
            row.setContentDescription(item.title + ". " + item.subtitle() + (selected ? ". Selected." : ". Play."));
            row.setActivated(selected); return row;
        }
    }

    private final class MediaGlyph extends View {
        boolean video;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        MediaGlyph(boolean video) { super(activity); this.video = video; setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight()), left = (getWidth() - size) / 2f, top = (getHeight() - size) / 2f;
            canvas.save(); canvas.translate(left, top); canvas.scale(size / 48, size / 48);
            paint.setStyle(Paint.Style.FILL); paint.setColor(activity.colors.selection); canvas.drawRoundRect(0, 0, 48, 48, 13, 13, paint);
            paint.setColor(activity.colors.accent);
            if (video) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.2f); canvas.drawRoundRect(10, 13, 38, 35, 4, 4, paint);
                paint.setStyle(Paint.Style.FILL); Path triangle = new Path(); triangle.moveTo(21, 18); triangle.lineTo(21, 30); triangle.lineTo(31, 24); triangle.close(); canvas.drawPath(triangle, paint);
            } else {
                canvas.drawRoundRect(21, 12, 24, 31, 1.5f, 1.5f, paint); canvas.drawRoundRect(21, 12, 34, 17, 1.5f, 1.5f, paint);
                canvas.drawOval(13, 27, 24, 35, paint);
            }
            canvas.restore();
        }
    }

    private final class VideoFrame extends FrameLayout {
        private int videoWidth, videoHeight;
        VideoFrame() { super(activity); }
        void setVideoSize(int width, int height) {
            if (videoWidth == width && videoHeight == height) return;
            videoWidth = width; videoHeight = height; requestLayout();
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            if (surface == null) return;
            int width = getMeasuredWidth(), height = getMeasuredHeight();
            if (videoWidth > 0 && videoHeight > 0 && width > 0 && height > 0) {
                float ratio = Math.min(width / (float) videoWidth, height / (float) videoHeight);
                width = Math.max(1, Math.round(videoWidth * ratio)); height = Math.max(1, Math.round(videoHeight * ratio));
            }
            surface.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }
}
