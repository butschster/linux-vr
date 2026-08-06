---
name: vr-diagnostics
description: Diagnose the VR desktop stack when the picture is black, frozen, stuttering or the pointer misses. Use before changing any code — most symptoms here were misdiagnosed at least once, and each check replaces a guess with a fact. Covers headset state, app liveness, log capture, stream health, capture source identification and pointer mapping.
---

# Diagnosing the VR desktop stack

Every procedure here exists because guessing produced a wrong answer first.
Run the check, do not reason from the symptom.

## Rule zero: a symptom has more than one cause

"Black screen" has meant, on different occasions: the headset was asleep, the
client never connected, the stream fell seconds behind reality, and a dark
terminal filling the captured monitor. Narrow it down before touching code.

## Headset and app

**Is the headset even awake?** Taken off, it sleeps, the activity pauses and the
session never starts. This is not a bug and wastes the most time when missed.

```sh
adb shell dumpsys power | grep mWakefulness=
```

**Is the app actually running?** `am start` returning success means nothing —
an app can die in `onCreate`, and the VR shell may not create a panel at all.

```sh
adb shell pidof <package> && echo ALIVE || echo DEAD
```

**Why did it die?**

```sh
adb logcat -d | grep -A 25 'FATAL EXCEPTION' | tail -30
```

**Capture logs before launching, not after.** The compositor can flood logcat
with warnings in seconds and evict everything. `logcat -d` afterwards shows
nothing:

```sh
adb logcat -c && adb logcat -s linux-vr > log.txt &
adb shell am start -n <package>/<activity>
```

## Stream health

Read three numbers together — one alone misleads.

**Host speed and duplicates**, from the ffmpeg stats line:

```
frame=358 fps=28 dup=341 speed=0.492x
```

- `speed` below 1x means the stream falls behind reality without bound. The
  picture looks frozen because it is showing several seconds ago.
- a high `dup` ratio is normal for a static desktop; combined with low `speed`
  it means rate control is padding unchanged frames.

**Socket send queue** — buffer bloat, and therefore latency, in bytes:

```sh
ss -tn | grep <port>          # third column is Send-Q
```

Steady megabytes mean the link is saturated and every frame is that far behind.
Zero is healthy.

**Client counters.** Units in versus frames out versus dropped. A stalled stream
and a genuinely static desktop look identical without them.

## Which monitor is being captured

Never guess from what the picture looks like. The pointer mapping depends on the
answer, and a wrong answer puts every click on the neighbouring screen.

```sh
sudo ffmpeg -loglevel debug -device /dev/dri/card1 -f kmsgrab -i - -t 0.2 -f null - 2>&1 \
    | grep -i 'Using plane'
# [kmsgrab] Plane 149: CRTC 368 FB 455.

sudo sh -c 'cat /sys/kernel/debug/dri/*/state' | grep -E '^(crtc|connector)|	crtc='
# crtc[368]: crtc-1
# connector[388]: HDMI-A-2
# 	crtc=crtc-1
```

DRM names connectors `HDMI-A-1`; GNOME's logical monitors are `HDMI-1`. They
correspond in order.

**Is the capture itself blank?** Grab one frame and look at it rather than
inferring:

```sh
sudo ffmpeg -device /dev/dri/card1 -f kmsgrab -i - -t 1 \
    -vf 'hwmap=derive_device=vaapi,hwdownload,format=bgr0,scale=320:180' \
    -frames:v 1 probe.png
```

## Desktop layout, for pointer mapping

```sh
busctl --user --json=short call org.gnome.Mutter.DisplayConfig \
    /org/gnome/Mutter/DisplayConfig org.gnome.Mutter.DisplayConfig GetCurrentState
```

`data[2]` is the logical monitors: x, y, scale, transform, primary, connectors.
A virtual absolute pointer addresses the whole desktop, so layer fractions must
be mapped into the rectangle of the captured monitor.

## Pointer misses the target

Split the question before fixing anything: **do the in-headset cursor and the
system cursor coincide?**

- They coincide, but both land off target → aiming, not mapping. In an immersive
  app with no rendered ray this is expected: the controller's aim axis is not
  the forearm axis.
- They differ → mapping. Verify the agent independently by sending known
  coordinates and locating the pointer, rather than adjusting and re-asking.

Behaviour beats arithmetic as a suspect. One real case: dragging the screen also
dragged the desktop pointer, because the ray kept hitting the moving layer and
positions kept being sent. The maths was correct all along.

## Self-inflicted traps

**`pkill -f <pattern>` kills your own shell** when the pattern appears in your
own command line — which it does, because you just typed it. Symptom: the
command dies with an odd exit code and nothing happens. Kill by exact name or by
the port owner:

```sh
sudo pkill -x ffmpeg
kill $(ss -tlnp | grep :9101 | grep -oP 'pid=\K[0-9]+')
```

**Piping a long-running process into `tail` hides its output** until it exits.
Redirect to a file instead.

**Single-client servers refuse test connections.** The input agent serves one
connection at a time; while the headset holds it, a test connection times out.
Stop the client first or make the server concurrent.
