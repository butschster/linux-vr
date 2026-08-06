#!/usr/bin/env python3
"""Injects pointer input coming from the headset into the Linux session.

Why a virtual absolute pointer rather than a relative mouse: the headset knows
where the ray hits the layer, not how far the pointer should travel. Converting
that into relative deltas would require knowing the current cursor position,
which no Wayland client is allowed to read. An absolute device sidesteps the
problem entirely.

Why raw uinput through ctypes and not python-evdev: no dependency to install,
and evdev adds nothing for the handful of ioctls needed here.

Why uinput at all, rather than a Wayland protocol: mutter does not implement
zwp_virtual_keyboard, so wtype and friends are a dead end on GNOME. uinput sits
below the compositor and works regardless of it — the user only needs to be in
the `input` group.

Protocol, one command per line, deliberately human-readable so it can be driven
from a terminal while debugging:

    list          reply with the monitor list, then `end`
    use <n>       this connection drives monitor n (index or connector name)
    m <x> <y>     absolute position within that monitor, floats in 0..1
    d <button>    press    (left | right | middle)
    u <button>    release
    s <dx> <dy>   scroll, integer clicks

One connection per window. Without `use`, coordinates address the whole desktop,
which is only correct on a single-monitor setup.

Usage:
    ./input-agent.py [--port 9101]
    printf 'use 1\nm 0.5 0.5\n' | nc -q0 localhost 9101
"""

import argparse
import fcntl
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time


# Monitor order, rectangles and ports come from monitors.py so that the
# streamer, this agent and the headset app cannot disagree about which screen
# is which. A disagreement there is invisible until someone clicks.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import monitors as monitors_mod


# ---------------------------------------------------------------- uinput ABI

EV_SYN, EV_KEY, EV_REL, EV_ABS = 0x00, 0x01, 0x02, 0x03
SYN_REPORT = 0x00
REL_WHEEL, REL_HWHEEL = 0x08, 0x06
ABS_X, ABS_Y = 0x00, 0x01
BTN_LEFT, BTN_RIGHT, BTN_MIDDLE = 0x110, 0x111, 0x112
INPUT_PROP_POINTER = 0x00

BUTTONS = {"left": BTN_LEFT, "right": BTN_RIGHT, "middle": BTN_MIDDLE}

# The pointer works in a fixed 0..65535 grid rather than in pixels: the layer
# does not know the desktop resolution, and libinput scales the range to
# whatever the screen actually is.
ABS_MAX = 65535


def _iow(nr: int, size: int) -> int:
    return (1 << 30) | (size << 16) | (ord("U") << 8) | nr


def _io(nr: int) -> int:
    return (ord("U") << 8) | nr


UI_DEV_CREATE = _io(1)
UI_DEV_DESTROY = _io(2)
UI_DEV_SETUP = _iow(3, 92)
UI_ABS_SETUP = _iow(4, 28)
UI_SET_EVBIT = _iow(100, 4)
UI_SET_KEYBIT = _iow(101, 4)
UI_SET_RELBIT = _iow(102, 4)
UI_SET_ABSBIT = _iow(103, 4)
UI_SET_PROPBIT = _iow(110, 4)


class VirtualPointer:
    def __init__(self, name: str = "linux-vr pointer", desktop=None):
        # One virtual device serves every window. The region is not stored here
        # but passed per move, because each connection drives a different
        # monitor and they arrive interleaved.
        self.desktop = desktop

        try:
            self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        except PermissionError:
            sys.exit(
                "Cannot open /dev/uinput. Add yourself to the `input` group:\n"
                "    sudo usermod -aG input $USER\n"
                "then log out and back in."
            )

        # These ioctls take the bit as an immediate value, not a pointer to one.
        # Passing a packed buffer makes Python hand over its address, which the
        # kernel then reads as the bit number and rejects with EINVAL.
        for ev in (EV_KEY, EV_ABS, EV_REL, EV_SYN):
            fcntl.ioctl(self.fd, UI_SET_EVBIT, ev)
        for btn in BUTTONS.values():
            fcntl.ioctl(self.fd, UI_SET_KEYBIT, btn)
        for axis in (ABS_X, ABS_Y):
            fcntl.ioctl(self.fd, UI_SET_ABSBIT, axis)
        for rel in (REL_WHEEL, REL_HWHEEL):
            fcntl.ioctl(self.fd, UI_SET_RELBIT, rel)

        # Without INPUT_PROP_POINTER libinput guesses this is a touchscreen and
        # treats absolute coordinates as touches rather than pointer motion.
        fcntl.ioctl(self.fd, UI_SET_PROPBIT, INPUT_PROP_POINTER)

        for axis in (ABS_X, ABS_Y):
            # uinput_abs_setup: u16 code, then input_absinfo (6 x s32)
            absinfo = struct.pack("iiiiii", 0, 0, ABS_MAX, 0, 0, 0)
            fcntl.ioctl(self.fd, UI_ABS_SETUP, struct.pack("H2x", axis) + absinfo)

        # uinput_setup: input_id (4 x u16), char name[80], u32 ff_effects_max
        setup = struct.pack("HHHH80sI", 0x03, 0x1234, 0x5678, 1,
                            name.encode()[:79], 0)
        fcntl.ioctl(self.fd, UI_DEV_SETUP, setup)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)

        # udev needs a moment to notice the device; writing immediately means
        # the first events land nowhere.
        time.sleep(0.3)

    def _emit(self, ev_type: int, code: int, value: int) -> None:
        # input_event on 64-bit: struct timeval (2 x long), u16, u16, s32
        os.write(self.fd, struct.pack("llHHi", 0, 0, ev_type, code, value))

    def _sync(self) -> None:
        self._emit(EV_SYN, SYN_REPORT, 0)

    def move(self, x: float, y: float, region=None) -> None:
        x = min(max(x, 0.0), 1.0)
        y = min(max(y, 0.0), 1.0)

        # Fractions of the window become fractions of the whole desktop, so that
        # pointing at the middle of a streamed monitor lands in the middle of
        # that monitor and not in the middle of the desktop.
        if region and self.desktop and self.desktop[0] and self.desktop[1]:
            rx, ry, rw, rh = region
            dw, dh = self.desktop
            x = (rx + x * rw) / dw
            y = (ry + y * rh) / dh

        self._emit(EV_ABS, ABS_X, int(x * ABS_MAX))
        self._emit(EV_ABS, ABS_Y, int(y * ABS_MAX))
        self._sync()

    def button(self, name: str, pressed: bool) -> None:
        code = BUTTONS.get(name)
        if code is None:
            return
        self._emit(EV_KEY, code, 1 if pressed else 0)
        self._sync()

    def scroll(self, dx: int, dy: int) -> None:
        if dy:
            self._emit(EV_REL, REL_WHEEL, dy)
        if dx:
            self._emit(EV_REL, REL_HWHEEL, dx)
        self._sync()

    def close(self) -> None:
        try:
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        finally:
            os.close(self.fd)


