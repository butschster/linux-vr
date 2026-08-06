#!/usr/bin/env python3
"""The single source of truth for which monitors exist and in what order.

Three separate pieces need to agree: the streamer picks a CRTC to capture, the
input agent maps pointer coordinates into a monitor's rectangle, and the headset
app opens one window per monitor. If their orderings disagree, clicks land on
the wrong screen — and that failure is invisible until someone tries to click
something.

So ordering lives here, once: monitors sorted by connector name.

Two naming systems have to be reconciled. DRM calls a connector `HDMI-A-1`;
GNOME's logical monitors call the same thing `HDMI-1`. Neither is wrong, they
just differ, and the mapping is needed because the CRTC comes from DRM while the
desktop rectangle comes from GNOME.

Run directly to see the table:

    ./monitors.py
    sudo ./monitors.py        # includes CRTC ids, which need debugfs
"""

import json
import re
import subprocess
import sys

# Streams are spaced ten apart so the input agent's port (9101) and any future
# per-monitor side channel fit between them without renumbering everything.
STREAM_PORT_BASE = 9100
STREAM_PORT_STEP = 10


def _normalise(connector: str) -> str:
    """`HDMI-A-1` and `HDMI-1` are the same connector under two conventions."""
    return re.sub(r"^([A-Za-z]+)-A-(\d+)$", r"\1-\2", connector)


def gnome_layout():
    """Logical monitor rectangles from mutter, keyed by normalised connector."""
    try:
        out = subprocess.run(
            ["busctl", "--user", "--json=short", "call",
             "org.gnome.Mutter.DisplayConfig", "/org/gnome/Mutter/DisplayConfig",
             "org.gnome.Mutter.DisplayConfig", "GetCurrentState"],
            capture_output=True, text=True, timeout=10).stdout
        data = json.loads(out)["data"]
    except Exception as e:
        print(f"cannot read monitor layout from mutter: {e}", file=sys.stderr)
        return {}, (0, 0)

    sizes = {}
    for monitor in data[1]:
        connector = monitor[0][0]
        for mode in monitor[1]:
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
                rects[_normalise(connector)] = (x, y, w, h)
                total_w = max(total_w, x + w)
                total_h = max(total_h, y + h)

    return rects, (total_w, total_h)


def drm_crtcs():
    """Connector -> CRTC object id, from DRM state.

    Reading debugfs needs root, but the layout above needs the user's session
    bus — root has none. So the script stays user-side and elevates only for
    this one read.
    """
    read = "cat /sys/kernel/debug/dri/*/state"
    commands = [["sh", "-c", read]]
    import os
    if os.geteuid() != 0:
        commands.append(["sudo", "-n", "sh", "-c", read])

    out = ""
    for cmd in commands:
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout
        except Exception:
            out = ""
        if out:
            break
    if not out:
        return {}

    crtc_ids = {}          # crtc-N -> numeric id
    result = {}
    pending = None
    for line in out.splitlines():
        m = re.match(r"^crtc\[(\d+)\]: (\S+)", line)
        if m:
            crtc_ids[m.group(2)] = int(m.group(1))
            continue
        m = re.match(r"^connector\[\d+\]: (\S+)", line)
        if m:
            pending = m.group(1)
            continue
        m = re.match(r"^\s+crtc=(\S+)", line)
        if m and pending:
            name = m.group(1)
            # Writeback connectors share a CRTC with a real output and would
            # otherwise overwrite it.
            if name != "(null)" and not pending.startswith("Writeback"):
                result[_normalise(pending)] = crtc_ids.get(name)
            pending = None
    return result


def monitors():
    """Ordered list of monitors, one dict each.

    Ordering is by connector name and must not be changed casually: the headset
    app addresses monitors by index, so reordering silently rewires which window
    shows which screen.
    """
    rects, desktop = gnome_layout()
    crtcs = drm_crtcs()

    out = []
    for index, connector in enumerate(sorted(rects)):
        x, y, w, h = rects[connector]
        out.append({
            "index": index,
            "connector": connector,
            "crtc": crtcs.get(connector),
            "x": x, "y": y, "width": w, "height": h,
            "port": STREAM_PORT_BASE + index * STREAM_PORT_STEP,
        })
    return out, desktop


def main():
    mons, desktop = monitors()
    if not mons:
        sys.exit("no monitors found")

    if "--json" in sys.argv:
        print(json.dumps({"desktop": {"width": desktop[0], "height": desktop[1]},
                          "monitors": mons}, indent=2))
        return

    print(f"desktop {desktop[0]}x{desktop[1]}")
    for m in mons:
        crtc = m["crtc"] if m["crtc"] is not None else "? (run with sudo)"
        print(f"  [{m['index']}] {m['connector']:10s} {m['width']}x{m['height']}"
              f" at +{m['x']}+{m['y']}  crtc={crtc}  port={m['port']}")


if __name__ == "__main__":
    main()
