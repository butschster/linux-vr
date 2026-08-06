#!/usr/bin/env python3
"""Turns speech from the headset into text at the cursor.

Receives raw PCM, transcribes it with a self-hosted Whisper behind an
OpenAI-compatible gateway, and inserts the result wherever the focus is.

Why the clipboard rather than typing the text out:

`uinput` works in scancodes tied to the keyboard layout. Cyrillic, punctuation,
anything non-ASCII becomes garbage or depends on which layout happens to be
active. `wtype` does not help either — it needs the `zwp_virtual_keyboard`
protocol, which mutter does not implement, so on GNOME Wayland that is a dead
end. The clipboard depends on neither: `wl-copy` carries finished UTF-8 and
Ctrl+V is two scancodes that mean the same thing in any layout.

The previous clipboard contents are saved and put back. Without that, dictation
silently destroys whatever the user copied a minute ago — maddening, and not
obvious to diagnose.

Configuration comes from the environment; the key never enters a config file or
this repository:

    LINUXVR_ASR_URL    http://<gateway>:4000/v1/audio/transcriptions
    LINUXVR_ASR_KEY    the gateway key
    LINUXVR_ASR_MODEL  whisper-large-v3-turbo

Wire protocol, one connection per utterance or per typed string:

    "pcm <rate> <channels>\\n" + raw signed 16-bit samples
        transcribe and insert at the cursor, then return the text so the client
        can show what was heard. Insertion is direct on purpose: a shell line or
        a text field is itself the place to fix a misheard word, and a review
        step in between turns two seconds of dictation into four.

    "text\\n" + UTF-8
        insert as is — this is the on-screen keyboard path

    "key <name>\\n"
        press a key or a chord: enter, tab, esc, backspace, delete, home, end,
        arrows, and combinations such as ctrl+c or shift+tab. Modifiers come
        first, separated by "+".
        Dictation inserts text but cannot submit it, so without this a terminal
        is unusable from the headset.

Both end when the client half-closes. Voice and keyboard converge here on
purpose: both produce a string, and inserting a string is the part that is
awkward on Wayland, so it is written once.

Testing without a headset:

    ./voice-agent.py --file sample.wav      transcribe and paste a file
    ./voice-agent.py                        serve on :9102
"""

import argparse
import io
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import wave

DEFAULT_PORT = 9102

# Whisper resamples everything to 16 kHz mono internally, so recording at that
# rate on the headset is lossless for recognition and keeps the upload small.
EXPECTED_RATE = 16000


# ------------------------------------------------------------------ keyboard

EV_KEY, EV_SYN = 0x01, 0x00
SYN_REPORT = 0x00
KEY_LEFTCTRL, KEY_V = 29, 47

# Keys the headset has no other way to press. Dictation inserts text but cannot
# submit it, which makes a terminal unusable from VR.
NAMED_KEYS = {
    "enter": 28,
    "tab": 15,
    "esc": 1,
    "backspace": 14,
    "up": 103,
    "down": 108,
    "left": 105,
    "right": 106,
    "home": 102,
    "end": 107,
    "delete": 111,
    "space": 57,
    "l": 38,
    "c": 46,
    "d": 32,
    "r": 19,
    "z": 44,
}

# Modifiers, for chords. Esc, Ctrl+C and Shift+Tab are the actual controls of a
# terminal session — without them the headset can type but cannot interrupt,
# complete or switch modes.
MODIFIERS = {
    "ctrl": 29,
    "shift": 42,
    "alt": 56,
}


def _iow(nr: int, size: int) -> int:
    return (1 << 30) | (size << 16) | (ord("U") << 8) | nr


def _io(nr: int) -> int:
    return (ord("U") << 8) | nr


UI_DEV_CREATE = _io(1)
UI_DEV_DESTROY = _io(2)
UI_DEV_SETUP = _iow(3, 92)
UI_SET_EVBIT = _iow(100, 4)
UI_SET_KEYBIT = _iow(101, 4)


class VirtualKeyboard:
    """Just enough of a keyboard to press Ctrl+V."""

    def __init__(self, name: str = "linux-vr keyboard"):
        import fcntl
        self._fcntl = fcntl
        try:
            self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        except PermissionError:
            sys.exit("cannot open /dev/uinput; add yourself to the `input` group")

        # These ioctls take the bit as an immediate value, not a pointer to one.
        fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
        fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_SYN)
        for key in {KEY_LEFTCTRL, KEY_V, *NAMED_KEYS.values(), *MODIFIERS.values()}:
            fcntl.ioctl(self.fd, UI_SET_KEYBIT, key)

        setup = struct.pack("HHHH80sI", 0x03, 0x1234, 0x5679, 1, name.encode()[:79], 0)
        fcntl.ioctl(self.fd, UI_DEV_SETUP, setup)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)
        time.sleep(0.3)   # udev needs a moment or the first keys go nowhere

    def _emit(self, ev_type: int, code: int, value: int) -> None:
        os.write(self.fd, struct.pack("llHHi", 0, 0, ev_type, code, value))

    def paste(self) -> None:
        self._emit(EV_KEY, KEY_LEFTCTRL, 1)
        self._emit(EV_KEY, KEY_V, 1)
        self._emit(EV_SYN, SYN_REPORT, 0)
        time.sleep(0.02)
        self._emit(EV_KEY, KEY_V, 0)
        self._emit(EV_KEY, KEY_LEFTCTRL, 0)
        self._emit(EV_SYN, SYN_REPORT, 0)

    def press(self, code: int, modifiers=()) -> None:
        for mod in modifiers:
            self._emit(EV_KEY, mod, 1)
        self._emit(EV_KEY, code, 1)
        self._emit(EV_SYN, SYN_REPORT, 0)
        time.sleep(0.02)
        self._emit(EV_KEY, code, 0)
        # Released in reverse, the way a hand would let go.
        for mod in reversed(list(modifiers)):
            self._emit(EV_KEY, mod, 0)
        self._emit(EV_SYN, SYN_REPORT, 0)

    def close(self) -> None:
        try:
            self._fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        finally:
            os.close(self.fd)


