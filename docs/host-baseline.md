# Host baseline, measured

Captured 2026-08-06 on the target machine. Everything below is a fact from the
machine, not a vendor specification.

## Hardware

| | |
|---|---|
| Model | Beelink EQ (DMI: `AZW` / `EQ`), chassis type 3 — desktop |
| CPU | AMD Ryzen 7 6800U, 16 threads |
| GPU | Radeon 680M (Rembrandt, `1002:1681`), VCN 3.0 |
| RAM | 27 GiB |
| Disk | 236 GiB free |
| Ethernet | 2× Realtek RTL8111 — both `NO-CARRIER` |
| Wi-Fi | Intel AX200 (Wi-Fi 6, no 6 GHz), 5 GHz ch40 |

The network is deliberately out of scope: a working Wi-Fi↔Wi-Fi reference has
already been confirmed on Windows.

## OS and session

| | |
|---|---|
| OS | Ubuntu 24.04.4 LTS, kernel 7.0.0-28-generic |
| Session | **Wayland**, GNOME Shell 46.0 |
| Mesa | 25.2.8 (radeonsi) |
| PipeWire | 1.0.5, `pipewire` + `wireplumber` active |
| Portals | `gnome.portal`, `gtk.portal` (no `wlr`) |
| gnome-remote-desktop | 46.3 installed, disabled |

## Encoder

VCN 3.0, rings `vcn_enc_0.0` / `vcn_enc_0.1`. Firmware ENC 1.30, DEC 3.

VA-API (`renderD128`, after installing `mesa-va-drivers`):

- `VAProfileH264ConstrainedBaseline` — `VAEntrypointEncSlice`
- `VAProfileH264Main` — `VAEntrypointEncSlice`
- `VAProfileH264High` — `VAEntrypointEncSlice`
- `VAProfileHEVCMain` — `VAEntrypointEncSlice`
- `VAProfileHEVCMain10` — `VAEntrypointEncSlice`

**No AV1 encode** — expected for VCN 3.0 (decode only; encode arrives with
VCN 4).

`EncSlice` matters: it allows emitting slices as they become ready instead of
waiting for the end of the frame.

### Throughput benchmark

```
ffmpeg -vaapi_device /dev/dri/renderD128 \
  -f lavfi -i testsrc2=size=2560x1440:rate=90 -t 10 \
  -vf format=nv12,hwupload \
  -c:v h264_vaapi -profile:v high -rc_mode CBR -b:v 100M -bf 0 -g 9999 -f null -
```

Result: **160 fps** sustained — 1.78× headroom over the 90 Hz target.

The estimate is pessimistic: the test generated frames on the CPU and uploaded
them via `hwupload`. In the real pipeline KMS hands over a DMA-BUF directly and
no copy through system memory happens.

## Displays

| Connector | Status | Mode |
|---|---|---|
| `HDMI-A-1` | connected | 2560×1440 |
| `HDMI-A-2` | connected | 2560×1440 |
| `DP-1` … `DP-7` | disconnected | — |
| `Writeback-1` | unknown | — |

**Careful with the DP connectors.** The seven free ones are display-controller
slots, not physical ports — the case exposes 2× HDMI and USB-C. Forcing
`video=DP-N:e` on amdgpu requires successful link training, which will not
happen without a real sink. The EDID-injection route for a virtual display
cannot be treated as solved, only as worth trying.

## Input

`/dev/uinput` is `root:input` and the user is in the `input` group. Input
injection via evdev works without sudo and independently of the compositor.

## Installed for this project

- `mesa-va-drivers`, `vainfo`, `ffmpeg` 6.1.1
- `sunshine` 2026.516.143833 (`.deb` from GitHub releases, not the distro repo)
- `adb` 1.0.41, `ninja-build`
- Android SDK / NDK 27.2.12479018 / CMake 3.22.1

### Sunshine

The unit is called `app-dev.lizardbyte.app.Sunshine.service` (not
`sunshine.service` — renamed in later releases). It is wired into
`graphical-session.target`.

At startup it selects:

- **Capture:** KMS + DMA-BUF (`Screencasting with KMS`), zero-copy, no portal
  dialog — this works thanks to the `CAP_SYS_ADMIN` set by the package's
  postinst.
- **Encoder:** Vulkan Video (`h264_vulkan`, `hevc_vulkan`) on RADV, **not**
  VAAPI. `av1_vulkan` fails to open — the hardware cannot do it.

The Vulkan path on RADV wedged the GPU in testing; see
[`gotchas.md`](gotchas.md). Force `encoder = vaapi`.

Limitation: KMS capture grabs a **physical** output. Until a virtual display
exists, the stream mirrors one of the two 2560×1440 monitors.