# ---------------------------------------------------------------- server

def resolve(mons, token):
    """Accept either an index or a connector name, whichever the client sends."""
    if token.isdigit():
        index = int(token)
        for m in mons:
            if m["index"] == index:
                return m
        return None
    for m in mons:
        if m["connector"].lower() == token.lower():
            return m
    return None


def handle(conn: socket.socket, pointer: VirtualPointer, mons, addr) -> None:
    # Each connection carries its own monitor. They arrive interleaved from
    # several windows, so this cannot live on the shared pointer.
    region = None
    buf = b""
    while True:
        chunk = conn.recv(4096)
        if not chunk:
            return
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            parts = line.decode(errors="ignore").split()
            if not parts:
                continue
            try:
                cmd = parts[0]
                if cmd == "list":
                    # The headset asks how many screens exist so it can open one
                    # window per screen. Discovering it at runtime is the only
                    # way to be right on every desk: a fixed set of launcher
                    # entries is wrong for anyone with a different monitor count.
                    reply = "".join(
                        f"monitor {m['index']} {m['connector']} "
                        f"{m['width']} {m['height']} {m['port']}\n" for m in mons)
                    conn.sendall((reply + "end\n").encode())
                elif cmd == "use" and len(parts) >= 2:
                    m = resolve(mons, parts[1])
                    if m is None:
                        print(f"{addr}: unknown monitor {parts[1]}")
                    else:
                        region = (m["x"], m["y"], m["width"], m["height"])
                        print(f"{addr}: drives [{m['index']}] {m['connector']}")
                elif cmd == "m" and len(parts) >= 3:
                    pointer.move(float(parts[1]), float(parts[2]), region)
                elif cmd == "d" and len(parts) >= 2:
                    pointer.button(parts[1], True)
                elif cmd == "u" and len(parts) >= 2:
                    pointer.button(parts[1], False)
                elif cmd == "s" and len(parts) >= 3:
                    pointer.scroll(int(parts[1]), int(parts[2]))
            except (ValueError, IndexError):
                # A malformed line is not worth dropping the connection over
                continue


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9101)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()

    mons, desktop = monitors_mod.monitors()
    if not mons:
        sys.exit("no monitors found")

    print(f"desktop {desktop[0]}x{desktop[1]}")
    for m in mons:
        print(f"    [{m['index']}] {m['connector']}: {m['width']}x{m['height']}"
              f" at +{m['x']}+{m['y']}")

    pointer = VirtualPointer(desktop=desktop)
    print(f"virtual pointer created, listening on {args.host}:{args.port}")

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    # One connection per window, so the backlog has to hold more than one.
    srv.listen(8)

    def serve(conn, addr):
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print(f"connected: {addr}")
        try:
            handle(conn, pointer, mons, addr)
        except OSError:
            pass
        finally:
            conn.close()
            print(f"disconnected: {addr}")

    try:
        while True:
            conn, addr = srv.accept()
            # A thread per connection: windows send interleaved and a single
            # blocking handler would let one window freeze the others.
            threading.Thread(target=serve, args=(conn, addr[0]), daemon=True).start()
    except KeyboardInterrupt:
        pass
    finally:
        pointer.close()
        srv.close()


if __name__ == "__main__":
    main()