# ----------------------------------------------------------------- clipboard

def clipboard_get() -> bytes:
    try:
        return subprocess.run(["wl-paste", "--no-newline"],
                              capture_output=True, timeout=5).stdout
    except Exception:
        return b""


def clipboard_set(data: bytes) -> None:
    try:
        subprocess.run(["wl-copy"], input=data, timeout=5)
    except Exception:
        pass


def insert_text(keyboard: VirtualKeyboard, text: str) -> None:
    saved = clipboard_get()
    clipboard_set(text.encode())
    # The compositor needs a moment to notice the new selection; pasting
    # immediately can insert the previous contents.
    time.sleep(0.1)
    keyboard.paste()
    time.sleep(0.2)
    clipboard_set(saved)


# --------------------------------------------------------------- recognition

def pcm_to_wav(pcm: bytes, rate: int, channels: int) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return buf.getvalue()


def transcribe(wav: bytes) -> str:
    url = os.environ.get("LINUXVR_ASR_URL", "")
    key = os.environ.get("LINUXVR_ASR_KEY", "")
    model = os.environ.get("LINUXVR_ASR_MODEL", "whisper-large-v3-turbo")
    if not url:
        raise RuntimeError("LINUXVR_ASR_URL is not set")

    boundary = uuid.uuid4().hex
    parts = []
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                 f"{model}\r\n".encode())
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                 f"filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".encode())
    parts.append(wav)
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    body = b"".join(parts)

    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    if key:
        req.add_header("Authorization", f"Bearer {key}")

    started = time.time()
    with urllib.request.urlopen(req, timeout=60) as resp:
        payload = json.loads(resp.read().decode())
    elapsed = time.time() - started

    text = (payload.get("text") or "").strip()
    print(f"recognised in {elapsed:.2f}s: {text!r}")
    return text


# -------------------------------------------------------------------- server

def handle(conn: socket.socket, keyboard: VirtualKeyboard, addr: str) -> None:
    conn.settimeout(30)
    data = b""
    while True:
        chunk = conn.recv(65536)
        if not chunk:
            break
        data += chunk

    if not data:
        return

    if data.startswith(b"key "):
        combo = data[4:].decode(errors="ignore").strip().lower()
        # "ctrl+c", "shift+tab", "enter" — modifiers first, key last.
        parts = combo.split("+")
        name = parts[-1]
        mods = [MODIFIERS[m] for m in parts[:-1] if m in MODIFIERS]
        code = NAMED_KEYS.get(name)
        print(f"{addr}: key {combo}" + ("" if code else " (unknown)"))
        if code:
            keyboard.press(code, mods)
        try:
            conn.sendall(b"\n")
        except OSError:
            pass
        return

    if data.startswith(b"text\n"):
        text = data[5:].decode("utf-8", errors="replace").strip()
        print(f"{addr}: typed {text!r}")
        if text:
            insert_text(keyboard, text)
        try:
            conn.sendall((text + "\n").encode())
        except OSError:
            pass
        return

    rate, channels = EXPECTED_RATE, 1
    if data.startswith(b"pcm "):
        header, _, rest = data.partition(b"\n")
        fields = header.decode(errors="ignore").split()
        if len(fields) >= 3:
            rate, channels = int(fields[1]), int(fields[2])
        data = rest

    seconds = len(data) / (rate * channels * 2)
    print(f"{addr}: {len(data)} bytes, {seconds:.1f}s at {rate} Hz")
    if seconds < 0.2:
        print("too short to be speech, ignoring")
        return

    try:
        text = transcribe(pcm_to_wav(data, rate, channels))
    except (urllib.error.URLError, RuntimeError, ValueError) as e:
        print(f"recognition failed: {e}")
        try:
            conn.sendall(b"\n")
        except OSError:
            pass
        return

    if text:
        insert_text(keyboard, text)
    try:
        conn.sendall((text + "\n").encode())
    except OSError:
        pass


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--file", help="transcribe and paste a wav file, then exit")
    args = ap.parse_args()

    keyboard = VirtualKeyboard()

    if args.file:
        with wave.open(args.file, "rb") as w:
            pcm = w.readframes(w.getnframes())
            wav = pcm_to_wav(pcm, w.getframerate(), w.getnchannels())
        text = transcribe(wav)
        if text:
            print("pasting in 3 seconds — focus a text field")
            time.sleep(3)
            insert_text(keyboard, text)
        keyboard.close()
        return

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(4)
    print(f"voice agent listening on {args.host}:{args.port}")
    if not os.environ.get("LINUXVR_ASR_URL"):
        print("WARNING: LINUXVR_ASR_URL is not set, recognition will fail")

    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=lambda c=conn, a=addr[0]: (handle(c, keyboard, a),
                                                              c.close()),
                             daemon=True).start()
    except KeyboardInterrupt:
        pass
    finally:
        keyboard.close()
        srv.close()


if __name__ == "__main__":
    main()
