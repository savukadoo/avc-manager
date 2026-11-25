package local.cc3.manager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.SurfaceHolder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-only player. Its foreground service owns audio independently of the manager screen. */
public final class EntertainmentPlaybackService extends Service {
    public static final String PLAY = "local.cc3.manager.media.PLAY";
    public static final String TOGGLE = "local.cc3.manager.media.TOGGLE";
    public static final String PAUSE = "local.cc3.manager.media.PAUSE";
    public static final String STOP = "local.cc3.manager.media.STOP";
    public static final String NEXT = "local.cc3.manager.media.NEXT";
    public static final String PREVIOUS = "local.cc3.manager.media.PREVIOUS";
    public static final String SEEK = "local.cc3.manager.media.SEEK";
    public static final String SHUFFLE = "local.cc3.manager.media.SHUFFLE";
    public static final String REPEAT = "local.cc3.manager.media.REPEAT";
    private static final String CHANNEL = "cc3_local_media";
    private static final int NOTIFICATION_ID = 71;
    private static final int MAX_QUEUE = 1000;
    private static volatile EntertainmentPlaybackService instance;
    private static volatile State latest = State.empty();
    private static SurfaceHolder visibleSurface;

    public static final class State {
        public final String id, title, artist, message, repeat;
        public final boolean video, playing, preparing, shuffle, error;
        public final long position, duration;
        public final int queueIndex, queueSize, videoWidth, videoHeight;
        State(String id, String title, String artist, String message, String repeat,
              boolean video, boolean playing, boolean preparing, boolean shuffle, boolean error,
              long position, long duration, int queueIndex, int queueSize, int videoWidth, int videoHeight) {
            this.id = id; this.title = title; this.artist = artist; this.message = message; this.repeat = repeat;
            this.video = video; this.playing = playing; this.preparing = preparing; this.shuffle = shuffle; this.error = error;
            this.position = position; this.duration = duration; this.queueIndex = queueIndex;
            this.queueSize = queueSize; this.videoWidth = videoWidth; this.videoHeight = videoHeight;
        }
        static State empty() { return new State("", "Choose something to play", "", "Your music stays on this CC3.",
                "Off", false, false, false, false, false, 0, 0, -1, 0, 0, 0); }
    }

    public static State state() { return latest; }

    /** Queues identifiers, never arbitrary network addresses or externally supplied paths. */
    public static void play(Context context, String id, List<String> queue) {
        if (id == null || id.isEmpty() || id.length() > 1024) throw new IllegalArgumentException("Choose a local media file");
        ArrayList<String> bounded = new ArrayList<>();
        if (queue != null) {
            int selected = queue.indexOf(id);
            int start = Math.max(0, selected - MAX_QUEUE / 2);
            for (int n = start; n < Math.min(queue.size(), start + MAX_QUEUE); n++) {
                String entry = queue.get(n);
                if (entry != null && entry.length() <= 1024) bounded.add(entry);
            }
        }
        if (!bounded.contains(id)) bounded.add(0, id);
        Intent request = new Intent(context, EntertainmentPlaybackService.class).setAction(PLAY)
                .putExtra("id", id).putStringArrayListExtra("queue", bounded);
        context.startForegroundService(request);
    }

    public static void command(Context context, String action) { command(context, action, 0); }
    public static void command(Context context, String action, long position) {
        if (instance == null) return;
        context.startService(new Intent(context, EntertainmentPlaybackService.class)
                .setAction(action).putExtra("position", position));
    }

