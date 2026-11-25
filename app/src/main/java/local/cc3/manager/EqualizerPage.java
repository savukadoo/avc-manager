package local.cc3.manager;

import android.Manifest;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntConsumer;

final class EqualizerPage implements FytModule.Listener {
    private static final int DSP = 1;
    private static final int C32107S = 10;
    private static final int AUDIO_SOURCE = 0;
    private static final int ANDROID_MEDIA = 0;
    private static final int FM_RADIO = 1;
    private static final int THIRD_PARTY_MEDIA = 10;
    private static final int GAIN = 9;
    private static final int PRESET = 10;
    private static final int FREQUENCY = 18;
    private static final int EDITED = 72;
    private static final int MULTI_PRESET = 74;
    private static final int BAND_COUNT = 27;
    private static final int STANDARD = 1;
    private static final int TECHNO = -2;
    private static final String PREVIOUS = "eq_previous_preset";
    private static final String OWN_PREVIEW = "eq_manager_preview";
    private static final String PREVIEW_LABEL = "eq_manager_preview_label";
    private static final String TONE_SETTINGS = "eq_tone_settings";
    private static final int TONE_RANGE = 4;
    private static final String TEST_SONG_FILE = "Imagine Dragons - Believer (Live Acoustic).mp3";
    private static final String TEST_SONG_FOLDER = "VaudioShow/";
    private static final float TEST_TRACK_VOLUME = 0.38f;
    private static final String[] PRESETS = {"User", "Standard", "Rock", "Soft", "Classical", "Pop", "Hall", "Jazz", "Cinema"};

    private final MainActivity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FytModule sound;
    private final FytModule sources;
    private final AudioManager audio;
    private final AudioFocusRequest audioFocus;
    private final TextView status;
    private final TextView bandLabel;
    private final Button restore, prior, next;
    private final Button testTrack;
    private final Button tone;
    private final Button position;
    private SpeakerPosition speakerPosition;
    private final SpeakerPosition.SoundField speakerField = new SpeakerPosition.SoundField();
    private ToneEditor toneEditor;
    private MediaPlayer player;
    private boolean playingBeliever;
    private boolean waitingForTrackAccess;
    private int playbackChecks;
    private int audioSource = -1;
    private boolean switchingFromRadio;
    private boolean hasTestFocus;
    private final Runnable startOnMedia = this::startTrack;
    private final Runnable checkPlayback = this::confirmTrackProgress;
    private final Runnable playbackTimeout = () -> {
        stopTrack();
        notice("Could not start the test track.");
    };
    private final Runnable routeTimeout = () -> {
        stopTrack();
        notice("Could not switch the CC3 from FM to media.");
    };
    private final Button[] presets = new Button[9];
    private final Curve curve;
    private final SeekBar bandLevel;
    private final int[] frequencies = new int[BAND_COUNT];
    private final int[] gain = new int[BAND_COUNT];
    private int module = -1;
    private int preset = -1;
    private int edited = -1;
    private int multi = -1;
    private int band = 13;
    private int pendingPreset = -1;
    private int[] pendingGain;
    private boolean applyingGains;
    private boolean connected;
    private boolean dragging;
    private String message;
    private final Runnable settle = this::finishPreset;
    private final Runnable render = this::refresh;
    private final Runnable finishGains = () -> {
        applyingGains = false;
        if (!ownsPreview()) message = "The DSP did not confirm all band changes.";
        refresh();
    };

