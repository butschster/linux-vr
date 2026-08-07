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

## What is not here

**The terminal client is a different product**, in
[vr-meta/linux-terminal](https://github.com/vr-meta/linux-terminal). It streams
no video at all — the host sends the bytes a shell produces and the headset
draws the glyphs — so it shares neither a wire nor a dependency with this. Do
not add a pty, a shell or a context bar here; that work belongs there.

The two servers coexist on one machine and answer the same kind of discovery
probe, on different ports and saying which service they are.

## Layout

```
host/             the Go server: capture, pointer, voice, discovery, control API
client/app        the app — server manager, then a window per screen
client/immersive  native OpenXR app (Gradle + CMake + NDK, no Unity)
packaging/        the systemd user unit
docs/             measurements, decisions, traps — read before changing anything
vendor/           external sources, not in git (see docs/gotchas.md)
assets/           generated test videos, not in git
```

### Key server files

| | |
|---|---|
| `internal/monitors/` | the monitor table everything else agrees on; mutter + DRM |
| `internal/capture/` | one ffmpeg per connection, started when a window opens |
| `internal/input/` | uinput absolute pointer, one region per connection |
| `internal/voice/` | Whisper, the clipboard, and the keys a headset cannot press |
| `internal/control/` | discovery datagram and `/v1/info` |

### Key client files

| | |
|---|---|
| `ServersActivity.java` | the front door: discovery, saved servers, typed addresses |
| `SessionActivity.java` | one machine, what it offers, a button per window |
| `StreamView.java` | `MediaCodec` into a `SurfaceView`, and pointer events back |
| `InputBar.java` | keyboard and dictation, in a window of its own |

### Key immersive-client files

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

**The client asks the server what it can do; it does not assume.** A host without
`ffmpeg`, without the sudo rule, or without `/dev/uinput` looks identical from
inside a window — black picture, dead pointer. `/v1/info` reports what works and
why not, and the session screen shows the sentence. Any new capability follows
the same shape.

**Nothing is encoded while nobody is watching.** The server owns the stream
listener and starts an encoder on accept. An encoder that runs whether or not a
window is open is a laptop that is hot for no reason.

**Measure, don't assume.** Every number in `docs/` was obtained on this
hardware. A new claim about performance or readability must rest on a
measurement, not on a vendor spec sheet.

## Build and run

```sh
make run          # the server in this terminal
make doctor       # what this machine is missing, before blaming the headset
make dev          # rebuild the app onto the headset, then run the server
adb logcat -s linux-vr
```

`client/local.properties` with `sdk.dir` is not in git — create it locally.

The release signing key lives at `~/.config/linuxvr/release.keystore`, outside
the repository, and the same key is a repository secret so CI signs identically.
Losing it means every headset has to uninstall before the next release will
install.

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

Before stating how the platform behaves, consult the sources below, or measure
on the device.

### Where to look, by question

| Question | Go to |
|---|---|
| Anything about the platform, first stop | [meta-quest/agentic-tools](https://github.com/meta-quest/agentic-tools) — official CLI, MCP server and 40+ skills, including `hz-android-2d-porting` and `hz-spatial-sdk` |
| Panel versus immersive, switching between them | [Meta-Spatial-SDK-Samples](https://github.com/meta-quest/Meta-Spatial-SDK-Samples) → **HybridSample** |
| Video or streaming on a panel | same repo → **MediaPlayerSample**, **PremiumMediaSample** |
| Controls that look native | same repo → **UISetSample** |
| Minimal Spatial SDK app with panels | [Meta-Spatial-SDK-Templates](https://github.com/meta-quest/Meta-Spatial-SDK-Templates) → **StarterTemplate** |
| Surface swapchains, passthrough, composition layers in C++ | [Mobile OpenXR samples](https://developers.meta.com/horizon/documentation/native/android/mobile-openxr-sample/) |
| Which extensions Quest actually provides | [OpenXR support for Quest](https://developers.meta.com/horizon/documentation/native/android/mobile-openxr/) |
| Exact semantics of an OpenXR struct field | [OpenXR reference pages](https://registry.khronos.org/OpenXR/specs/1.1/man/html/) — read, do not infer |
| `MediaCodec`, `SurfaceView`, `MotionEvent` | [developer.android.com](https://developer.android.com/reference/android/media/MediaCodec) |
| Store, purchases, achievements, presence | Platform SDK samples — **irrelevant to this project**, see `docs/references.md` |

Full list with notes: [`docs/references.md`](docs/references.md).

**Answered, with the confidence stated:** a Spatial SDK app places its panels
precisely, and the price is coexistence — the window layout you multitask in is
the Home environment, and `createPanelEntity`'s freedom applies inside your own
immersive scene. That is assembled from five documented facts, not quoted from
one sentence; the sources and the assembly are in
[`docs/references.md`](docs/references.md).

The same section settles the related question: **a 2D app cannot place its own
window at all.** Size is the only window property it controls. Do not spend time
looking for an API; the user places windows, and "pin to space" makes it stick.

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