    /** UI-thread only. A video never starts or advances without an attached visible surface. */
    public static void attachSurface(SurfaceHolder holder) {
        visibleSurface = holder;
        EntertainmentPlaybackService service = instance;
        if (service != null) service.updateSurface();
    }
    public static void detachSurface(SurfaceHolder holder) {
        if (holder != null && visibleSurface != holder) return;
        visibleSurface = null;
        EntertainmentPlaybackService service = instance;
        if (service != null) {
            if (service.item != null && service.item.video) service.pause("Video paused when leaving the screen.", false);
            service.updateSurface();
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "cc3-media-metadata"));
    private final Random random = new Random();
    private LocalMediaLibrary library;
    private MediaPlayer player;
    private LocalMediaLibrary.Item item;
    private List<String> queue = Collections.emptyList();
    private int index = -1, generation, repeatMode, source = -1, dsp = -1;
    private int videoWidth, videoHeight;
    private long duration, position;
    private boolean ready, playing, preparing, shuffle, error, hasFocus, waitingRoute, resumeAfterFocus, ducked;
    private boolean sourceKnown, dspKnown, destroyed, foreground;
    private String currentId = "", title = "Choose something to play", artist = "", message = "Your music stays on this CC3.";
    private boolean currentVideo;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private MediaSession session;
    private FytModule sources, sound;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            readPosition(); publish(false); main.postDelayed(this, 500);
        }
    };
    private final Runnable routeTimeout = () -> fail("The CC3 did not confirm switching from FM to media. Try again.");
    private final Runnable routeSettled = this::startReadyPlayer;
    private final Runnable mediaTimeout = () -> fail("This file could not be opened. It may use an unsupported codec or be damaged.");
    private final Runnable idleStop = () -> {
        if (!playing && !preparing && !destroyed) stopPlayback();
    };
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause("Paused after the audio output changed.", false); }
    };

    @Override public void onCreate() {
        super.onCreate(); instance = this;
        library = new LocalMediaLibrary(this);
        audio = getSystemService(AudioManager.class);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this::focusChanged, main).build();
        session = new MediaSession(this, "CC3 Entertainment");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause("Paused", false); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSkipToNext() { advance(1, false); }
            @Override public void onSkipToPrevious() { advance(-1, false); }
            @Override public void onSeekTo(long target) { seek(target); }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) { return super.onMediaButtonEvent(mediaButtonIntent); }
        }, main);
        session.setSessionActivity(PendingIntent.getActivity(this, 101, Screen.managerIntent(this, "Entertainment"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Local media", NotificationManager.IMPORTANCE_LOW));
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        sources = new FytModule(this, "com.syu.ms.main", new int[]{0}, new FytModule.Listener() {
            @Override public void update(int field, int[] values) {
                source = values[0]; sourceKnown = true;
                if (waitingRoute && mediaSource(source)) {
                    main.removeCallbacks(routeTimeout); main.removeCallbacks(routeSettled);
                    main.postDelayed(routeSettled, 350);
                } else if (playing && !mediaSource(source)) {
                    pause("Paused because the CC3 audio source changed.", false);
                }
            }
            @Override public void disconnected() {
                sourceKnown = true; source = -1;
                if (waitingRoute) fail("The CC3 audio source is unavailable.");
            }
        });
        sound = new FytModule(this, "com.syu.ms.sound", new int[]{0}, new FytModule.Listener() {
            @Override public void update(int field, int[] values) { dsp = values[0]; dspKnown = true; }
            @Override public void disconnected() { dspKnown = true; dsp = -1; }
        });
        sources.start(); sound.start(); main.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (!foreground) { startForeground(NOTIFICATION_ID, notification()); foreground = true; }
        if (!PLAY.equals(action) && currentId.isEmpty() && !preparing) { stopPlayback(); return START_NOT_STICKY; }
        if (PLAY.equals(action)) {
            String requested = intent.getStringExtra("id");
            ArrayList<String> requestedQueue = intent.getStringArrayListExtra("queue");
            queue = requestedQueue == null ? new ArrayList<>() : new ArrayList<>(requestedQueue.subList(0, Math.min(MAX_QUEUE, requestedQueue.size())));
            if (requested == null || requested.length() > 1024) { fail("Choose a local media file."); return START_NOT_STICKY; }
            if (requested.startsWith("media:video:") || LocalMediaLibrary.isVideoFileName(requested)) { shuffle = false; repeatMode = 0; }
            if (!queue.contains(requested)) queue.add(0, requested);
            index = queue.indexOf(requested); load(requested);
        } else if (TOGGLE.equals(action)) {
            if (playing || preparing) pause("Paused", false); else resume();
        } else if (PAUSE.equals(action)) pause("Paused", false);
        else if (STOP.equals(action)) stopPlayback();
        else if (NEXT.equals(action)) advance(1, false);
        else if (PREVIOUS.equals(action)) advance(-1, false);
        else if (SEEK.equals(action)) seek(intent.getLongExtra("position", 0));
        else if (SHUFFLE.equals(action)) { shuffle = !shuffle; publish(true); }
        else if (REPEAT.equals(action)) { repeatMode = (repeatMode + 1) % 3; publish(true); }
        else if (item == null && !preparing) stopPlayback();
        return START_NOT_STICKY;
    }

    private void load(String id) {
        releasePlayer(); main.removeCallbacks(idleStop);
        int token = ++generation;
        currentId = id; currentVideo = id.startsWith("media:video:") || LocalMediaLibrary.isVideoFileName(id);
        item = null; ready = false; playing = false; preparing = true; error = false;
        title = "Opening local file…"; artist = ""; message = "Opening…"; duration = position = 0;
        videoWidth = videoHeight = 0; publish(true);
        worker.execute(() -> {
            LocalMediaLibrary.Item found = library.find(id);
            String foundTitle = found == null ? "" : found.title;
            String foundArtist = found == null ? "" : found.artist;
            if (found != null) {
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                try {
                    metadata.setDataSource(this, found.uri);
                    String tag = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    if (tag != null && !tag.trim().isEmpty()) foundTitle = clean(tag);
                    tag = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    if (tag != null && !tag.trim().isEmpty()) foundArtist = clean(tag);
                } catch (RuntimeException error) { Diagnostics.event("Media","MetadataRead",error); }
                finally { try { metadata.release(); } catch (java.io.IOException | RuntimeException ignored) { } }
            }
            final String taggedTitle = foundTitle, taggedArtist = foundArtist;
            main.post(() -> {
                if (destroyed || generation != token) return;
                if (found == null) { fail("This local file is unavailable. Refresh the library or grant media access."); return; }
                item = found; title = taggedTitle; artist = taggedArtist;
                if (item.video && !hasVisibleSurface()) { pause("Open the Video tab to play this file.", false); return; }
                preparePlayer(token);
            });
        });
    }

    private void preparePlayer(int token) {
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(focus.getAudioAttributes());
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(this, item.uri);
            if (item.video) updateSurface();
            player.setOnPreparedListener(prepared -> {
                if (generation != token || player != prepared) return;
                ready = true; duration = Math.max(0, prepared.getDuration());
                videoWidth = prepared.getVideoWidth(); videoHeight = prepared.getVideoHeight();
                main.removeCallbacks(mediaTimeout);
                // Give the FYT source subscriptions time to arrive before routing normal media.
                awaitSource(token, 0);
            });
            player.setOnVideoSizeChangedListener((changed, width, height) -> {
                if (player == changed) { videoWidth = width; videoHeight = height; publish(false); }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) fail("Cannot play this file on the CC3. Its codec may be unsupported or the file damaged.");
                return true;
            });
            player.setOnCompletionListener(completed -> {
                if (player != completed) return;
                position = duration; playing = false; advance(1, true);
            });
            main.postDelayed(mediaTimeout, 20000);
            player.prepareAsync(); publish(true);
        } catch (Exception failure) { fail("Cannot open this file. Check its format and local media access."); }
    }

    private void awaitSource(int token, int attempts) {
        if (destroyed || generation != token || !preparing || player == null) return;
        if ((!sourceKnown || !dspKnown) && attempts < 10) {
            main.postDelayed(() -> awaitSource(token, attempts + 1), 100); return;
        }
        beginPlayback();
    }

    private boolean requestFocus() {
        if (hasFocus) return true;
        hasFocus = audio != null && audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasFocus) { pause("Another app is using audio. Try Play again.", false); return false; }
        return true;
    }

    private void beginPlayback() {
        if (item == null || !ready || player == null) return;
        if (item.video && !hasVisibleSurface()) { pause("Open the Video tab to play this file.", false); return; }
        if (!requestFocus()) return;
        if (dsp == 10 && source == 1) {
            waitingRoute = true; preparing = true; message = "Switching from FM to media…";
            if (!sources.send(0, 0)) { fail("Cannot switch the CC3 from FM to media. Try again."); return; }
            main.removeCallbacks(routeTimeout); main.postDelayed(routeTimeout, 2500); publish(true);
        } else startReadyPlayer();
    }

    private void startReadyPlayer() {
        if (player == null || !ready || item == null || !hasFocus || (item.video && !hasVisibleSurface())) return;
        waitingRoute = false; main.removeCallbacks(routeTimeout); main.removeCallbacks(idleStop);
        try {
            player.setVolume(ducked ? 0.2f : 1f, ducked ? 0.2f : 1f);
            player.start(); playing = true; preparing = false; error = false; resumeAfterFocus = false;
            message = audio != null && (audio.isStreamMute(AudioManager.STREAM_MUSIC)
                    || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) ? "Playing · media volume is muted" : "Playing";
            session.setActive(true); publish(true);
        } catch (RuntimeException failed) { fail("Playback could not start. Try a different file."); }
    }

    private void resume() {
        main.removeCallbacks(idleStop);
        if (item == null) { if (!currentId.isEmpty()) load(currentId); return; }
        if (item.video && !hasVisibleSurface()) { pause("Open the Video tab to play this file.", false); return; }
        error = false;
        if (player == null || !ready) { load(item.id); return; }
        if (duration > 0 && position >= duration - 100) seek(0);
        preparing = true; beginPlayback();
    }

    private void pause(String why, boolean temporary) {
        boolean wasPlaying = playing;
        main.removeCallbacks(routeTimeout); main.removeCallbacks(routeSettled);
        waitingRoute = false; preparing = false;
        if (player != null && ready) {
            try { if (player.isPlaying()) player.pause(); } catch (RuntimeException error) { Diagnostics.event("Media","MetadataRead",error); }
            readPosition();
        } else if (player != null) { generation++; releasePlayer(); }
        // Metadata lookup may also be pending before a MediaPlayer exists.
        else generation++;
        playing = false; message = why; resumeAfterFocus = temporary && wasPlaying;
        if (!temporary) abandonFocus();
        main.removeCallbacks(idleStop); main.postDelayed(idleStop, 30L * 60 * 1000);
        publish(true);
    }

    private void seek(long target) {
        if (player == null || !ready || duration <= 0) return;
        position = Math.max(0, Math.min(target, duration));
        try { player.seekTo(position, MediaPlayer.SEEK_CLOSEST); publish(true); }
        catch (RuntimeException ignored) { }
    }

    private void advance(int direction, boolean automatic) {
        if (queue.isEmpty() || index < 0) { pause("Playback finished", false); return; }
        if (!automatic && direction < 0 && position > 3000) { seek(0); return; }
        if (automatic && repeatMode == 2) { seek(0); beginPlayback(); return; }
        int next = index + direction;
        if (shuffle && queue.size() > 1 && direction > 0) {
            next = random.nextInt(queue.size() - 1); if (next >= index) next++;
        }
        if (next < 0) next = repeatMode == 1 ? queue.size() - 1 : 0;
        if (next >= queue.size()) {
            if (repeatMode == 1 || !automatic) next = 0;
            else { pause("Playback finished", false); return; }
        }
        if (item != null && item.video && !hasVisibleSurface()) { pause("Video paused when leaving the screen.", false); return; }
        index = next; load(queue.get(index));
    }

    private void focusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            ducked = false;
            if (player != null && ready) try { player.setVolume(1f, 1f); } catch (RuntimeException error) { Diagnostics.event("Media","MetadataRead",error); }
            if (resumeAfterFocus && item != null && (!item.video || hasVisibleSurface())) { hasFocus = true; beginPlayback(); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            ducked = true;
            if (player != null && ready) try { player.setVolume(0.2f, 0.2f); } catch (RuntimeException error) { Diagnostics.event("Media","MetadataRead",error); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause("Paused while another app uses audio.", true);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            hasFocus = false; pause("Paused because another app started audio.", false);
        }
    }

    private static boolean mediaSource(int source) { return source == 0 || source == 10; }
    private static boolean hasVisibleSurface() {
        return visibleSurface != null && visibleSurface.getSurface() != null && visibleSurface.getSurface().isValid();
    }
    private void updateSurface() {
        if (player == null || item == null || !item.video) return;
        try { player.setDisplay(hasVisibleSurface() ? visibleSurface : null); }
        catch (RuntimeException failure) { pause("The video display is unavailable. Open Video and try again.", false); }
    }
    private void readPosition() {
        if (player == null || !ready) return;
        try { position = Math.max(0, player.getCurrentPosition()); }
        catch (RuntimeException ignored) { }
    }
    private void abandonFocus() {
        resumeAfterFocus = false; ducked = false;
        if (hasFocus && audio != null) audio.abandonAudioFocusRequest(focus);
        hasFocus = false;
    }
    private void releasePlayer() {
        main.removeCallbacks(routeTimeout); main.removeCallbacks(routeSettled); main.removeCallbacks(mediaTimeout);
        waitingRoute = false; ready = false;
        if (player != null) {
            MediaPlayer old = player; player = null;
            try { old.setOnErrorListener(null); old.setOnCompletionListener(null); old.reset(); }
            catch (RuntimeException ignored) { }
            finally { try { old.release(); } catch (RuntimeException error) { Diagnostics.event("Media","MetadataRead",error); } }
        }
    }
    private void fail(String why) {
        generation++; releasePlayer(); abandonFocus(); playing = preparing = false; error = true; message = why;
        main.removeCallbacks(idleStop); main.postDelayed(idleStop, 5L * 60 * 1000); publish(true);
    }
    private void stopPlayback() {
        generation++; releasePlayer(); abandonFocus(); item = null; playing = preparing = error = false;
        position = duration = 0; currentId = ""; currentVideo = false; title = "Choose something to play"; artist = ""; message = "Stopped";
        queue = Collections.emptyList(); index = -1; publish(false);
        session.setActive(false); stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf();
    }

    private void publish(boolean transportChanged) {
        latest = new State(currentId, title, artist, message,
                repeatMode == 0 ? "Off" : repeatMode == 1 ? "All" : "One", currentVideo,
                playing, preparing, shuffle, error, position, duration, index, queue.size(), videoWidth, videoHeight);
        if (session == null || !transportChanged) return;
        int status = error ? PlaybackState.STATE_ERROR : preparing ? PlaybackState.STATE_BUFFERING
                : playing ? PlaybackState.STATE_PLAYING : item == null ? PlaybackState.STATE_STOPPED : PlaybackState.STATE_PAUSED;
        PlaybackState.Builder playback = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
                .setState(status, position, playing ? 1f : 0f);
        if (error) playback.setErrorMessage(message);
        session.setPlaybackState(playback.build());
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist.isEmpty() ? "CC3 local library" : artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build());
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private PendingIntent action(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, EntertainmentPlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        Notification.Builder result = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_manager)
                .setContentTitle(title).setContentText(preparing ? "Opening local media…" : artist.isEmpty() ? message : artist)
                .setContentIntent(PendingIntent.getActivity(this, 100, Screen.managerIntent(this, "Entertainment"),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(playing || preparing)
                .setDeleteIntent(action(STOP, 5))
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_previous), "Previous", action(PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,playing || preparing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        playing || preparing ? "Pause" : "Play", action(TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_next), "Next", action(NEXT, 3)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_menu_close_clear_cancel), "Stop", action(STOP, 4)).build());
        if (session != null) result.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return result.build();
    }

    private static String clean(String value) {
        String result = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return result.length() > 300 ? result.substring(0, 300) : result;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true; generation++; main.removeCallbacksAndMessages(null);
        releasePlayer(); abandonFocus();
        if (sources != null) sources.stop(); if (sound != null) sound.stop();
        try { unregisterReceiver(noisy); } catch (IllegalArgumentException ignored) { }
        if (session != null) { session.setActive(false); session.release(); }
        worker.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE); foreground = false;
        if (instance == this) instance = null;
        latest = State.empty();
        super.onDestroy();
    }
}
