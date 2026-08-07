---
name: headset
description: Talk to a Quest 3 from this Linux machine — get adb to see it, go wireless, capture logs that do not get evicted, and tell a dead app from a sleeping headset. Use whenever a command has to reach the headset, or when adb sees nothing, says unauthorized, or the app "started" but nothing happened.
---

# Reaching the headset from Linux

Everything here was learned by losing time to it. The recurring shape is that
the headset answers cheerfully while doing nothing you asked, so **every step
ends in a fact rather than an absence of errors**.

## Is it there at all

```sh
adb devices -l
```

| What you see | What it means |
|---|---|
| `2G97C5ZJ2300XS   device` | ready |
| `...   unauthorized` | the headset is showing an "Allow USB debugging" dialog — **put it on and accept it**. Tick "always allow" or you will do this again |
| `...   offline` | usually a sleeping headset, see below. `adb reconnect` if not |
| nothing at all | developer mode off, a charge-only cable, or udev |

Developer mode is set in the phone's Meta Horizon app, per device, and it does
not survive being toggled off. If the device never appears on any cable:

```sh
lsusb | grep -i oculus          # 2833: is Meta. Nothing here is a cable or a port problem
groups | grep -q plugdev || echo "not in plugdev"
```

## The headset sleeps the moment you take it off

This is the single most common "it broke" that is not a break. The proximity
sensor suspends the session: the activity pauses, the OpenXR session never
starts, adb may go `offline`.

```sh
adb shell dumpsys power | grep -E "mWakefulness="
```

`mWakefulness=Asleep` means put it on, or cover the sensor. Do not go looking
for the cause in code before running this.

## Wireless, so the cable stops mattering

The cable is fine for installing and hopeless for wearing. Once over USB:

```sh
adb tcpip 5555
adb shell ip route | awk '/wlan0/ {print $NF; exit}'    # the headset's address
adb connect <that address>:5555
```

Then unplug. It survives until the headset reboots, after which `adb tcpip 5555`
has to be done over USB again. If both a USB and a network entry are listed,
every command needs `-s <serial>` or adb refuses with "more than one device".

## Logs, without losing them

**The ring buffer evicts.** The Horizon OS compositor can flood logcat with
warnings in seconds, so `logcat -d` after the fact shows you the flood and not
your lines. Capture live, filtered by tag, **started before the app**:

```sh
adb logcat -c && adb logcat -s linux-vr > /tmp/headset.log &
adb shell am start -n dev.butschster.linuxvr/.ServersActivity
```

For a native crash the tag is different and the stack is in `DEBUG`:

```sh
adb logcat -s DEBUG AndroidRuntime linux-vr
```

## "It started" is not a fact

`am start` reports success for an activity that dies in `onCreate`. Ask the
system whether a process exists:

```sh
adb shell pidof dev.butschster.linuxvr && echo ALIVE || echo DEAD
```

To see what is actually in front of the user:

```sh
adb shell dumpsys activity activities | grep -m3 -E "ResumedActivity|topResumedActivity"
```

## Screenshots are black, and that is correct

```sh
adb shell screencap -p /sdcard/x.png     # black for this project
```

`screencap` reads the eye buffer. This project's whole point is that frames go
into a **composition layer**, which the compositor samples separately and which
therefore never appears in a capture. A black screenshot is evidence the
architecture is working, not that the app is broken. To see what the user sees,
look through the lenses, or cast from the headset.

## Installing and removing

```sh
adb install -r linux-vr.apk                  # -r keeps app data
adb uninstall dev.butschster.linuxvr
adb shell dumpsys package dev.butschster.linuxvr | grep -E "versionName|versionCode"
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed copy was signed with a
different key — almost always a local `make dev` build being replaced by a
release one. Uninstall once and install again; between two releases it should
never happen, and if it does the signing key changed and that is a real defect.

Sideloaded apps live under **Unknown Sources** in the library, not with the
store apps.

## Getting recordings and screenshots off

The headset's own screen recorder writes here. Nothing else does, and the
filename carries the app and the timestamp:

```sh
adb shell ls -lt /sdcard/Oculus/VideoShots      # recordings, newest first
adb shell ls -lt /sdcard/Oculus/Screenshots
```

Pull the lot into `~/Videos/quest`:

```sh
mkdir -p ~/Videos/quest && adb pull /sdcard/Oculus/VideoShots ~/Videos/quest
```

Measured at **38 MB/s over USB** — a 77 MB recording in under two seconds. Do
not reach for MTP for this: it is slower, it needs the headset's USB mode
changed by hand, and changing that mode drops the adb connection you are
probably using for something else. MTP is only worth it to *browse* in Files,
and the switch lives in the headset's USB notification, not in any command here.

Recordings are H.265 in an MP4 — fine for `ffprobe`, `mpv` and anything else,
and worth checking before assuming a file is broken:

```sh
ffprobe -v error -show_entries format=duration,size:stream=codec_name,width,height \
  -of default=noprint_wrappers=1 ~/Videos/quest/*.mp4
```

Disk pressure is rarely the reason to delete them — a Quest 3 has hundreds of
gigabytes free. Check before tidying, and delete deliberately rather than as
part of a pull:

```sh
adb shell df -h /sdcard | tail -1
adb shell rm /sdcard/Oculus/VideoShots/<exact-name>.mp4
```

## Poking the app from here

```sh
adb shell am start -n dev.butschster.linuxvr/.ServersActivity
adb shell am force-stop dev.butschster.linuxvr
adb shell pm clear dev.butschster.linuxvr        # wipes saved servers
```

## Checking the headset can reach the host

Discovery is a UDP broadcast, and some networks drop it while routing
everything else fine — which looks exactly like a server that is not running.
Test the direct path first:

```sh
HOST=$(hostname -I | awk '{print $1}')
adb shell "curl -s --max-time 3 http://$HOST:9099/v1/info | head -c 200"
```

An answer here with an empty server list in the app means broadcast is being
dropped, not that the server is down. Add the machine by address instead.

## Related

- `vr-diagnostics` — when the picture is black, frozen or the pointer misses
- `install` — putting a release on the headset and on the Linux machine
