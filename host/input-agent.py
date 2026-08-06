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

    m <x> <y>     absolute position, floats in 0..1
    d <button>    press    (left | right | middle)
    u <button>    release
    s <dx> <dy>   scroll, integer clicks

Usage:
    ./input-agent.py [--port 9101]
    echo "m 0.5 0.5" | nc -q0 localhost 9101      # pointer to screen centre
"""

import argparse
import fcntl
import json
import os
import socket
import struct
import subprocess
import sys
import time


def desktop_layout():
    """Logical monitor rectangles, keyed by connector, from mutter.

    Needed because a virtual absolute pointer addresses the whole desktop while
    the stream shows a single monitor. Without the mapping, the left half of the
    layer lands on the neighbouring screen — the coordinates are off by exactly
    the ratio of one monitor to the whole desktop.
    """
    try:
        out = subprocess.run(
            ["busctl", "--user", "--json=short", "call",
             "org.gnome.Mutter.DisplayConfig", "/org/gnome/Mutter/DisplayConfig",
             "org.gnome.Mutter.DisplayConfig", "GetCurrentState"],
            capture_output=True, text=True, timeout=10).stdout
        data = json.loads(out)["data"]
    except Exception:
        return {}, (0, 0)

    sizes = {}
    for monitor in data[1]:
        connector = monitor[0][0]
        for mode in monitor[1]:
            # mode: id, width, height, refresh, preferred_scale, ..., properties
            props = mode[6] if len(mode) > 6 else {}
            if isinstance(props, dict) and props.get("is-current", {}).get("data"):
                sizes[connector] = (mode[1], mode[2])
                break

    rects = {}
    total_w = total_h = 0
    for lm in data[2]:
        x, y = lm[0], lm[1]
        for m in lm[5]:
            connector = m[0]
            w, h = sizes.get(connector, (0, 0))
            if w and h:
                rects[connector] = (x, y, w, h)
                total_w = max(total_w, x + w)
                total_h = max(total_h, y + h)

    return rects, (total_w, total_h)

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
    def __init__(self, name: str = "linux-vr pointer", region=None, desktop=None):
        # region: (x, y, w, h) of the captured monitor inside the desktop.
        # None means the layer covers the whole desktop.
        self.region = region
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

    def move(self, x: float, y: float) -> None:
        x = min(max(x, 0.0), 1.0)
        y = min(max(y, 0.0), 1.0)

        # Fractions of the layer become fractions of the whole desktop, so that
        # pointing at the middle of the streamed monitor lands in the middle of
        # that monitor and not in the middle of the desktop.
        if self.region and self.desktop and self.desktop[0] and self.desktop[1]:
            rx, ry, rw, rh = self.region
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

def handle(conn: socket.socket, pointer: VirtualPointer) -> None:
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
                if cmd == "m" and len(parts) >= 3:
                    pointer.move(float(parts[1]), float(parts[2]))
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
    ap.add_argument("--monitor", default=None,
                    help="connector the stream captures, e.g. HDMI-2. "
                         "Without it the layer is mapped onto the whole desktop, "
                         "which is wrong as soon as there is more than one screen.")
    args = ap.parse_args()

    rects, desktop = desktop_layout()
    region = None
    if rects:
        print(f"desktop {desktop[0]}x{desktop[1]}, monitors:")
        for connector, r in sorted(rects.items()):
            marker = " <- streamed" if connector == args.monitor else ""
            print(f"    {connector}: {r[2]}x{r[3]} at +{r[0]}+{r[1]}{marker}")
        if args.monitor:
            region = rects.get(args.monitor)
            if region is None:
                sys.exit(f"unknown connector {args.monitor}")
        elif len(rects) > 1:
            print("WARNING: several monitors and no --monitor given; the pointer "
                  "will address the whole desktop and land on the wrong screen.")

    pointer = VirtualPointer(region=region, desktop=desktop)
    print(f"virtual pointer created, listening on {args.host}:{args.port}")

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(1)

    try:
        while True:
            conn, addr = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            print(f"connected: {addr[0]}")
            try:
                handle(conn, pointer)
            except OSError:
                pass
            finally:
                conn.close()
                print("disconnected")
    except KeyboardInterrupt:
        pass
    finally:
        pointer.close()
        srv.close()


if __name__ == "__main__":
    main()
