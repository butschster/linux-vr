# linux-vr

A VR desktop for Ubuntu on Quest 3 / 3S. A native OpenXR client that presents the
desktop as a **compositor layer**, not as a panel inside a VR scene.

This is a personal tool, not a Horizon Store product. The repository is public
because the measurements and platform traps collected along the way weren't
written down anywhere else — they live in [`docs/`](docs/).

## Why write another client

Existing solutions show the desktop on a 2D panel: it renders into the eye
buffer, goes through foveated rendering, and gets resampled a second time.
For text that is the difference between readable and unreadable.

The frame has to go into a **composition layer**: the layer is sampled by the
compositor at the panel's native resolution after reprojection, bypassing the
eye buffer and FFR entirely.

```
KMS capture ──► VAAPI (H.264) ──► network ──► MediaCodec ──► Android Surface
                                                                  │
                                            XR_KHR_android_surface_swapchain
                                                                  │
                                                 XrCompositionLayerCylinderKHR
                                                                  ▼
                                                            compositor
```

Zero-copy from decoder to compositor: `MediaCodec` writes straight into the
swapchain surface, with no intermediate buffer in system memory. Confirmed on
device — the decoder reports `color-format: 2130708361` (`COLOR_FormatSurface`).

A consequence of this design: **the stream rate does not have to match the
headset rate.** The layer is pinned to world coordinates and the compositor
reprojects it every frame at 90 Hz, whether or not a new desktop frame arrived.

Native, no Unity: the scene is empty, there are zero objects, the whole picture
lives in the layer. An engine would add ~200 MB of APK and a JNI boundary
exactly where zero-copy is needed. The built APK is **2.5 MB**.

## Main result: how to make text readable

Full derivation in [`docs/readability.md`](docs/readability.md).

Readability is governed by the **angular size of the glyph**, not by pixel
density:

```
density:       R = W / θ            pixels per degree
angular size:  α = h·θ / W          degrees per glyph
```

The measured comfort threshold on Quest 3 is **α ≈ 0.39°**, and it does not
depend on the layer angle. What depends on the angle is which font size lands
inside it.

This leads to a conclusion opposite to the intuitive one: **don't scale the
desktop up, widen the layer.**

| Option | Layer angle | Ubuntu scaling | Columns in view |
|---|---|---|---|
| narrow layer, large font | 50° | 125% | ~225 |
| **wide layer, stock font** | **66°** | **100%** | **~284** |

The second option shows more text at the same readability, needs no display
scaling (which still breaks applications on Linux), and spends less bitrate per
useful pixel.

Verified against a screenshot of a real desktop at stock settings
(`Ubuntu Sans 11`, `Ubuntu Sans Mono 13`, scale 1.0): at 66° the whole thing
reads comfortably.

The ceiling for a single layer is **60–75°**. Beyond that the edges fall into
the worst part of the lenses and reading them requires turning your head, not
moving your eyes. More area means more layers — the Quest 3 compositor allows 32.

## Status

- [x] Host: KMS + DMA-BUF capture, VAAPI encode, measured 160 fps at 1440p
- [x] Runtime capability probe — [`docs/device-probe.md`](docs/device-probe.md)
- [x] Client: `MediaCodec` → surface swapchain → cylinder layer
- [x] Readability measurement and working geometry — [`docs/readability.md`](docs/readability.md)
- [x] Pointer: controller ray → desktop coordinates, cursor as its own layer
- [ ] Live stream instead of a file
- [ ] Input back-channel
- [ ] Virtual display at arbitrary resolution
- [ ] Voice control — [`docs/voice-control.md`](docs/voice-control.md)
- [ ] Multiple independent monitor layers

## Building

Requires JDK 21, Android SDK 34, NDK 27.2, CMake 3.22. The OpenXR loader comes
from Maven Central (`org.khronos.openxr:openxr_loader_for_android`) as a prefab
package — Meta's vendor SDK is not needed.

```sh
cd client
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.butschster.linuxvr/android.app.NativeActivity
adb logcat -s linux-vr
```

Test material is generated on the host:

```sh
./host/make-test-pattern.sh
adb push assets/testpattern.mp4 \
    /sdcard/Android/data/dev.butschster.linuxvr/files/
```

## Controls

| | |
|---|---|
| right stick, forward/back | layer distance, 0.8–3 m |
| right stick, left/right | angular width, 25–110° |
| A button | reset to 1.5 m and 66° |
| right controller ray | pointer |

## Hardware everything was measured on

Host — a Beelink EQ mini PC: Ryzen 7 6800U, Radeon 680M (VCN 3.0), Ubuntu 24.04,
GNOME 46 on Wayland. Headset — Quest 3, Horizon OS v206.
Full numbers in [`docs/host-baseline.md`](docs/host-baseline.md).

## Documentation

| | |
|---|---|
| [`readability.md`](docs/readability.md) | text readability measurement, working geometry |
| [`device-probe.md`](docs/device-probe.md) | what the Quest 3 runtime offers, layer limits |
| [`host-baseline.md`](docs/host-baseline.md) | host hardware, encoder benchmarks |
| [`latency-budget.md`](docs/latency-budget.md) | per-stage latency budget |
| [`gotchas.md`](docs/gotchas.md) | traps: Horizon OS, Sunshine, amdgpu, build |
| [`voice-control.md`](docs/voice-control.md) | voice control design |

## License

MIT.
