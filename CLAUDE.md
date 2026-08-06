# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Language

**Everything committed to this repository is written in English** — docs, code
comments, log strings, commit messages. The repository is public and mixed
language makes it useless to an outside reader.

Conversation with the repository owner happens in Russian. That does not change
what goes into the files.

## What this project is

A VR desktop for Ubuntu on Quest 3 / 3S. A personal tool, **not a Horizon Store
product** — VRC compliance, entitlement checks, guardian-exit handling and the
rest of the store scaffolding are out of scope.

## What is written here and what is reused

Custom code is written **only where off-the-shelf solutions lose
architecturally** — that means the in-headset client. Everything else is reused.

| Layer | Choice |
|---|---|
| Host capture + encode | KMS + DMA-BUF → VAAPI |
| Transport | currently a direct TCP pipe; eventually Sunshine + `moonlight-common-c` |
| **In-headset client** | **custom, native OpenXR** |

If you feel tempted to write something custom for the first two rows, that is
almost always the wrong move. The one exception is multiple independent monitor
surfaces, which Sunshine does not provide.

## Layout

```
client/     native OpenXR app (Gradle + CMake + NDK, no Unity)
host/       Ubuntu-side scripts: test material generation, capture, streaming
docs/       measurements, decisions, traps — read before changing anything
vendor/     external sources, not in git (see docs/gotchas.md)
assets/     generated test videos, not in git
```

### Key client files

| | |
|---|---|
| `xr_app.cpp` | session, layers, input, cursor — the core |
| `video_decoder.cpp` | `MediaCodec` from a file into an `ANativeWindow` |
| `stream_decoder.cpp` | same, but from a live TCP H.264 stream |
| `probe.cpp` | runtime capability diagnostics, runs at startup |
| `egl_context.cpp` | pbuffer context, exists only to bind OpenXR |

## Principles that must not be broken

**Frames go into a composition layer, never onto a quad in the scene.** This is
the reason the project exists. The layer is sampled by the compositor at the
panel's native resolution, bypassing the eye buffer and foveated rendering. Any
proposal to "render into a texture and show it in the scene" is a regression.

**Zero-copy from decoder to compositor.** `MediaCodec` writes directly into the
surface obtained from `xrCreateSwapchainAndroidSurfaceKHR`. Copying a 1440p
frame through the CPU costs 2–4 ms, which the budget does not have.

**The cursor is its own layer.** It is not drawn into the desktop image. It
updates at headset rate and therefore stays instant regardless of stream lag.

**The scene is empty.** No custom geometry is rendered at all. The EGL context
exists solely because creating an OpenXR session requires one.

**Measure, don't assume.** Every number in `docs/` was obtained on this
hardware. A new claim about performance or readability must rest on a
measurement, not on a vendor spec sheet.

## Build and run

```sh
cd client
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.butschster.linuxvr/android.app.NativeActivity
adb logcat -s linux-vr
```

`local.properties` with `sdk.dir` is not in git — create it locally.

### Debugging techniques that saved time

**Log lines get evicted from the ring buffer.** The Horizon OS compositor can
flood logcat with warnings in seconds. Capture live by tag, started **before**
launching the app, rather than reading `logcat -d` afterwards:

```sh
adb logcat -c && adb logcat -s linux-vr > log.txt &
adb shell am start -n dev.butschster.linuxvr/android.app.NativeActivity
```

**A successful `am start` means nothing.** Verify by fact:

```sh
adb shell pidof dev.butschster.linuxvr && echo ALIVE || echo DEAD
```

**The headset sleeps when taken off.** `mWakefulness=Asleep`, the activity
pauses, the session never starts. That is not a bug — check it before looking
for the cause in code:

```sh
adb shell dumpsys power | grep mWakefulness=
```

**Gradle caches toolchain detection.** After installing a JDK you need
`./gradlew --stop`, otherwise the same error repeats on a fixed system.

## Never answer platform questions from memory

Horizon OS changes fast, gates capabilities behind manifest declarations that
are easy to forget, and diverges from the base OpenXR specification. Two claims
made from memory in this project were simply false — see
[`docs/references.md`](docs/references.md) for which and why.

Before stating how the platform behaves, consult
[Meta's agentic tools](https://github.com/meta-quest/agentic-tools) or the
documentation, or measure on the device. `docs/references.md` lists where to
look for each topic.

## Platform traps

The full list is [`docs/gotchas.md`](docs/gotchas.md); add to it as you find
more. The most important ones:

**Horizon OS reports API 34 but ships a reduced set of system services.** Check
service availability by fact, not via `Build.VERSION`. Every version of
Moonlight crashes on this (`GameManager` returns `null`).

**Without declaring `oculus.software.handtracking` the system blocks launch**
with a "controllers required" dialog. You do not have to use hand tracking —
the declaration itself is what matters.

**`xrCreateSwapchainAndroidSurfaceKHR` requires zeros** in `format`,
`sampleCount`, `faceCount`, `arraySize`, `mipCount`. Fill in only `width` and
`height`.

**Include EGL headers before `openxr_platform.h`**, otherwise you get a wall of
`unknown type name 'EGLDisplay'`.

**Vulkan Video encode on RADV hangs the GPU.** Sunshine picks it by default; on
Rembrandt this causes an amdgpu reset and throws you out of the graphical
session. Force `encoder = vaapi`.

## Style

- A comment explains **why**, not what. "Zero-copy, otherwise 2–4 ms per copy"
  is useful; "create the swapchain" is not.
- Numbers in comments and docs carry their source: a measurement, a conclusion
  drawn from one, or a specification.
- Do not add a dependency when what is already there will do. The cursor is
  drawn with scissor rects and `glClear` for exactly this reason — shaders and
  geometry are not worth it for a crosshair.