    EqualizerPage(MainActivity activity) {
        this.activity = activity;
        sound = new FytModule(activity, "com.syu.ms.sound",
                new int[] {DSP, SpeakerPosition.POSITION_UPDATE, SpeakerPosition.MODE_UPDATE,
                        SpeakerPosition.GAIN_UPDATE, PRESET, FREQUENCY, GAIN, EDITED, MULTI_PRESET}, this);
        sources = new FytModule(activity, "com.syu.ms.main", new int[] {AUDIO_SOURCE}, new FytModule.Listener() {
            @Override public void update(int field, int[] values) {
                updateSource(values[0]);
            }

            @Override public void disconnected() {
                audioSource = -1;
                if (switchingFromRadio) {
                    stopTrack();
                    notice("The CC3 audio source became unavailable.");
                }
            }
        });
        audio = activity.getSystemService(AudioManager.class);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) stopTrack();
                }, main).build();
        Arrays.fill(gain, -1);

        int width = activity.paneWidth();
        boolean split = width >= 1000;
        activity.pageHeading("Sound",
                "Tap the graph or use the arrows to select a frequency band. Use the slider to adjust the band’s gain. "
                        + "Play test track uses Imagine Dragons’ Believer (Live Acoustic) from the CC3 music library "
                        + "or the built-in instrumental if the song is missing.");

        LinearLayout soundActions = activity.row();
        position = activity.button("Speaker position", false, this::showSpeakerPosition);
        activity.styleButton(position, activity.colors, false, true, false);
        activity.tile(soundActions, position);
        tone = activity.button("Bass & treble", false, this::showTone);
        activity.styleButton(tone, activity.colors, false, true, false);
        activity.tile(soundActions, tone);
        testTrack = activity.button("Play test track", false, this::toggleTrack);
        activity.styleButton(testTrack, activity.colors, false, true, false);
        activity.tile(soundActions, testTrack);
        activity.addSection(soundActions);

        LinearLayout panel = activity.card();
        panel.setId(R.id.equalizer_graph_panel);
        status = activity.managerText("Reading DSP settings…", 16, activity.colors.ink, 600);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);
        curve = new Curve();
        int height = activity.getResources().getConfiguration().screenHeightDp;
        curve.setMinimumHeight(activity.dp(204));
        LinearLayout.LayoutParams curveBounds = new LinearLayout.LayoutParams(-1, split ? 0 : activity.dp(204), split ? 1 : 0);
        curveBounds.topMargin = activity.dp(12);
        panel.addView(curve, curveBounds);
        bandLabel = activity.managerText("Select a band", 15, activity.colors.muted, 500);
        bandLabel.setPadding(0, activity.dp(10), 0, 0);
        panel.addView(bandLabel);
        LinearLayout editor = activity.row();
        prior = activity.button("‹", false, () -> selectBand(band - 1));
        prior.setContentDescription("Lower frequency band");
        prior.setMinWidth(activity.dp(60));
        activity.styleButton(prior, activity.colors, false, true, false);
        editor.addView(prior);
        bandLevel = new TactileSlider(activity, true);
        bandLevel.setMax(20);
        bandLevel.setProgress(10);
        bandLevel.setContentDescription("Selected band gain");
        LinearLayout.LayoutParams sliderLayout = new LinearLayout.LayoutParams(0, activity.dp(56), 1);
        sliderLayout.setMargins(activity.dp(9), 0, activity.dp(9), 0);
        editor.addView(bandLevel, sliderLayout);
        next = activity.button("›", false, () -> selectBand(band + 1));
        next.setContentDescription("Higher frequency band");
        next.setMinWidth(activity.dp(60));
        activity.styleButton(next, activity.colors, false, true, false);
        editor.addView(next);
        panel.addView(editor);

        LinearLayout profiles = activity.card();
        profiles.setId(R.id.equalizer_profiles_panel);
        profiles.addView(activity.helpHeading("Sound profiles", 17,
                "Choose a preset to hear it on the CC3 DSP. Restore previous returns to the preset you were using before."));
        int[] order = {1, 7, TECHNO, 2, 5, 4, 3, 6, 8};
        int columns = split ? 2 : width >= 740 ? 5 : width >= 455 ? 3 : 2;
        for (int start = 0; start < order.length; start += columns) {
            LinearLayout row = activity.row();
            for (int index = start; index < Math.min(start + columns, order.length); index++) {
                int id = order[index];
                Button button = activity.button(id == TECHNO ? "Techno" : PRESETS[id], false,
                        () -> { if (id == TECHNO) techno(); else applyPreset(id); });
                activity.styleButton(button, activity.colors, false, true, false);
                presets[index] = button;
                button.setTag(id);
                activity.tile(row, button);
            }
            profiles.addView(row);
        }
        restore = activity.button("Restore previous", false, this::restore);
        activity.styleButton(restore, activity.colors, false, true, false);
        LinearLayout.LayoutParams restoreBounds = new LinearLayout.LayoutParams(-1, -2);
        restoreBounds.topMargin = activity.dp(8);
        if (split) profiles.addView(new View(activity), new LinearLayout.LayoutParams(1, 0, 1));
        profiles.addView(restore, restoreBounds);
        if (split) {
            LinearLayout columnsRow = activity.row();
            columnsRow.setBaselineAligned(false);
            columnsRow.setGravity(android.view.Gravity.TOP);
            columnsRow.addView(panel, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams profileBounds = new LinearLayout.LayoutParams(activity.dp(318), -1);
            profileBounds.leftMargin = activity.dp(12);
            columnsRow.addView(profiles, profileBounds);
            activity.addSection(columnsRow);
        } else {
            activity.addSection(panel);
            activity.addSection(profiles);
        }

        bandLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean user) {
                if (!user) return;
                showBand(progress);
                if (!dragging) adjustBand(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar slider) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar slider) {
                dragging = false;
                adjustBand(slider.getProgress());
            }
        });
        refresh();
    }

    void start() {
        sound.start();
        sources.start();
        main.removeCallbacks(render);
        main.postDelayed(render, 2400);
    }

    void stop() {
        stopTrack();
        if (toneEditor != null) toneEditor.close();
        if (speakerPosition != null) speakerPosition.close();
        sound.stop();
        sources.stop();
        audioSource = -1;
        main.removeCallbacksAndMessages(null);
        pendingPreset = -1;
        pendingGain = null;
        applyingGains = false;
        connected = false;
        speakerField.clear();
    }

    private void toggleTrack() {
        if (hasTestFocus) {
            stopTrack();
            return;
        }
        if (audio == null || audio.isMusicActive() || audio.getMode() != AudioManager.MODE_NORMAL) {
            notice("Audio is already playing. Pause it to use the test track.");
            return;
        }
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            notice("Raise the media volume to hear the test track.");
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            waitingForTrackAccess = true;
            activity.requestTestTrackAccess();
            return;
        }
        if (audio.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            notice("Audio is busy.");
            return;
        }
        hasTestFocus = true;
        if (module == C32107S && audioSource == FM_RADIO) {
            if (!sources.send(AUDIO_SOURCE, ANDROID_MEDIA)) {
                stopTrack();
                notice("Could not switch the CC3 from FM to media.");
                return;
            }
            switchingFromRadio = true;
            testTrack.setText("Switching to media…");
            main.postDelayed(routeTimeout, 2500);
        } else {
            startTrack();
        }
    }

    void testTrackAccessResult(boolean allowed) {
        if (!waitingForTrackAccess) return;
        waitingForTrackAccess = false;
        if (allowed) main.post(this::toggleTrack);
        else notice("Allow media access to play Believer from the CC3 music library.");
    }

    private void updateSource(int source) {
        audioSource = source;
        if (!switchingFromRadio) return;
        if (testMediaSource(source) && player == null) {
            main.removeCallbacks(routeTimeout);
            main.removeCallbacks(startOnMedia);
            // The main module reports its source before the DSP input settles.
            main.postDelayed(startOnMedia, 350);
        } else if (!testMediaSource(source) && (source != FM_RADIO || player != null)) {
            switchingFromRadio = false;
            stopTrack();
        }
    }

    private static boolean testMediaSource(int source) {
        // FYT may promote a playing Android stream to its third-party player source.
        return source == ANDROID_MEDIA || source == THIRD_PARTY_MEDIA;
    }

    private void startTrack() {
        if (!hasTestFocus) return;
        try {
            Uri song = findBeliever();
            playingBeliever = song != null;
            player = new MediaPlayer();
            player.setAudioAttributes(audioFocus.getAudioAttributes());
            if (playingBeliever) {
                player.setDataSource(activity, song);
            } else {
                try (AssetFileDescriptor instrumental = activity.getResources().openRawResourceFd(R.raw.eq_test_track)) {
                    player.setDataSource(instrumental.getFileDescriptor(),
                            instrumental.getStartOffset(), instrumental.getLength());
                }
            }
            player.setOnPreparedListener(ready -> {
                if (ready != player) return;
                main.removeCallbacks(playbackTimeout);
                ready.setVolume(TEST_TRACK_VOLUME, TEST_TRACK_VOLUME);
                ready.start();
                playbackChecks = 0;
                main.postDelayed(checkPlayback, 150);
            });
            player.setOnCompletionListener(finished -> {
                if (finished == player) stopTrack();
            });
            player.setOnErrorListener((failed, what, extra) -> {
                if (failed == player) {
                    stopTrack();
                    notice("Could not play the test track.");
                }
                return true;
            });
            testTrack.setText(playingBeliever ? "Starting Believer…" : "Starting test track…");
            player.prepareAsync();
            main.postDelayed(playbackTimeout, 8000);
        } catch (IOException | RuntimeException error) {
            stopTrack();
            notice("Could not play the test track.");
        }
    }

    private Uri findBeliever() {
        Uri music = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String id = MediaStore.Audio.Media._ID;
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + " = ? AND "
                + MediaStore.Audio.Media.DISPLAY_NAME + " = ?";
        try (Cursor tracks = activity.getContentResolver().query(music, new String[] {id},
                selection, new String[] {TEST_SONG_FOLDER, TEST_SONG_FILE}, null)) {
            return tracks != null && tracks.moveToFirst()
                    ? ContentUris.withAppendedId(music, tracks.getLong(0)) : null;
        }
    }

    private void confirmTrackProgress() {
        if (player == null) return;
        if (player.isPlaying() && player.getCurrentPosition() > 0) {
            testTrack.setText(playingBeliever ? "Playing Believer · Tap to stop" : "Playing · Tap to stop");
            testTrack.setSelected(true);
            activity.styleButton(testTrack, activity.colors, false, true, true);
        } else if (++playbackChecks >= 16) {
            stopTrack();
            notice("The CC3 did not advance audio playback.");
        } else {
            main.postDelayed(checkPlayback, 150);
        }
    }

    private void stopTrack() {
        if (!hasTestFocus) return;
        hasTestFocus = false;
        main.removeCallbacks(routeTimeout);
        main.removeCallbacks(startOnMedia);
        main.removeCallbacks(playbackTimeout);
        main.removeCallbacks(checkPlayback);
        MediaPlayer old = player;
        player = null;
        if (old != null) old.release();
        if (switchingFromRadio && (testMediaSource(audioSource) || audioSource == FM_RADIO)) {
            sources.send(AUDIO_SOURCE, FM_RADIO);
        }
        switchingFromRadio = false;
        audio.abandonAudioFocusRequest(audioFocus);
        testTrack.setText("Play test track");
        testTrack.setSelected(false);
        activity.styleButton(testTrack, activity.colors, false, true, false);
    }

    private void notice(String message) {
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override public void disconnected() {
        connected = false;
        speakerField.clear();
        if (speakerPosition != null) speakerPosition.unavailable();
        pendingPreset = -1;
        pendingGain = null;
        applyingGains = false;
        message = "The CC3 DSP service is unavailable.";
        refresh();
    }

    @Override public void update(int field, int[] values) {
        connected = true;
        switch (field) {
            case DSP: module = values[0]; break;
            case SpeakerPosition.POSITION_UPDATE:
            case SpeakerPosition.MODE_UPDATE:
            case SpeakerPosition.GAIN_UPDATE:
                if (speakerField.update(field, values) && speakerPosition != null) speakerPosition.readback();
                break;
            case PRESET: preset = values[0]; break;
            case EDITED: edited = values[0]; break;
            case MULTI_PRESET: multi = values[0]; break;
            case FREQUENCY:
            case GAIN:
                if (values.length >= 2 && values[0] >= 0 && values[0] < BAND_COUNT) {
                    (field == FREQUENCY ? frequencies : gain)[values[0]] = values[1];
                }
                break;
            default: return;
        }
        main.removeCallbacks(render);
        main.postDelayed(render, 45);
    }

    private boolean ready() {
        if (!connected || module != 10 || preset < 0 || edited < 0 || multi < 0) return false;
        for (int i = 0; i < BAND_COUNT; i++) {
            if (frequencies[i] < 20 || frequencies[i] > 22000 || gain[i] < 0 || gain[i] > 20) return false;
        }
        return true;
    }

    private boolean editable() {
        return ready() && multi == 1 && (edited == 0 || ownsPreview());
    }

    private boolean ownsPreview() {
        Object saved = activity.prefs().getAll().get(OWN_PREVIEW);
        return ready() && edited != 0 && previewSignature(gain).equals(saved);
    }

    private String previewSignature(int[] levels) {
        return preset + ":" + Arrays.toString(levels);
    }

    private void recordPreview(int[] levels, boolean techno) {
        activity.prefs().edit().putString(OWN_PREVIEW, previewSignature(levels))
                .putString(PREVIEW_LABEL, techno ? "Techno" : "").apply();
        applyingGains = true;
        main.removeCallbacks(finishGains);
        main.postDelayed(finishGains, 650);
    }

    private boolean idle() {
        return pendingPreset == -1 && !applyingGains;
    }

    private boolean canAdjustBands() {
        return idle() && editable() && preset >= 1 && preset <= 8;
    }

    private void remember() {
        if (edited == 0) {
            activity.prefs().edit().putInt(PREVIOUS, preset).remove(OWN_PREVIEW).remove(PREVIEW_LABEL).apply();
        }
    }

    private void applyPreset(int id) {
        if (!ready() || !idle() || (edited != 0 && !ownsPreview())) return;
        if (preset == id && edited == 0) return;
        remember();
        sendPreset(id, null);
    }

    private void restore() {
        int previous = activity.prefs().getInt(PREVIOUS, -1);
        if (!ready() || !idle() || previous < 0 || (edited != 0 && !ownsPreview())) return;
        sendPreset(previous, null);
    }

    private void techno() {
        if (!editable() || !idle()) return;
        remember();
        int[] target = new int[BAND_COUNT];
        for (int i = 0; i < BAND_COUNT; i++) {
            int hz = frequencies[i];
            target[i] = hz >= 176 && hz <= 388 ? 8 : hz >= 100 && hz < 1450 ? 9 : 10;
        }
        sendPreset(STANDARD, target);
    }

    private void sendPreset(int id, int[] target) {
        message = null;
        pendingPreset = id;
        pendingGain = target;
        if (!sound.send(2, id)) {
            disconnected();
            return;
        }
        main.removeCallbacks(settle);
        main.postDelayed(settle, 420);
        refresh();
    }

    private void finishPreset() {
        int requested = pendingPreset;
        int[] target = pendingGain;
        pendingPreset = -1;
        pendingGain = null;
        if (preset != requested || edited != 0 || !ready()) {
            message = "The DSP did not confirm the preset change.";
        } else if (target != null && multi == 1 && preset == STANDARD) {
            activity.prefs().edit().remove(TONE_SETTINGS).apply();
            boolean ok = true;
            for (int i = 0; i < BAND_COUNT; i++) {
                if (target[i] != gain[i] && !sound.send(1, i, target[i])) ok = false;
            }
            recordPreview(target, true);
            if (!ok) message = "Some band changes were not sent. Restore the previous preset.";
        } else {
            activity.prefs().edit().remove(OWN_PREVIEW).remove(PREVIEW_LABEL).remove(TONE_SETTINGS).apply();
        }
        refresh();
    }

    private void selectBand(int index) {
        if (!ready()) return;
        band = Math.max(0, Math.min(BAND_COUNT - 1, index));
        refresh();
    }

    private void adjustBand(int level) {
        if (!canAdjustBands() || level == gain[band]) return;
        remember();
        int[] target = gain.clone();
        target[band] = level;
        if (sound.send(1, band, level)) {
            activity.prefs().edit().remove(TONE_SETTINGS).apply();
            recordPreview(target, false);
            message = null;
        } else message = "The DSP did not accept the band change.";
        refresh();
    }

    private void showTone() {
        if (!canAdjustBands()) {
            notice("Bass and treble need an editable factory preset.");
            return;
        }
        ToneSettings saved = currentToneSettings();
        toneEditor = new ToneEditor(saved == null ? new ToneSettings(gain.clone(), 0, 0) : saved);
        toneEditor.dialog.show();
    }

    private void showSpeakerPosition() {
        if (!positionAvailable()) return;
        speakerPosition = new SpeakerPosition(activity, main, speakerField, () -> speakerPosition = null,
                (command, values) -> positionAvailable() && sound.send(command, values));
        speakerPosition.show();
    }

    private boolean positionAvailable() {
        return connected && module == 10 && speakerField.available();
    }

    private TactileSlider toneSlider(LinearLayout panel, String name, int level, IntConsumer changed) {
        TextView label = activity.managerText(name, 16, activity.colors.ink, 600);
        label.setPadding(activity.dp(4), activity.dp(9), 0, 0);
        panel.addView(label);
        TactileSlider slider = new TactileSlider(activity, true);
        slider.setMax(2 * TONE_RANGE);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar control, int progress, boolean user) {
                label.setText(name + "  ·  " + toneLevel(progress - TONE_RANGE));
                control.setContentDescription(name + ", " + toneLevel(progress - TONE_RANGE));
                if (user) changed.accept(progress - TONE_RANGE);
            }
            @Override public void onStartTrackingTouch(SeekBar control) { }
            @Override public void onStopTrackingTouch(SeekBar control) { }
        });
        slider.setProgress(level + TONE_RANGE);
        panel.addView(slider, new LinearLayout.LayoutParams(-1, activity.dp(58)));
        return slider;
    }

    private static String toneLevel(int level) {
        return level == 0 ? "0 dB" : String.format(Locale.US, "%+d dB", level);
    }

    private ToneSettings currentToneSettings() {
        if (!ownsPreview()) return null;
        ToneSettings settings = ToneSettings.decode(activity.prefs().getString(TONE_SETTINGS, ""));
        return settings != null && Arrays.equals(settings.levels(frequencies), gain) ? settings : null;
    }

    private final class ToneEditor {
        private final int activePreset = preset;
        private final int[] centers = frequencies.clone();
        private final boolean techno = ownsPreview()
                && "Techno".equals(activity.prefs().getString(PREVIEW_LABEL, ""));
        private final Screen.PanelDialog dialog = activity.new PanelDialog("Bass & treble", 760,
                "Adjust the tone while you listen. The sliders apply live.");
        private final int[] base;
        private final TactileSlider bassSlider;
        private final TactileSlider trebleSlider;
        private final Runnable send = this::sendChanges;
        private int[] requested = gain.clone();
        private int bass;
        private int treble;
        private boolean started;
        private boolean pending;

        ToneEditor(ToneSettings settings) {
            base = settings.base;
            bass = settings.bass;
            treble = settings.treble;
            bassSlider = toneSlider(dialog.body, "Bass", bass, value -> {
                bass = value;
                queueChanges();
            });
            trebleSlider = toneSlider(dialog.body, "Treble", treble, value -> {
                treble = value;
                queueChanges();
            });
            dialog.action("Reset tone", false, () -> {
                bass = 0;
                treble = 0;
                bassSlider.setProgress(TONE_RANGE);
                trebleSlider.setProgress(TONE_RANGE);
                queueChanges();
            });
            dialog.action("Done", true, this::close);
            dialog.setOnDismissListener(window -> {
                main.removeCallbacks(send);
                if (pending) sendChanges();
                if (toneEditor == this) toneEditor = null;
            });
        }

        void close() {
            main.removeCallbacks(send);
            if (pending) sendChanges();
            dialog.dismiss();
        }

        private void queueChanges() {
            if (pending) return;
            pending = true;
            main.postDelayed(send, 90);
        }

        private void sendChanges() {
            pending = false;
            boolean expectedCurve = started ? applyingGains || Arrays.equals(requested, gain)
                    : Arrays.equals(requested, gain) && canAdjustBands();
            if (!ready() || activePreset != preset || !Arrays.equals(centers, frequencies) || !expectedCurve) {
                notice("The EQ curve changed. Open Bass & treble again.");
                dialog.dismiss();
                return;
            }
            ToneSettings settings = new ToneSettings(base, bass, treble);
            int[] target = settings.levels(centers);
            if (Arrays.equals(target, requested)) return;
            if (!started) {
                remember();
                started = true;
            }
            int[] sent = requested.clone();
            for (int i = 0; i < BAND_COUNT; i++) {
                if (target[i] != requested[i] && sound.send(1, i, target[i])) sent[i] = target[i];
            }
            boolean complete = Arrays.equals(sent, target);
            if (!Arrays.equals(sent, requested)) {
                requested = sent;
                if (complete) activity.prefs().edit().putString(TONE_SETTINGS, settings.encode()).apply();
                else activity.prefs().edit().remove(TONE_SETTINGS).apply();
                recordPreview(sent, techno);
            }
            message = complete ? null : "Some tone changes could not be sent.";
            refresh();
        }
    }

    private static final class ToneSettings {
        final int[] base;
        final int bass;
        final int treble;

        ToneSettings(int[] base, int bass, int treble) {
            this.base = base;
            this.bass = bass;
            this.treble = treble;
        }

        int[] levels(int[] frequencies) {
            int[] target = base.clone();
            for (int i = 0; i < BAND_COUNT; i++) {
                int hz = frequencies[i];
                double low = Math.max(0, Math.min(1, Math.log(250.0 / hz) / Math.log(2.5)));
                double high = Math.max(0, Math.min(1, Math.log(hz / 2000.0) / Math.log(2.5)));
                int offset = (int) Math.round(bass * low + treble * high);
                target[i] = Math.max(0, Math.min(20, base[i] + offset));
            }
            return target;
        }

        String encode() {
            StringBuilder saved = new StringBuilder().append(bass).append(',').append(treble);
            for (int level : base) saved.append(',').append(level);
            return saved.toString();
        }

        static ToneSettings decode(String saved) {
            String[] fields = saved.split(",");
            if (fields.length != BAND_COUNT + 2) return null;
            try {
                int bass = Integer.parseInt(fields[0]);
                int treble = Integer.parseInt(fields[1]);
                if (Math.abs((long) bass) > TONE_RANGE || Math.abs((long) treble) > TONE_RANGE) return null;
                int[] base = new int[BAND_COUNT];
                for (int i = 0; i < BAND_COUNT; i++) {
                    base[i] = Integer.parseInt(fields[i + 2]);
                    if (base[i] < 0 || base[i] > 20) return null;
                }
                return new ToneSettings(base, bass, treble);
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
    }

    private void refresh() {
        boolean ready = ready();
        boolean techno = ownsPreview() && "Techno".equals(activity.prefs().getString(PREVIEW_LABEL, ""));
        boolean canSwitch = ready && idle() && (edited == 0 || ownsPreview());
        boolean canAdjust = canAdjustBands();
        tone.setEnabled(ready && idle());
        position.setEnabled(positionAvailable());
        restore.setEnabled(canSwitch && activity.prefs().getInt(PREVIOUS, -1) >= 0);
        String title = preset >= 0 && preset < PRESETS.length ? PRESETS[preset] : "Custom " + (preset - 8);
        if (!idle()) status.setText(applyingGains ? "Applying band changes…" : "Applying preset…");
        else if (message != null) status.setText(message);
        else if (!ready) status.setText(connected ? "Waiting for 27-band DSP readback…" : "Reading DSP settings…");
        else if (edited != 0 && !ownsPreview()) status.setText("Unsaved DSP edits · " + title);
        else if (techno) status.setText("Techno preview  ·  27-band DSP");
        else status.setText(title + (edited == 0 ? "" : " preview") + "  ·  27-band DSP");
        for (Button button : presets) {
            if (button == null) continue;
            int id = (int) button.getTag();
            boolean active = ready && (techno ? id == TECHNO : id == preset);
            if (button.isSelected() != active) {
                button.setSelected(active);
                activity.styleButton(button, activity.colors, false, true, active);
            }
            button.setEnabled(id == TECHNO ? idle() && editable() : canSwitch);
        }
        prior.setEnabled(ready && band > 0);
        next.setEnabled(ready && band < BAND_COUNT - 1);
        bandLevel.setEnabled(canAdjust);
        if (!dragging && gain[band] >= 0) bandLevel.setProgress(gain[band]);
        showBand(dragging ? bandLevel.getProgress() : gain[band]);
        curve.invalidate();
    }

    private void showBand(int value) {
        if (frequencies[band] > 0 && value >= 0) {
            bandLabel.setText(String.format(Locale.US, "%s  ·  %+d dB", frequency(frequencies[band]), value - 10));
        }
    }

    private static String frequency(int hz) {
        return hz < 1000 ? hz + " Hz" : String.format(Locale.US, "%.1f kHz", hz / 1000f);
    }

    private final class Curve extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Curve() {
            super(activity);
            setContentDescription("Equalizer band levels");
        }

        private float plotLeft() {
            return activity.dp(60);
        }

        private float plotRight() {
            return getWidth() - activity.dp(22);
        }

        private float position(int hz) {
            return plotLeft() + (plotRight() - plotLeft())
                    * (float) (Math.log(Math.max(20, Math.min(20000, hz)) / 20.0) / Math.log(1000));
        }

        @Override protected void onDraw(Canvas canvas) {
            float left = plotLeft();
            float right = plotRight();
            float top = activity.dp(12);
            float bottom = getHeight() - activity.dp(29);
            float height = bottom - top;
            paint.setColor(activity.colors.muted);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            paint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
            String[] units = {"+10 dB", "0", "−10"};
            for (int i = 0; i < units.length; i++) {
                float y = top + height * i / 2;
                paint.setColor(activity.colors.outline);
                paint.setAlpha(i == 1 ? 235 : 115);
                paint.setStrokeWidth(activity.dp(i == 1 ? 2 : 1));
                canvas.drawLine(left, y, right, y, paint);
                paint.setColor(activity.colors.muted);
                canvas.drawText(units[i], left - activity.dp(9) - paint.measureText(units[i]),
                        Math.max(-paint.ascent(), y - (paint.ascent() + paint.descent()) / 2), paint);
            }
            int[] ticks = {20, 100, 1000, 10000, 20000};
            String[] labels = {"20 Hz", "100", "1 kHz", "10 kHz", "20 kHz"};
            float nextLabel = left;
            float lastLabel = right - paint.measureText(labels[labels.length - 1]);
            for (int i = 0; i < ticks.length; i++) {
                float x = position(ticks[i]);
                paint.setColor(activity.colors.outline);
                paint.setAlpha(95);
                paint.setStrokeWidth(activity.dp(1));
                canvas.drawLine(x, top, x, bottom, paint);
                paint.setColor(activity.colors.muted);
                float labelWidth = paint.measureText(labels[i]);
                float textX = Math.min(right - labelWidth, Math.max(left, x - labelWidth / 2));
                if (i == ticks.length - 1 || (textX >= nextLabel && textX + labelWidth + activity.dp(8) <= lastLabel)) {
                    canvas.drawText(labels[i], textX, getHeight() - activity.dp(5), paint);
                    nextLabel = textX + labelWidth + activity.dp(8);
                }
            }
            if (!ready()) return;

            float first = position(frequencies[0]);
            float last = position(frequencies[BAND_COUNT - 1]);
            path.reset();
            for (int index = 0; index < BAND_COUNT; index++) {
                float x = position(frequencies[index]);
                float y = top + height * (20 - gain[index]) / 20;
                if (index == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            Path fill = new Path(path);
            fill.lineTo(last, bottom);
            fill.lineTo(first, bottom);
            fill.close();
            int accent = activity.colors.accent;
            paint.setShader(new LinearGradient(0, top, 0, bottom, (accent & 0xffffff) | 0x48000000,
                    accent & 0xffffff, Shader.TileMode.CLAMP));
            canvas.drawPath(fill, paint);
            paint.setShader(null);
            paint.setColor(accent);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(activity.dp(3));
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            float x = position(frequencies[band]);
            float y = top + height * (20 - gain[band]) / 20;
            paint.setAlpha(110);
            paint.setStrokeWidth(activity.dp(1));
            canvas.drawLine(x, top, x, bottom, paint);
            paint.setAlpha(255);
            canvas.drawCircle(x, y, activity.dp(8), paint);
            paint.setColor(activity.colors.onAccent);
            canvas.drawCircle(x, y, activity.dp(3), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!ready()) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int nearest = 0;
                for (int index = 1; index < BAND_COUNT; index++) {
                    if (Math.abs(position(frequencies[index]) - event.getX())
                            < Math.abs(position(frequencies[nearest]) - event.getX())) nearest = index;
                }
                selectBand(nearest);
                performClick();
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
