# Entertainment

Open **Music** or **Video** in Entertainment using the labelled tabs. To open either library directly from Home, tap an empty **+** tile or open **Appearance → Choose Home shortcuts**. On the **AVC Manager** tab choose **Music** or **Video**, use **Arrange** to pick its position, then **Save**. In the built-in Import browser, **Back** returns to the previous folder. At the storage list it returns to Entertainment.


The 0.5.1 candidate provides an on-device music and video library. Open **Entertainment** in the Manager rail. Music and videos stay available without mobile reception; playback does not contact a streaming service.

## Add files from a laptop

1. Connect the laptop and CC3 to the same trusted Wi-Fi, or connect the laptop to the CC3 hotspot.
2. On the CC3 choose **Entertainment → Upload → Start uploads**.
3. Open the HTTPS address shown on the CC3. Before trusting the certificate or entering a pairing code, compare the browser's SHA-256 certificate fingerprint with **Verify secure connection** on the CC3. If your browser cannot display certificate details, use the pinned uploader described below.
4. Choose or drop audio/video files, then choose **Upload to CC3**. Files transfer one at a time, with progress and individual results. Each completed file appears in the CC3 library automatically.
5. Choose **Stop uploads** on the CC3 when finished. A persistent notification also has a Stop action. Uploads automatically stop after ten minutes idle or two hours total. The next session uses a fresh pairing code.

No ADB command, cloud account or mobile-data connection is required. The transfer listener binds a private Wi-Fi/hotspot IPv4 address; it never deliberately binds the cellular interface or all interfaces. It accepts clients from that interface's subnet. Some guest networks isolate clients and prevent a laptop from reaching the CC3; use a shared network that permits local connections.

The local transfer uses TLS 1.2/1.3 with an AndroidKeyStore P-256 identity, forward-secret AEAD suites, a Secure/HttpOnly cookie and explicit certificate verification. A private self-signed certificate is expected: **compare its full fingerprint before trusting it**, rather than bypassing a warning. The displayed fingerprint can change after an IP change; read the current value on the head unit. There are no media-download or arbitrary filesystem endpoints.

For a laptop client that verifies the certificate automatically, run the standard-library Python 3 uploader:

```sh
python3 tools/cc3-upload.py --url https://192.0.2.10:8765 --fingerprint 'FULL SHA-256 FROM THE CC3' music.mp3 video.mp4
```

Use the address actually shown on your CC3. The script prompts privately for the eight-digit pairing code. It compares the certificate pin before sending any HTTP request, credentials or file bytes; a mismatch stops the connection. It does not follow redirects or require a cloud account.

The server has absolute three-second TLS/header/pairing deadlines, per-peer pending-connection limits and reserved worker capacity for already-paired peer addresses. Authentication and CSRF checks still apply on each upload. A paired transfer retains bounded streaming and inactivity/session limits.

Uploads stream directly to hidden staging files with a 64 KiB buffer. Interrupted transfers are removed and never enter the playable library. Completed files are committed atomically. Existing filenames are preserved; duplicate files receive names such as `Track (2).mp3`. The receiver limits files to16 GiB and leaves512 MiB free. A cancelled file can be added again to retry; partial uploads are not resumable.

## Files already on the CC3

**Find media** requests access to existing on-device audio/video listed in Android's media database. **Import** opens Android's local document picker and copies selected files into the managed library. USB media availability depends on how the firmware exposes it to Android. Import is limited to100 selections per batch. When the factory document picker is missing, AVC opens its own picker for readable mounted volumes and indexed media. FAT32 is a practical first choice for USB drives, with each file smaller than 4 GiB. Recognition of exFAT and NTFS depends on the firmware. TEYES specifies FAT32 for CC3 update media. [CC3 USB guidance](https://www.teyes.com.au/pages/cc3-nov-2025-firmware-20-11-2025)

Uploaded/imported files live in `getExternalFilesDir("entertainment")`, with an internal app-storage fallback. They survive app updates. **Uninstalling AVC Manager removes this managed library**, so keep originals on the laptop. Existing device media is never moved, overwritten or deleted.

## Playback

- Music: play/pause, seek, previous/next, shuffle, repeat off/all/one, Stop, searchable library and folder filtering. The filtered list becomes a queue of up to1,000 files around the chosen track.
- Music continues across Manager sections using a foreground media service, MediaSession, audio focus, notification controls and a playback wake lock. It pauses when an audio output becomes noisy or another source takes over. Paused services stop after an idle timeout; playback is not automatically restarted at boot.
- Video: an integrated, aspect-preserving surface with seek and transport controls. Leaving the video screen pauses it. Video is intended for use while parked. This build does **not** claim an independently verified speed/gear interlock.
- FYT source switching reuses the existing main/source0 and DSPmodule10 integration for normal Android media. It does not change DSP presets. Physical speaker output and source switching need in-car acceptance for this new player.

Audio library extensions: MP3, M4A, AAC, FLAC, WAV, OGG, OGA, OPUS and AMR. Video library extensions: MP4, M4V, MKV, WEBM, 3GP, 3GPP, TS and M2TS. A container/extension does not guarantee its internal codecs are supported. The player uses Android MediaPlayer and reports an error for unsupported or damaged media; it does not auto-skip silently or download decoder packs. See [Android's supported media formats](https://developer.android.com/media/platform/supported-formats).

## Home playback shortcut —0.4.3

While Entertainment has a current music item, each Home layout shows its title, artist when available, and **Now playing**, **Paused**, **Opening…** or **Playback problem**. Tap this shortcut to open Entertainment's **Music** tab, even if Video was previously being browsed. Playback continues during navigation. **Stop** removes the shortcut. This uses the existing local player state; it does not request notification access or monitor third-party players. The right-menu **AVC Manager** title opens the selected CC3 Home layout without changing Android's default launcher.

## USB discovery in the current implementation

Entertainment scans its managed CC3 library and, after media permission is granted, Android's external audio/video MediaStore collections. Open Entertainment or tap **Refresh** after inserting a drive. Files indexed by the head unit can then play directly from USB. Keep the drive connected during playback. To keep a local copy, use **Import**, which lets you browse storage exposed by the head unit. Physical USB insertion and removal still need a test on this CC3.


## Current-row visibility and full-screen video — 0.5.4

When the playing item changes through Next, Previous or automatic advance, the library brings its whole highlighted row into view. A fully visible row stays in place. Search and folder filters remain intact; if the playing file is outside the filtered results, the list is left alone. Regular playback ticks do not reset a user’s browsing position.

The expand icon in the Video picture enters **Full screen**. It enlarges the existing playback surface and hides the library, page header, section tabs and Manager menu. The transport overlay offers Previous, Play/Pause, Next and **Exit full screen**. Controls hide after three seconds of playback; tapping the picture shows them, and paused controls stay visible. Back exits full screen first and returns to the library at the same video position. Leaving the activity still pauses video. Aspect ratio is retained; the space available for video depends on the head unit’s system bars. This uses Android’s immersive display flags; physical FYT bar behaviour still needs device verification.
