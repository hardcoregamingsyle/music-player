"""Headless tray music player.

Drop this script (or the built .exe) into a folder full of .mp3 files and run it.
It shuffles through every .mp3 in its own folder, playing one fully before picking
the next at random. Runs with no window — just a system tray icon.

Also runs a tiny LAN server so a phone on the same Wi-Fi can discover this
machine and pull down any .mp3 it doesn't have yet (see android/ for the client).
"""

import ctypes
import json
import os
import random
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

import pystray
from PIL import Image, ImageDraw

APP_NAME = "Music Player"
HTTP_PORT = 8721
BROADCAST_PORT = 8722
BROADCAST_INTERVAL_SEC = 3

# --- MP3 playback via the Windows MCI API (winmm.dll) -----------------------
# Every real .mp3 gets decoded to a temp .wav via ffmpeg first, then played
# through MCI's "waveaudio" device. Two reasons, found by testing against
# actual downloaded songs (not just synthetic test tones):
#   1. MCI's "mpegvideo" device (which can open mp3s directly) chokes on
#      files with an embedded cover-art picture in their ID3v2 tag -- which
#      is most real-world mp3s -- with "MCI error 277: initializing MCI".
#   2. "mpegvideo" is a *video*-capable device class, so it can pop up a
#      visible player window even for audio-only files. "waveaudio" is pure
#      audio with no on-screen surface at all, so nothing ever appears.
# ffmpeg must be installed and on PATH (it already is on this machine).

_winmm = ctypes.WinDLL("winmm")
_winmm.mciSendStringW.restype = ctypes.c_uint32
_winmm.mciSendStringW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_void_p]
_winmm.mciGetErrorStringW.restype = ctypes.c_bool
_winmm.mciGetErrorStringW.argtypes = [ctypes.c_uint32, ctypes.c_wchar_p, ctypes.c_uint32]

_TEMP_DIR = tempfile.gettempdir()
_TEMP_WAV_PREFIX = "musicplayer_decode_"


def _temp_wav_path(n):
    return os.path.join(_TEMP_DIR, f"{_TEMP_WAV_PREFIX}{n}.wav")


def cleanup_stale_temp_wavs():
    """Best-effort: clear out decode files left behind by a previous run (a
    crash, a force-kill, Task Manager). Safe to do at startup -- that process
    is gone, so nothing can still be holding them open."""
    try:
        for name in os.listdir(_TEMP_DIR):
            if name.startswith(_TEMP_WAV_PREFIX) and name.endswith(".wav"):
                try:
                    os.remove(os.path.join(_TEMP_DIR, name))
                except OSError:
                    pass
    except OSError:
        pass


def _mci(command):
    buf = ctypes.create_unicode_buffer(256)
    err = _winmm.mciSendStringW(command, buf, len(buf), None)
    if err:
        errbuf = ctypes.create_unicode_buffer(256)
        _winmm.mciGetErrorStringW(err, errbuf, len(errbuf))
        raise RuntimeError(f"MCI error {err} ({errbuf.value}) for: {command}")
    return buf.value


def _decode_to_wav(mp3_path, wav_path):
    try:
        result = subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", mp3_path, wav_path],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
    except FileNotFoundError:
        raise RuntimeError("ffmpeg not found on PATH -- required to decode mp3s") from None
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg failed on {mp3_path}: {result.stderr.decode(errors='replace')[:300]}")


class McuMp3Player:
    """Thin wrapper around one MCI waveaudio device instance.

    Each track decodes to its OWN temp .wav filename (not a shared/reused
    one). Reusing a single filename meant that if MCI's "close" command ever
    failed to release its file handle (observed in the wild -- rare, but it
    happens), that filename stayed locked for the rest of the process's
    life, and every track after that point failed to decode forever. Unique
    filenames make that failure mode impossible; a small trailing history is
    still cleaned up so temp files don't accumulate.
    """

    def __init__(self):
        self._alias = None
        self._counter = 0
        self._wav_history = []

    def load_and_play(self, path):
        self.stop()
        self._counter += 1
        wav_path = _temp_wav_path(self._counter)
        _decode_to_wav(path, wav_path)
        alias = f"track{self._counter}"
        _mci(f'open "{wav_path}" type waveaudio alias {alias}')
        _mci(f"play {alias}")
        self._alias = alias

        self._wav_history.append(wav_path)
        while len(self._wav_history) > 2:
            stale = self._wav_history.pop(0)
            try:
                os.remove(stale)
            except OSError:
                pass  # still locked (e.g. a leaked handle) -- harmless, skip it

    def is_busy(self):
        if not self._alias:
            return False
        try:
            return _mci(f"status {self._alias} mode") == "playing"
        except RuntimeError:
            return False

    def pause(self):
        if self._alias:
            _mci(f"pause {self._alias}")

    def resume(self):
        if self._alias:
            _mci(f"play {self._alias}")

    def stop(self):
        if self._alias:
            try:
                _mci(f"stop {self._alias}")
                _mci(f"close {self._alias}")
            except RuntimeError:
                pass
            self._alias = None


