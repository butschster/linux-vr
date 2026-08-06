# Gotchas

Things that broke and how they were fixed. Extended as new ones show up.

## Quest / Horizon OS

### Moonlight crashes on startup, every version

`com.limelight` dies in `onCreate`, before the panel is even drawn:

```
java.lang.NullPointerException: Attempt to invoke virtual method
'void android.app.GameManager.setGameState(android.app.GameState)' on a null object reference
    at com.limelight.utils.UiHelper.setGameModeStatus(UiHelper.java:40)
    at com.limelight.utils.UiHelper.notifyNewRootView(UiHelper.java:118)
    at com.limelight.PcView.completeOnCreate(PcView.java:245)
```

Moonlight requests the `GameManager` system service (Android 13+) and does not
null-check the result. Horizon OS does not provide that service.

**Downgrading does not help.** Verified: v12.1 and v11.0 crash identically, and
`GameManager` is present in the dex all the way back to v10.8.4 (September
2022). The cause is not the Moonlight version: the code is guarded by an API
level check, but **Horizon OS reports API 34**, the guard passes, and
`getSystemService` returns `null`.

**Fix:** build from source with a null check in `UiHelper.setGameModeStatus`.
As a bonus the tree contains `moonlight-common-c`, which milestone C needs
anyway.

```sh
git clone --recurse-submodules --depth 1 \
    https://github.com/moonlight-stream/moonlight-android.git vendor/moonlight-android
cd vendor/moonlight-android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
# in app/build.gradle: set ndkVersion to the installed one
# in UiHelper.setGameModeStatus: early return if gameManager == null
./gradlew assembleNonRootDebug
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
```

The debug build installs under a **different** applicationId —
`com.limelight.debug`, not `com.limelight`. Launch with
`am start -n com.limelight.debug/com.limelight.PcView`.

This does not affect our own client, which is native and never touches
`GameManager`. The general lesson does apply: **an API level on Horizon OS does
not guarantee a system service exists.** Check by fact, not by `Build.VERSION`.

### Horizon OS blocks launch when hand tracking is not declared

`am start` returns success but the app never starts. In the log:

```
ActivityLaunchInterceptorController: RequiresControllersLaunchInterceptor
CaseDialogAnalytics: dialogId=common_system_dialog_app_launch_blocked_controller_required
```

The system decides the app needs controllers and blocks launch when the user is
working with hands. For a VR desktop that is precisely the normal case:
controllers are on the desk, hands are on the keyboard.

**Fix** — in the manifest:

```xml
<uses-feature android:name="oculus.software.handtracking" android:required="false" />
<uses-permission android:name="com.oculus.permission.HAND_TRACKING" />
```

You do not have to actually use hand tracking; the declaration is what counts.

### `xrCreateSwapchainAndroidSurfaceKHR` requires zeroed format fields

Returns `-1` (`XR_ERROR_VALIDATION_FAILURE`) if you fill in
`XrSwapchainCreateInfo` the way you would for an ordinary swapchain.

For a surface swapchain only **`width` and `height`** are set. `format`,
`sampleCount`, `faceCount`, `arraySize` and `mipCount` must stay zero — the
frame producer (`MediaCodec`) owns the contents, not the application. The
habitual ones in `faceCount`/`arraySize`/`mipCount` are rejected.

### 2D apps do not always start via `am start`

`adb shell am start` for a 2D app returns without error, but the VR shell may
not create a panel for it. The symptom is an activity that started and a process
that is already dead.

Check by fact, not by return code:

```sh
adb shell pidof com.limelight && echo ALIVE || echo DEAD
```

Immersive apps (category `IMMERSIVE_HMD`) are not affected; they launch through
`am start` normally.

### Ping to a sleeping headset lies

RTT to the Quest jumps between 21 ms and 1.6 s when the headset is off your
head — Wi-Fi power saving. Only measure connectivity with the headset worn,
otherwise the diagnosis wanders off into network problems that do not exist.

### `/dev/tcp` does not work in the headset shell

