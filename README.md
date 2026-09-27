# Music Player

A bare-bones personal music player: shuffles through `.mp3` files, never
switching until the current one finishes. Desktop half runs on Windows as a
tray app; the Android half plays in the background (screen off/locked) and
pulls new tracks from the laptop automatically over Wi-Fi.

Personal use only — not for distribution.

## Desktop (Windows)

`desktop/player.py` scans **its own folder** for `.mp3` files and shuffle-plays
them, with a tray icon (Pause/Resume, Skip, Exit). It also runs a tiny local
server so the phone can find it and sync.

**Run from source:**
```powershell
pip install -r desktop/requirements.txt
python desktop/player.py
```

**Build the standalone `.exe`:**
```powershell
desktop/build_exe.ps1
```
This produces `desktop/dist/MusicPlayer.exe`. Copy that exe into whatever
folder holds your `.mp3` files and run it from there — it only looks at the
folder it's sitting in.

Playback: each mp3 is decoded to a temp `.wav` via **ffmpeg** (must be
installed and on `PATH`), then played through the Windows `winmm` MCI
`waveaudio` device via `ctypes` — no Python audio library needed. This exists
because MCI can technically open mp3s directly via its `mpegvideo` device,
but that fails on most real-world mp3s (anything with embedded cover art)
and can pop up a visible player window since it's a video-capable device;
`waveaudio` is pure audio with neither problem.

If a track fails to play, check `player_error.log` next to your mp3s (there's
no console window to print errors to).

## Android

The APK is built by GitHub Actions (see `.github/workflows/android-build.yml`)
since Android's SDK/JDK/Gradle toolchain isn't installed locally. After a push
to `main`, grab the latest APK from this repo's **Releases** page (tag
`latest`) on your phone's browser and install it (enable "install unknown
apps" for your browser the first time).

App behavior:
- **Start playback**: begins shuffle-playing whatever's in the app's synced
  folder (see Sync below), plus an optional extra folder you pick.
- Plays through a foreground service with lock-screen media controls
  (play/pause/skip) — keeps going with the screen off or locked. No manual
  wake lock is held; Android's audio pipeline keeps the CPU awake only while a
  track is actually rendering, which keeps battery use to a minimum.
- **Pick additional folder**: one-time folder picker (Storage Access
  Framework) for mp3s already on the phone you want included.
- **Sync now**: manually pull anything new from the laptop immediately,
  instead of waiting for the automatic check.

## Sync (laptop → phone)

Custom, local-network only — no cloud account or third-party service:

1. The desktop app broadcasts a small UDP "I'm here" packet on the LAN every
   few seconds and serves a file manifest + the mp3s themselves over HTTP.
2. The phone (on the same Wi-Fi) listens for that broadcast in a low-frequency
   background job (`WorkManager`, checks roughly every 15 minutes, Wi-Fi
   only — never touches mobile data), and downloads anything missing or
   changed into its own app folder.
3. If the laptop app isn't running, or the phone isn't on the same network,
   the check is a no-op and retries next cycle.

Both devices being "online" for sync purposes means: same Wi-Fi network, and
the desktop app is running.

## Repo layout

```
desktop/    Windows tray player (Python) + LAN sync server
android/    Android app (Kotlin) + sync client
.github/    CI workflow that builds the APK
```
