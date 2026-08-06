# References

Read these instead of trusting recollection. Every entry here exists because
something in this project was once explained from memory and turned out to be
wrong.

## Rule

**Platform behaviour is not something to answer from memory.** Horizon OS
changes fast, its capabilities are gated behind manifest declarations that are
easy to forget, and its OpenXR surface differs from the base specification.
Before stating how the platform behaves — especially about multitasking,
passthrough, panels or permissions — open the documentation or measure on the
device.

Two claims made in this project from memory that were simply false:

- "Virtual Desktop is also exclusive, so nothing can run beside it." The user
  runs Discord, a browser and a streamed desktop at the same time.
- "Passthrough is unavailable, the runtime only offers OPAQUE." It was gated
  behind one line in the manifest.

## Start here: Meta's own agentic tooling

[github.com/meta-quest/agentic-tools](https://github.com/meta-quest/agentic-tools)

Meta ships official agent skills and tools for Horizon OS development. For any
platform question this is the authority to reach for before the prose docs, and
long before recollection.

- **`metavr` CLI** (`npx -y metavr`) — device management, app control, file
  operations, documentation search, performance analysis.
- **MCP server**, 40+ tools, embedded in `metavr` — lets an assistant talk to the
  headset directly instead of shelling out to `adb` and guessing.
- **40+ agent skills**, including several that map onto this project:
  `hz-android-2d-porting` (exactly what the panel client is),
  `hz-spatial-sdk` (curved and multiple panels), `hz-perfetto-debug` and
  `hz-simpleperf-debug` (performance), `hz-immersive-designer`.

The two skills in `.claude/skills/` here are project-specific — this stack, its
host scripts, its failure modes. Platform behaviour belongs to Meta's skills.

## Meta Horizon OS

| | |
|---|---|
| [Developer center](https://developers.meta.com/horizon/) | entry point for everything below |
| [OpenXR support for Quest](https://developers.meta.com/horizon/documentation/native/android/mobile-openxr/) | which extensions the runtime provides and how they differ from the base spec |
| [OpenXR Mobile SDK](https://developers.meta.com/horizon/documentation/native/android/mobile-intro) | native Android XR development |
| [Mobile OpenXR samples](https://developers.meta.com/horizon/documentation/native/android/mobile-openxr-sample/) | working code for surface swapchains, passthrough, layers |
| [Meta OpenXR SDK samples](https://developers.meta.com/horizon/documentation/native/native-openxr-sdk-sample/) | the sample catalogue |
| [API reference](https://developers.meta.com/horizon/reference/) | Meta-specific extensions |
| [Platforms overview](https://developers.meta.com/horizon/discover/platforms/) | immersive versus panel apps, engines, WebXR |
| [2D apps and Meta Spatial SDK](https://developers.meta.com/horizon/discover/2d-apps-meta-spatial/) | how a plain Android app becomes a window, and the path to spatial panels |

The **Spatial SDK** is the Kotlin framework for spatial panels — the middle
ground between a plain 2D window and a fully immersive app. Relevant if the
panel client ever needs curved surfaces or several panels it controls itself.

## OpenXR

| | |
|---|---|
| [Specification](https://registry.khronos.org/OpenXR/specs/1.1/html/xrspec.html) | the authority on layer semantics, spaces and structure fields |
| [Reference pages](https://registry.khronos.org/OpenXR/specs/1.1/man/html/) | per-call valid usage — read these before assuming what a field means |
| [OpenXR-SDK](https://github.com/KhronosGroup/OpenXR-SDK) | loader and headers |

Field semantics are worth checking rather than inferring. `XrSwapchainCreateInfo`
for an Android surface swapchain must have zeroed `format`, `sampleCount`,
`faceCount`, `arraySize` and `mipCount` — filling them in the habitual way is
rejected outright.

## Android

| | |
|---|---|
| [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) | decoder lifecycle, buffer handling, `low-latency` |
| [NDK media APIs](https://developer.android.com/ndk/reference/group/media) | `AMediaCodec`, `AMediaExtractor` for the native client |
| [SurfaceView](https://developer.android.com/reference/android/view/SurfaceView) | the panel client's output surface |
| [MotionEvent](https://developer.android.com/reference/android/view/MotionEvent) | hover versus touch, which the controller ray uses in turn |

## Linux side

| | |
|---|---|
| [VA-API](https://intel.github.io/libva/) | hardware encode entry points |
| [FFmpeg kmsgrab](https://ffmpeg.org/ffmpeg-devices.html#kmsgrab) | KMS capture, its CRTC selection and its limits |
| [libinput](https://wayland.freedesktop.org/libinput/doc/latest/) | how an absolute pointer device is interpreted |
| [Linux uinput](https://www.kernel.org/doc/html/latest/input/uinput.html) | creating virtual input devices |

The `uinput` ioctls `UI_SET_EVBIT` and relatives take the bit as an immediate
value, not a pointer to one — the kernel documentation is explicit and the
mistake costs an opaque `EINVAL`.
