# Quest 3: capability probe results

Captured 2026-08-06 by milestone A on the actual device. Runtime Oculus
**v206.153.0**, Android 14 (API 34), codename `eureka`. The runtime exposes
71 extensions.

## The critical ones — confirmed

| Extension | Version | Why it matters |
|---|---|---|
| `XR_KHR_android_surface_swapchain` | v4 | Android Surface as a swapchain: zero-copy `MediaCodec` → compositor |
| `XR_KHR_composition_layer_cylinder` | v4 | curved layer instead of a flat quad |
| `XR_KHR_opengl_es_enable` | v10 | graphics backend |
| `XR_KHR_android_create_instance` | v3 | instance creation |

Both load-bearing pillars of the client exist. No fallback plan required.

## System limits

| | |
|---|---|
| Swapchain limit | 8192×8192 |
| **Layers in a composition** | **32** |
| Recommended per eye | 1680×1760 |
| Maximum per eye | 8192×8192 |
| Tracking | orientation + position |

**32 layers** removes the main constraint on multi-monitor work. A design where
each monitor is an independent layer with its own resolution and frame rate is
achievable without compromise; the encoder and the network will run out long
before the compositor does.

The recommended 1680×1760 per eye confirms the arithmetic in the README:
streaming 4K onto a 50°-wide monitor is pointless.

## Found beyond the plan

The probe surfaced extensions that improve several decisions.

### `XR_META_recommended_layer_resolution` v1

The runtime itself reports the optimal resolution for a layer with a given
placement and angular size.

This **replaces PPD arithmetic**. Instead of estimating "~25 PPD × 50° ≈ 1250
pixels", ask the compositor and get a number that accounts for the real optics,
distortion and current settings. The virtual display resolution on the Ubuntu
side should be chosen to match that answer rather than an a-priori calculation.

### `XR_FB_composition_layer_settings` v1

Per-layer sharpening and supersampling. Directly affects text legibility — turn
it on and measure on the first working layer.

### `XR_FB_swapchain_update_state_android_surface` v1

Changes surface-swapchain state on the fly. Layer resolution can change without
recreating the swapchain, which means changing the virtual display resolution
will not require reconnecting the stream.

### `XR_META_performance_metrics` v2

Compositor telemetry. Lets the final segment of the latency budget
("composition layer → photons") be measured from the inside rather than with a
stopwatch.

### Others, useful later

- `XR_FB_composition_layer_alpha_blend` v3 — blending with passthrough
- `XR_FB_display_refresh_rate` v1 — change display rate at runtime
- `XR_FB_android_surface_swapchain_create` v1 — extra creation flags
- `XR_META_automatic_layer_filter` v1 — automatic filter selection
- `XR_KHR_composition_layer_equirect2` v1 — fallback for very wide layouts
- `XR_META_virtual_keyboard` v1 — system keyboard, if input without a physical
  one is ever needed
- `XR_EXT_frame_synthesis`, `XR_FB_space_warp` — not needed for a desktop, the
  content is static

## Reproducing

```sh
cd client
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -n dev.butschster.linuxvr/android.app.NativeActivity
adb logcat -d -s linux-vr
```