def music_dir():
    """Folder to scan for .mp3 files: wherever the exe/script currently lives."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def list_mp3s(folder):
    try:
        names = sorted(f for f in os.listdir(folder) if f.lower().endswith(".mp3"))
    except OSError:
        names = []
    return names


def log_error(folder, message):
    """Playback errors used to fail silently (no console window to see them
    in). Write them next to the mp3s instead, so they're actually visible."""
    try:
        with open(os.path.join(folder, "player_error.log"), "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}\n")
    except OSError:
        pass


class PlayerState:
    def __init__(self, folder):
        self.folder = folder
        self.player = McuMp3Player()
        self.lock = threading.Lock()
        self.current = None
        self.paused = False
        self.stop_event = threading.Event()
        self.skip_event = threading.Event()
        self.last_played = None

    def status_text(self):
        with self.lock:
            if self.current is None:
                return "Not playing"
            state = "Paused" if self.paused else "Playing"
            return f"{state}: {self.current}"


def pick_next(folder, last_played):
    files = list_mp3s(folder)
    if not files:
        return None
    if len(files) > 1 and last_played in files:
        choices = [f for f in files if f != last_played]
    else:
        choices = files
    return random.choice(choices)


def playback_loop(state):
    while not state.stop_event.is_set():
        track = pick_next(state.folder, state.last_played)
        if track is None:
            time.sleep(2)
            continue

        with state.lock:
            state.current = track
            state.paused = False
        state.last_played = track
        state.skip_event.clear()

        try:
            state.player.load_and_play(os.path.join(state.folder, track))
        except RuntimeError as e:
            log_error(state.folder, f"Failed to play '{track}': {e}")
            time.sleep(1)
            continue

        while state.player.is_busy() or state.paused:
            if state.stop_event.is_set():
                state.player.stop()
                return
            if state.skip_event.is_set():
                state.player.stop()
                break
            time.sleep(0.25)

    state.player.stop()


def toggle_pause(state):
    with state.lock:
        if state.current is None:
            return
        if state.paused:
            state.player.resume()
            state.paused = False
        else:
            state.player.pause()
            state.paused = True


def skip_track(state):
    state.skip_event.set()


def make_icon_image():
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((2, 2, size - 2, size - 2), fill=(30, 30, 30, 255))
    d.ellipse((22, 14, 34, 26), fill=(240, 240, 240, 255))
    d.line((33, 20, 33, 46), fill=(240, 240, 240, 255), width=4)
    d.line((33, 20, 46, 16), fill=(240, 240, 240, 255), width=4)
    d.ellipse((26, 42, 40, 54), fill=(240, 240, 240, 255))
    d.ellipse((39, 38, 53, 50), fill=(240, 240, 240, 255))
    return img


def build_tray(state):
    def on_toggle(icon, item):
        toggle_pause(state)

    def on_skip(icon, item):
        skip_track(state)

    def on_exit(icon, item):
        state.stop_event.set()
        icon.stop()

    def sync_text():
        if SYNC_INFO["error"]:
            return f"Sync ERROR: {SYNC_INFO['error']}"
        ips = ", ".join(SYNC_INFO["ips"]) or "no network"
        return f"Sync: listening on {ips}:{HTTP_PORT}"

    def contact_text():
        last = SYNC_INFO["last_contact"]
        return f"Phone last seen: {last}" if last else "Phone last seen: never"

    def menu_items():
        yield pystray.MenuItem(state.status_text(), None, enabled=False)
        yield pystray.MenuItem(sync_text(), None, enabled=False)
        yield pystray.MenuItem(contact_text(), None, enabled=False)
        yield pystray.Menu.SEPARATOR
        yield pystray.MenuItem("Pause / Resume", on_toggle, default=True)
        yield pystray.MenuItem("Skip", on_skip)
        yield pystray.Menu.SEPARATOR
        yield pystray.MenuItem("Exit", on_exit)

    icon = pystray.Icon(APP_NAME, make_icon_image(), APP_NAME, pystray.Menu(menu_items))
    return icon


def refresh_tray_periodically(icon, state):
    while not state.stop_event.is_set():
        icon.update_menu()
        time.sleep(1)


# Shared with the tray menu so the laptop side can show whether sync is
# actually reachable, instead of failing invisibly.
SYNC_INFO = {"ips": [], "error": None, "last_contact": None}


