---
name: horizon-panel
description: Build and run Horizon OS apps that multitask as windows, and know when to choose an immersive OpenXR app instead. Use when an app must coexist with Discord, a browser or other panels; when an app unexpectedly takes over the whole headset; when adding a video panel backed by MediaCodec; or when pointer, drag or resize behaviour needs to come from the shell rather than be written by hand.
---

# Horizon OS: windows versus immersive apps

## The decision

Horizon OS runs an app in one of two ways, and the manifest decides which.

| | Window (panel) | Immersive |
|---|---|---|
| Coexists with other apps | yes | no, it owns the headset |
| Placement, resize, drag | the shell does it | you write it |
| Pointer | the shell's ray, as input events | you write ray intersection |
| Composition layer control | none | full: shape, angle, resolution |
| Typical size | a few hundred lines | thousands |

**Default to a window.** Only go immersive when you genuinely need control of the
composition layer geometry — a curved surface at a chosen angle and distance,
several independent layers, or per-layer sharpening. Everything else the shell
already does, better and consistently with other apps.

A common trap is building immersive first because it sounds like "the real VR
way", then reimplementing dragging, resizing and a pointer, and finally
discovering the app cannot sit next to a browser.

## What makes an app immersive

Any of these turns the app into an exclusive experience. To get a window,
declare **none** of them:

```xml
<uses-feature android:name="android.hardware.vr.headtracking" android:required="true" />
<category android:name="org.khronos.openxr.intent.category.IMMERSIVE_HMD" />
```

A plain Android activity with a `LAUNCHER` category gets a window.

## The panel pattern for streamed video

An `Activity` with a `SurfaceView`, and `MediaCodec` configured with the
holder's surface. The decoder writes straight into the surface, so a frame still
never passes through system memory.

```java
MediaFormat format = MediaFormat.createVideoFormat(MIMETYPE_VIDEO_AVC, w, h);
format.setInteger("low-latency", 1);
MediaCodec codec = MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC);
codec.configure(format, holder.getSurface(), null, 0);
codec.start();
```

Width and height are advisory — the decoder takes real geometry from the SPS.

Release output buffers as they decode, without pacing by presentation time. For
a live stream latency costs more than smoothness.

## Input from the shell's pointer

The controller ray arrives as ordinary Android input, but through **two**
different callbacks:

- `onGenericMotionEvent` — `ACTION_HOVER_MOVE` / `ACTION_HOVER_ENTER`, while the
  trigger is up
- `onTouchEvent` — `ACTION_DOWN` / `ACTION_UP`, once it is pressed

Handle both. Handling only touch gives a pointer that moves only while clicking.

Coordinates are view-relative, so the fraction of the panel maps one to one onto
the fraction of the displayed image. No geometry maths, and no question about
which monitor is which.

## Traps

**`NetworkOnMainThreadException`.** Input callbacks run on the UI thread and
Android forbids socket writes there. Queue the command and drain it from a
sender thread. Use a bounded queue that drops the oldest entry when full —
for a pointer that is right, since the newest position supersedes the old one,
and blocking the UI thread is never acceptable.

**Config files pushed the wrong way are unreadable.** `adb shell "echo x > path"`
creates the file mode 660 owned by `shell`, and the app cannot read it. Use
`adb push`, which creates it 644.

Also create the app's external files directory by launching the app once before
pushing into it.

**Passthrough is gated by the manifest.** Without

```xml
<uses-feature android:name="com.oculus.feature.PASSTHROUGH" android:required="false" />
```

the `XR_FB_passthrough` extension is not listed at all and the only environment
blend mode offered is `OPAQUE`. This looks exactly like "the device does not
support passthrough". It applies to immersive apps; panels sit in the shell's
environment already.

**Hand tracking must be declared even if unused.** Without

```xml
<uses-feature android:name="oculus.software.handtracking" android:required="false" />
```

Horizon OS blocks launch of an immersive app with a "controllers required"
dialog when the user is working with their hands. `am start` reports success and
the process dies immediately.

## Verifying it really is a window

Log the surface size on `surfaceChanged`. A window reports a modest size chosen
by the shell, such as `500x800`. Full display dimensions mean the app went
immersive.

Do not trust `am start` returning success — check the process:

```sh
adb shell pidof <package> && echo ALIVE || echo DEAD
```
