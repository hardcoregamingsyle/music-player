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
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

import pystray
from PIL import Image, ImageDraw

APP_NAME = "Music Player"
HTTP_PORT = 8721
BROADCAST_PORT = 8722
BROADCAST_INTERVAL_SEC = 5

# --- MP3 playback via the Windows MCI API (winmm.dll) -----------------------
# No third-party audio library needed: MCI's "mpegvideo" compound device
# handles mp3 decoding natively on stock Windows, so this has zero extra
# dependencies and nothing to compile.

_winmm = ctypes.WinDLL("winmm")
_winmm.mciSendStringW.restype = ctypes.c_uint32
_winmm.mciSendStringW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_void_p]
_winmm.mciGetErrorStringW.restype = ctypes.c_bool
_winmm.mciGetErrorStringW.argtypes = [ctypes.c_uint32, ctypes.c_wchar_p, ctypes.c_uint32]


def _mci(command):
    buf = ctypes.create_unicode_buffer(256)
    err = _winmm.mciSendStringW(command, buf, len(buf), None)
    if err:
        errbuf = ctypes.create_unicode_buffer(256)
        _winmm.mciGetErrorStringW(err, errbuf, len(errbuf))
        raise RuntimeError(f"MCI error {err} ({errbuf.value}) for: {command}")
    return buf.value


class McuMp3Player:
    """Thin wrapper around one MCI mp3 device instance."""

    def __init__(self):
        self._alias = None
        self._counter = 0

    def load_and_play(self, path):
        self.stop()
        self._counter += 1
        alias = f"track{self._counter}"
        _mci(f'open "{path}" type mpegvideo alias {alias}')
        _mci(f"play {alias}")
        self._alias = alias

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
        except RuntimeError:
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

    def menu_items():
        yield pystray.MenuItem(state.status_text(), None, enabled=False)
        yield pystray.Menu.SEPARATOR
        yield pystray.MenuItem("Pause / Resume", on_toggle)
        yield pystray.MenuItem("Skip", on_skip)
        yield pystray.Menu.SEPARATOR
        yield pystray.MenuItem("Exit", on_exit)

    icon = pystray.Icon(APP_NAME, make_icon_image(), APP_NAME, pystray.Menu(menu_items))
    return icon


def refresh_tray_periodically(icon, state):
    while not state.stop_event.is_set():
        icon.update_menu()
        time.sleep(1)


class SyncRequestHandler(BaseHTTPRequestHandler):
    folder = None  # set by make_handler

    def log_message(self, fmt, *args):
        pass  # keep this silent/headless

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


def run_sync_server(folder, stop_event):
    server = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), make_handler(folder))
    server.timeout = 1
    while not stop_event.is_set():
        server.handle_request()
    server.server_close()


def run_broadcaster(stop_event):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    hostname = socket.gethostname()
    message = f"MUSICSYNC:{HTTP_PORT}:{hostname}".encode("utf-8")
    while not stop_event.is_set():
        try:
            sock.sendto(message, ("255.255.255.255", BROADCAST_PORT))
        except OSError:
            pass
        stop_event.wait(BROADCAST_INTERVAL_SEC)
    sock.close()


def main():
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