def sync_log(folder, message):
    try:
        path = os.path.join(folder, "sync.log")
        if os.path.exists(path) and os.path.getsize(path) > 200_000:
            os.remove(path)
        with open(path, "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}\n")
    except OSError:
        pass


class SyncRequestHandler(BaseHTTPRequestHandler):
    folder = None  # set by make_handler

    def log_message(self, fmt, *args):
        pass  # keep this silent/headless

    def log_request(self, code="-", size="-"):
        client = self.client_address[0]
        SYNC_INFO["last_contact"] = f"{time.strftime('%H:%M:%S')} from {client}"
        sync_log(self.folder, f"{client} {self.command} {self.path} -> {code}")

    def do_GET(self):
        if self.path == "/manifest":
            files = list_mp3s(self.folder)
            manifest = []
            for name in files:
                try:
                    st = os.stat(os.path.join(self.folder, name))
                    manifest.append({"name": name, "size": st.st_size, "mtime": int(st.st_mtime)})
                except OSError:
                    continue
            body = json.dumps(manifest).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path.startswith("/file/"):
            name = unquote(self.path[len("/file/"):])
            safe_name = os.path.basename(name)
            if safe_name != name or safe_name not in list_mp3s(self.folder):
                self.send_response(404)
                self.end_headers()
                return
            path = os.path.join(self.folder, safe_name)
            try:
                with open(path, "rb") as fh:
                    data = fh.read()
            except OSError:
                self.send_response(404)
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_response(404)
            self.end_headers()


def make_handler(folder):
    class Handler(SyncRequestHandler):
        pass

    Handler.folder = folder
    return Handler


class _SyncServer(ThreadingHTTPServer):
    # On Windows, SO_REUSEADDR lets a second instance bind the same port and
    # silently shadow the first (a stale copy then answers the phone with an old
    # song list). Exclusive bind makes the second one fail loudly instead.
    allow_reuse_address = False

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def run_sync_server(folder, stop_event):
    while not stop_event.is_set():
        try:
            server = _SyncServer(("0.0.0.0", HTTP_PORT), make_handler(folder))
        except OSError as e:
            SYNC_INFO["error"] = f"can't listen on port {HTTP_PORT}"
            log_error(folder, f"Sync server could not bind port {HTTP_PORT}: {e}")
            stop_event.wait(5)
            continue
        SYNC_INFO["error"] = None
        server.timeout = 1
        try:
            while not stop_event.is_set():
                server.handle_request()
        except Exception as e:
            log_error(folder, f"Sync server stopped unexpectedly: {e}")
        finally:
            server.server_close()


def _local_ipv4s():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith(("127.", "169.254.")):
                ips.add(ip)
    except OSError:
        pass
    return ips


def _broadcast_once(message):
    """255.255.255.255 on its own only leaves via ONE network adapter (whichever
    Windows prefers), which is often not the one the phone is on. Send from
    every local address, to both the limited and the /24 directed broadcast."""
    targets = [(None, "255.255.255.255")]
    for ip in _local_ipv4s():
        targets.append((ip, "255.255.255.255"))
        targets.append((ip, ip.rsplit(".", 1)[0] + ".255"))
    for src, dst in targets:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            if src:
                s.bind((src, 0))
            s.sendto(message, (dst, BROADCAST_PORT))
        except OSError:
            pass
        finally:
            s.close()


def run_broadcaster(stop_event):
    hostname = socket.gethostname()
    message = f"MUSICSYNC:{HTTP_PORT}:{hostname}".encode("utf-8")
    while not stop_event.is_set():
        SYNC_INFO["ips"] = sorted(_local_ipv4s())
        _broadcast_once(message)
        stop_event.wait(BROADCAST_INTERVAL_SEC)


_single_instance_handle = None  # kept alive for the life of the process


def already_running():
    """A second copy would fight the first over the audio device and the sync
    port (this caused real trouble with stale copies left behind)."""
    global _single_instance_handle
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    kernel32.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
    _single_instance_handle = kernel32.CreateMutexW(None, False, "MusicPlayerTrayApp_single_instance")
    return ctypes.get_last_error() == 183  # ERROR_ALREADY_EXISTS


def main():
    if already_running():
        return
    cleanup_stale_temp_wavs()
    folder = music_dir()
    state = PlayerState(folder)

    threading.Thread(target=playback_loop, args=(state,), daemon=True).start()
    threading.Thread(target=run_sync_server, args=(folder, state.stop_event), daemon=True).start()
    threading.Thread(target=run_broadcaster, args=(state.stop_event,), daemon=True).start()

    icon = build_tray(state)
    threading.Thread(target=refresh_tray_periodically, args=(icon, state), daemon=True).start()
    icon.run()


if __name__ == "__main__":
    main()