Testing a port with `echo > /dev/tcp/host/port` returns
`No such file or directory`. That is a limitation of the Android shell, not a
closed port. Use another method.

### Log lines are evicted from the ring buffer

The compositor can flood logcat with warnings in seconds — for example
`UpdateApertureCompositorData: compositor update failed ... aperture permanently
dropped`, repeated dozens of times per second. Application logs read with
`logcat -d` afterwards are simply gone by then.

Capture live by tag, started before the app:

```sh
adb logcat -c && adb logcat -s linux-vr > log.txt &
adb shell am start -n dev.butschster.linuxvr/android.app.NativeActivity
```

## Host

### Vulkan Video encode on RADV hangs the GPU

**Symptom:** a second after the stream starts, the GPU resets and you are thrown
back to the graphical login.

```
amdgpu 0000:06:00.0: GPU reset(1) succeeded!
amdgpu 0000:06:00.0: [drm] device wedged, but recovered through reset
amdgpu 0000:06:00.0: [drm] *ERROR* Failed to initialize parser -125!
REG_WAIT timeout 1us * 100 tries - dcn31_program_compbuf_size
WARNING: dcn31_hubbub.c:151 at dcn31_program_compbuf_size
```

Sunshine loses Wayland at that moment (`Error reading events from display:
Broken pipe`) and exits with status 1.

**Cause:** Sunshine picks `h264_vulkan` / `hevc_vulkan` by default — the Vulkan
Video path on RADV. On Rembrandt (VCN 3.0) it wedges the video engine.

**Fix:** force VAAPI in `~/.config/sunshine/sunshine.conf`:

```
encoder = vaapi
```

VAAPI was verified separately on this hardware: `ffmpeg` ran `h264_vaapi` at
1440p90 with CBR 100 Mbit for ten seconds without a single error, at 160 fps.

### After a GPU reset Sunshine restarts into a vacuum

systemd brings the service back up before the graphical session is restored.
The log then shows:

```
Error: Couldn't open: /dev/dri/card1: Permission denied
Error: [wayland] Environment variable WAYLAND_DISPLAY has not been defined
Warning: [portalgrab] Failed to connect to dbus
Fatal: Unable to find display or encoder during startup
```

Every encoder reports `failed` even though the hardware is fine.
**After logging back in, restart Sunshine by hand:**

```sh
systemctl --user restart app-dev.lizardbyte.app.Sunshine.service
```

### kmsgrab above the display refresh rate breaks timestamps

Asking for a capture rate higher than the display actually produces does not
merely duplicate frames — it destroys timestamp generation entirely.

Capturing a 60 Hz output with `-framerate 90`:

```
frame=11187 fps=197 time=00:00:00.01 dup=11185 speed=0.000197x
```

Eleven thousand frames and the output clock has advanced by one hundredth of a
second. The client receives what amounts to a single still frame.

At `-framerate 60` on the same output the result is normal: `dup=13`, the clock
advances correctly.

**The capture rate must match the display, not the headset.** The mismatch with
the headset is harmless — that is the point of a composition layer: it is
world-locked and the compositor reprojects it at 90 Hz regardless of the stream
rate.

### Which monitor kmsgrab captures is not guessable — ask the kernel

kmsgrab picks the first plane that has a framebuffer, and that is not
necessarily the primary monitor. Guessing from what the picture looks like
wastes time, because the pointer mapping depends on the answer and a wrong
mapping puts every click on the neighbouring screen.

Resolve it in two steps. First, the CRTC kmsgrab chose:

```sh
sudo ffmpeg -loglevel debug -device /dev/dri/card1 -f kmsgrab -i - -t 0.2 -f null - 2>&1 \
    | grep -i 'Using plane'
# [kmsgrab] Plane 149: CRTC 368 FB 455.
# [kmsgrab] Using plane 149 to locate framebuffers.
```

Then the connector on that CRTC:

```sh
sudo sh -c 'cat /sys/kernel/debug/dri/*/state' | grep -E '^(crtc|connector)|	crtc='
# crtc[368]: crtc-1
# connector[388]: HDMI-A-2
# 	crtc=crtc-1
```

