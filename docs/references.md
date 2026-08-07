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

### Meta Spatial SDK — relevant, and the open question

| | |
|---|---|
| [Meta-Spatial-SDK-Samples](https://github.com/meta-quest/Meta-Spatial-SDK-Samples) | 13 samples |
| [Meta-Spatial-SDK-Templates](https://github.com/meta-quest/Meta-Spatial-SDK-Templates) | StarterTemplate: an immersive scene with panels that talk to each other |

Worth reading first:

- **HybridSample** — "begin with a standard Android-based 2D panel experience
  and switch between an immersive experience". Literally the fork this project
  hit: panel versus immersive, treated as a switch rather than a permanent
  choice.
- **MediaPlayerSample**, **PremiumMediaSample** — video on panels, including
  streaming.
- **UISetSample** — the Horizon OS UI Set, for controls that look native.

Requirements: Quest v69+, JDK 17, Android Studio Narwhal or newer, and the Meta
Spatial Editor.

**Open question, not to be answered from memory:** whether a Spatial SDK app
coexists with other apps or takes the headset. The template is described as an
immersive scene containing panels, which suggests the panels are inside our app
rather than windows in the shell. If that is so, Spatial SDK buys controllable
curved panels — the thing the immersive client was written for — at the cost of
the multitasking the panel client was adopted for. Verify before building on it.

### Checked and not relevant

Recorded so nobody opens them again with the same question.

| | |
|---|---|
| [Meta-Horizon-Platform-SDK-Samples](https://github.com/meta-quest/Meta-Horizon-Platform-SDK-Samples) | one sample, in-app purchases via Horizon Billing |
| [horizon-platform-sdk-samples](https://github.com/meta-quest/horizon-platform-sdk-samples) | 16 samples: achievements, IAP, leaderboards, presence, users, consent |

Both cover the **Platform SDK** — store and social plumbing. Nothing on panels,
windowing, multitasking, video or input. This project is a personal tool with no
store presence, so none of it applies. For code that does apply, use the Mobile
OpenXR samples listed below.

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

## Window placement for a 2D app: settled, with sources

Asked in earnest, researched against Meta's own documentation, and the answer is
**no** — a 2D panel app cannot influence where its window appears in the room.
Size is the only window property an app controls.

- Meta's complete feature surface for Android apps lists one window entry, "Panel
  sizing": default dimensions via `<layout>`, user resizing, multiple panels.
  <https://developers.meta.com/horizon/documentation/android-apps/features-overview/>
- Placement belongs to the shell: "Window affordances (control bar, edge handles,
  resize handles) are provided automatically by Horizon OS for Android panel apps."
  <https://developers.meta.com/horizon/design/windows_implementation/>
- "follow me", "theater view" and "pin to space" are actions in the system control
  bar, with no app-facing API documented.
  <https://developers.meta.com/horizon/design/windows/>
- A launched 2D window arrives attached to the Navigator, which is in front of the
  user — so it already appears where you wanted it, without the app asking.
  <https://www.meta.com/help/quest/542427545314119/>
- `ActivityOptions.setLaunchBounds` is ignored without
  `FEATURE_FREEFORM_WINDOW_MANAGEMENT` or `FEATURE_PICTURE_IN_PICTURE`. **Measured
  on this Quest 3: neither is reported** (`adb shell pm list features`), so that
  avenue is closed by fact and not by inference. It is a 2D pixel rectangle in any
  case and cannot express distance or facing.

`android:gravity` in `<layout>` is an Android 2D free-form concept and carries no
depth or yaw. Meta documents only `defaultWidth` and `defaultHeight`; whether it
honours `gravity`, `minWidth` or `minHeight` at all is **not documented and not
measured**.

The one placement Meta does document for a second window is
`FLAG_ACTIVITY_LAUNCH_ADJACENT`: "the panel activity will be launched next to the
actively running activity from your app". `documentLaunchMode` and
`FLAG_ACTIVITY_NEW_DOCUMENT` appear nowhere in Meta's documentation — they work,
but where the window lands is unspecified.
<https://developers.meta.com/horizon/documentation/spatial-sdk/hybrid-apps-overview/>

Only three windows attach to the Navigator; beyond that the user detaches them
into the room.

### The Spatial SDK question, previously recorded as unresolved

Meta Spatial SDK **does** place panels precisely — `Entity.createPanelEntity` takes
a pose in metres.
<https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-spawn/>

The cost is the thing that was unknown. Assembling it from four documented facts:
activities declare `com.oculus.intent.category.2D` or `.VR`; an immersive activity
is an `AppSystemActivity extends VrActivity`; Home is "the landing point... when
exiting an immersive app"; cooperative mode is defined only for panel and immersive
activities **of the same app**; and another app enters an immersive app only via
`OVERLAY_LAUNCHER`.

So: the free window layout you multitask in **is** the Home environment, and
`createPanelEntity`'s placement freedom applies to panels inside your own immersive
scene. Controllable placement and immersive mode come together, and they cost
coexistence with other apps.

**Stated honestly:** no single sentence in Meta's documentation says "an immersive
app takes over the headset". That conclusion is strongly implied by the five quotes
above, not quoted. Treat it as such.

### The metavr CLI does not run on Linux

`npx metavr` refuses with "unsupported platform 'linux-x64' (supported:
darwin-arm64, darwin-x64, win32-x64)". The skills in
[meta-quest/agentic-tools](https://github.com/meta-quest/agentic-tools) are plain
markdown and are readable from a clone; only the CLI and its doc-search are
unavailable here.