So CRTC 368 is HDMI-A-2, and the input agent needs `--monitor HDMI-2`.

Note the naming difference: DRM connectors are `HDMI-A-1`/`HDMI-A-2` while
GNOME's logical monitors are `HDMI-1`/`HDMI-2`. They correspond in order.

### CBR is the wrong rate control for a desktop

With CBR 100M on a still desktop the pipeline ran at **0.49x real time** and the
stream fell several seconds behind reality, which looks exactly like a frozen
picture.

The mechanism: a desktop is mostly static, so kmsgrab hands back the same
framebuffer and 341 of 358 frames were duplicates. CBR pads each of them to the
target bitrate anyway. The Wi-Fi link cannot carry 83 Mbit/s of padding, ffmpeg
blocks writing to the socket, and everything upstream halves in speed. The TCP
send queue sat at a steady 2.7 MB — about 220 ms of pure buffer bloat.

Switching to `-rc_mode CQP -global_quality 23`:

| | CBR 100M | CQP 23 |
|---|---|---|
| Host speed | 0.49x | 1.1x |
| Bitrate | 83 Mbit/s | 1.9 Mbit/s |
| Socket send queue | 2.7 MB | 0 |

Under CQP an unchanged frame encodes as all-skip macroblocks — a few hundred
bytes. A still desktop costs almost nothing and only real changes cost bitrate.

### Sunshine's unit has a different name

Not `sunshine.service` but **`app-dev.lizardbyte.app.Sunshine.service`** — it
was renamed in later releases. Instructions found online do not work.

### Sunshine can be configured without a browser

```sh
sunshine --creds <user> <password>          # web UI credentials
curl -sk -u '<user>:<password>' https://localhost:47990/api/apps
curl -sk -u '<user>:<password>' https://localhost:47990/api/clients/list
```

The certificate is self-signed, so `curl` needs `-k`.

### Startup encoder probe errors are noise

Sunshine prints `Couldn't find monitor [0]` and codec open failures while
probing encoders at startup, then says so itself:

```
// Ignore any errors mentioned above, they are not relevant. //
```

Do not build a diagnosis on them. The lines that matter come after and look
like `Screencasting with KMS` / `Found monitor for DRM screencasting`.

### A pty agent started from inside Claude Code poisons every shell it opens

Claude Code exports `CLAUDECODE` and a set of `CLAUDE_CODE_*` markers into the
processes it starts. `pty-agent.py` is usually launched from a terminal, and that
terminal is often one Claude Code is already running in — so the shell in the
headset inherited a child-session marker, and every `claude` started there ran
with transcript saving off and a warning on the banner.

The agent now drops `CLAUDECODE` and everything beginning with `CLAUDE_CODE_`
before exec'ing the shell. A shell in the headset is not a child of anything.

### A stale agent on the port makes the new one invisible

The second `pty-agent.py` failed to bind and died; the first one kept serving,
so testing a code change silently exercised the old code. Nothing about the
client's behaviour hints at this — it connects and works.

The bind failure is loud, but only in the agent's own output. Before concluding
that a change had no effect, check who actually holds the port:

```sh
ss -ltnp | grep 9103
```

Killing it by pattern is its own trap: `pkill -f pty-agent` matches the command
line of the shell running the `pkill`, and kills that instead. Kill by pid.

## Build

### The system had a JRE without `javac`

Gradle failed with `Toolchain installation ... does not provide the required
capabilities: [JAVA_COMPILER]`. Fixed by `apt install openjdk-21-jdk-headless`.

### Gradle caches toolchain detection

After installing the JDK the build kept failing with the same error until the
daemon was stopped:

```sh
./gradlew --stop
```

### EGL headers must come before `openxr_platform.h`

`openxr_platform.h` declares structures with `EGLDisplay`, `EGLConfig` and
`EGLContext` fields but does not pull in the EGL headers itself. Including it
first produces a wall of `unknown type name 'EGLDisplay'`.
