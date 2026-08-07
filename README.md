# linux-vr

**Your Linux desktop, in a Quest 3, with text you can actually read all day.**

Run one binary on your Linux machine. Put on the headset, open the app, and the
machine is already in the list — it announced itself on your network. Tap a
screen and it opens as a window floating in front of you, beside your browser,
beside Discord, beside anything else the headset is running. The controller ray
moves the Linux mouse. Dictation types into whatever has focus.

No cloud. No account. No subscription. Nothing leaves your network.

---

## What you actually get

**As many monitors as you want, without buying any.** Every physical screen on
the host becomes its own window you can place, resize and pin anywhere in the
space around you. A 27" monitor costs money and a piece of desk; a second window
in the headset costs a click.

**Text that stays readable.** This is the part everything else was built around,
and it is measured rather than claimed — see [the numbers](#the-part-that-makes-it-different-readable-text)
below. At the working geometry, roughly **284 columns of stock Ubuntu terminal
text** fit in comfortable view with no display scaling at all.

**Your machine stays where it is.** The desktop keeps running on the desk, with
its GPU, its RAM and its 40 open tabs. The headset is a display and a pointer,
not a computer that has to keep up. Walk to the sofa; the session does not care.

**It costs nothing when you are not looking.** No encoder runs until a window
opens. Close the window and the machine goes quiet again — which on a laptop is
the difference between warm and hot.

**A settings page that comes with it.** The server has a small web UI on
`http://localhost:9099`: what the headset would see, which screens are being
encoded right now, which parts of the machine are not working and why, and the
handful of settings worth changing. No config file to learn.

**It is a few hundred kilobytes.** The headset app is a **240 KB** APK. There is
no engine in it, no analytics, no login screen, and the whole thing is here to
read.

## Who this is for

You, if:

- you have a **Linux desktop or mini-PC** — Ubuntu 24.04 with GNOME on Wayland
  is what this is developed and measured against
- its GPU does **VAAPI H.264** encode, which every recent AMD and Intel iGPU does
- you have a **Quest 3 or 3S** and are willing to sideload an APK over `adb`
- you want to **read and write text** in the headset — code, terminals, docs —
  rather than watch a film in a cinema room

Not for you, if you want a polished Store app with a login and support, if your
host is Windows or a Mac, or if you are looking for a gaming streamer. This is a
personal tool built to a standard, made public because the measurements behind
it were not written down anywhere else.

## The part that makes it different: readable text

Most ways to see a desktop in a headset draw it into the **eye buffer** — the
same image the rest of the 3D scene is rendered into. That image then goes
through foveated rendering and is resampled again on its way to the lenses. For
a photo it does not matter. For a 14 px glyph it is the difference between text
and mush.

Here the decoded frame goes into a **composition layer** instead. The compositor
samples it at the panel's native resolution, after reprojection, bypassing the
eye buffer and foveated rendering entirely.

```
KMS capture ──► VAAPI (H.264) ──► network ──► MediaCodec ──► Surface ──► layer
```

Zero-copy the whole way: `MediaCodec` writes straight into the surface the
compositor reads, with no intermediate buffer in system memory. Confirmed on
device — the decoder reports `color-format: 2130708361` (`COLOR_FormatSurface`).

### What was measured

Live in the headset, 2560×1440 layer at 1.5 m, **51.2 pixels per degree**:

| Font size | Verdict | Angular size |
|---|---|---|
| 24 px | comfortable with margin | 0.47° |
| **20 px** | **comfortable** | **0.39°** |
| 18 px | readable | 0.35° |
| 16 px | borderline | 0.31° |
| below 16 px | unreadable | < 0.31° |

The threshold is the **angular size of the glyph**, not pixel density — verified
by stretching the layer until even 10 px text became readable.

That leads to a conclusion opposite to the intuitive one: **do not scale the
desktop up, widen the layer.**

| | Layer angle | Ubuntu scaling | Columns in view |
|---|---|---|---|
| narrow layer, large font | 50° | 125% | ~225 |
| **wide layer, stock font** | **66°** | **100%** | **~284** |

The second option shows more text at the same readability, needs no display
scaling — which still breaks applications on Linux — and spends less bitrate per
useful pixel.

One more consequence worth knowing: **the stream rate does not have to match the
headset rate.** The layer is world-locked and the compositor reprojects it at
90 Hz whether or not a new desktop frame has arrived. A 60 Hz monitor looks
correct in a 90 Hz headset, and a hiccup in the stream does not make the world
judder.

Full derivation: [`docs/readability.md`](docs/readability.md).

## Two halves, and why

This is a client-server product, not an app that happens to talk to a script.
That shape is deliberate and it is what makes it usable day to day.

```
  Quest 3                                  your Linux machine
┌──────────────────────┐                 ┌────────────────────────────────┐
│  the app             │                 │  linux-vr-server               │
│                      │                 │  (one binary, systemd service) │
│  server manager  ────┼── "who's there?"┼──►  answers on the network     │
│                  ◄───┼── "I am, 2 screens, here's what works"           │
│                      │                 │                                │
│  session screen  ────┼── what do you have? ─►  monitors, ports, health  │
│                      │                 │                                │
│  window per screen ◄─┼──── H.264 ──────┼───  encoder, started on demand │
│      pointer     ────┼──── x, y, click ┼──►  virtual mouse (uinput)     │
│      dictation   ────┼──── audio ──────┼──►  Whisper → your cursor      │
└──────────────────────┘                 └────────────────────────────────┘
```

**The server is a service, not a session.** It is one static binary with no
runtime dependencies, installed as a systemd user service. It survives reboots,
restarts if it dies, and does not care whether the headset is on your head or on
the shelf. Nothing has to be started by hand in the right order.

**The headset finds it by itself.** The app broadcasts a probe on the local
network and every server answers with its name, its user and how many screens it
has. Nothing is configured. A machine somewhere else — a workstation at the
office, a box on a VPN — is added once by address and remembered.

**One headset, several machines.** The server manager is a list, not a setting.
Two machines can be open at once, each with its own windows, side by side in the
same space.

**The server says what works before you look at a black rectangle.** A host
missing `ffmpeg`, missing a sudo rule for it, or missing `/dev/uinput` looks
identical from inside a window: a picture that never arrives, a pointer that
never moves. So the client asks first, and the session screen shows the sentence
instead of leaving you to guess.

**Encoding follows attention.** The server owns the video socket and starts an
encoder when a window connects, not when the service starts. Close the window
and it stops.

Details of every wire, port and payload: [`docs/server.md`](docs/server.md).

## Installing

### On the Linux machine

Grab the binary from [the latest release](https://github.com/vr-meta/linux-vr/releases/latest):

```sh
curl -L https://github.com/vr-meta/linux-vr/releases/latest/download/linux-vr-server-linux-amd64 \
    -o /usr/local/bin/linux-vr-server && chmod +x /usr/local/bin/linux-vr-server
```

Two permissions it cannot grant itself — the virtual mouse, and the screen
capture, which needs `CAP_SYS_ADMIN` and therefore a rule saying it may have it
without a password prompt nobody is there to answer:

```sh
sudo usermod -aG input $USER          # then log out and back in
echo "$USER ALL=(root) NOPASSWD: /usr/bin/ffmpeg" | sudo tee /etc/sudoers.d/linux-vr
```

Then check the machine rather than guessing at it:

```sh
linux-vr-server doctor
```

Every check in `doctor` is a failure that has actually happened here and cost an
evening. When it is green, start it:

```sh
linux-vr-server
```

To install it as a service instead, clone the repository and run `make install`.

### In the headset

Download `linux-vr.apk` from the same release, put the headset in developer mode,
and:

```sh
adb install -r linux-vr.apk
```

It appears in the app library under **Unknown Sources**.

## Using it

1. Open the app. Your machine is in the list, with its name, its user and how
   many screens it has.
2. Tap **connect**. The session screen shows what that machine offers.
3. Tap **open** next to a screen. A window appears with your desktop in it.
   Place and resize it the way you place any window in Horizon OS.
4. Point at it and pull the trigger — that is your Linux mouse.
5. Open **controls** for a window with a keyboard, dictation and the keys a
   headset otherwise cannot press: `Esc`, `Tab`, `Ctrl+C`, arrows.

Dictation needs somewhere to recognise speech. Open **`http://localhost:9099`**
on the Linux machine — the server's own settings page — and either paste an
OpenAI key or point it at a transcription service of your own. A local model
counts: run it as an HTTP service on `127.0.0.1` and no audio leaves the
building. There is a **Test it** button that tells you whether it works without
putting the headset back on.

Everything else works without any of that.

## What it deliberately does not do

Stated plainly, because finding these out by discovery is worse.

- **There is no authentication.** Anything on your network that can reach the
  input port can move your pointer. That is an honest description of a tool for
  one desk. On a network you do not control, bind it to an interface that is not
  exposed, or do not run it.
- **It mirrors physical monitors.** A virtual display at an arbitrary resolution
  is the obvious next step and is not built yet — so the shape of what you see is
  the shape of the screens actually plugged in.
- **A monitor plugged in while it runs needs a restart**, because the ports it
  serves are derived from the monitor list.
- **GNOME on Wayland**, because the monitor layout comes from mutter over D-Bus.
  Other compositors need a different reader; nothing else would have to change.
- **One watcher per screen.** A second client on the same monitor replaces the
  first rather than joining it.

## Its sibling: a terminal

If what you actually do in the headset is run a shell and an AI CLI in it, there
is a better answer than a video of a terminal:
[**vr-meta/linux-terminal**](https://github.com/vr-meta/linux-terminal).

It streams no video at all. The host sends the bytes the shell produces and the
headset draws the glyphs itself, at the panel's own resolution — nothing is
encoded, scaled or resampled on the way, so there is no quality question to
have. It also knows what is running in that shell and offers its keys.

The two servers coexist on one machine, answer the same kind of probe on
different ports, and share nothing else.

## Status

Working end to end, in daily use.

- [x] KMS + DMA-BUF capture, VAAPI encode — measured **160 fps at 1440p**, 1.78×
      headroom over the 90 Hz target
- [x] Live H.264 over TCP, reconnecting, encoder started on demand
- [x] One binary, systemd unit, `doctor`
- [x] Discovery on the local network; servers elsewhere added by address
- [x] Connection manager and session screen in the app
- [x] Controller ray drives the Linux pointer, with drag suppressed below a
      threshold so a trembling hand does not turn a click into a selection
- [x] Keyboard and dictation into the Linux cursor
- [ ] Virtual display at an arbitrary resolution, instead of mirroring
- [ ] Multiple independent monitor layers in one immersive view
- [ ] Windows keep the stream's aspect ratio
- [ ] End-to-end latency measured rather than budgeted — the target is
      **40–60 ms** click-to-photon and only the encoder stage is confirmed
      ([`docs/latency-budget.md`](docs/latency-budget.md))

## Building from source

Server: Go 1.24. Client: JDK 21 and Android SDK 34.

```sh
git clone https://github.com/vr-meta/linux-vr
cd linux-vr
make server       # host/linux-vr-server
make client       # the APK
make install      # binary + systemd user service
make dev          # rebuild onto the attached headset, then run the server
make doctor       # what this machine is missing
make monitors     # the monitor table every part of the system agrees on
```

`client/local.properties` with `sdk.dir` is not in git — create it locally.

### Layout

```
host/             the Go server: capture, pointer, voice, discovery, control API
client/app        the app — server manager, then a window per screen
client/immersive  a native OpenXR client, kept for measurements
packaging/        the systemd user unit
docs/             measurements, decisions, traps — read before changing anything
```

`client/immersive` came first, and every measurement in `docs/` was made with it
— it is the reason the readability numbers exist. But once it worked, the thing
that mattered most in daily use turned out to be **coexisting with other apps**,
and a plain 2D app gets that for free along with dragging, resizing and a
pointer, none of which then need writing. It stays in the tree because it keeps
full control of the composition layer, which is what any future work on multiple
monitor layers will need.

## Hardware everything was measured on

Host — a Beelink EQ mini PC: Ryzen 7 6800U, Radeon 680M (VCN 3.0), Ubuntu 24.04,
GNOME 46 on Wayland. Headset — Quest 3, Horizon OS v206.
Full numbers in [`docs/host-baseline.md`](docs/host-baseline.md).

## Documentation

| | |
|---|---|
| [`server.md`](docs/server.md) | the server: install, configuration, every protocol |
| [`readability.md`](docs/readability.md) | the readability measurement and the geometry that follows |
| [`device-probe.md`](docs/device-probe.md) | what the Quest 3 runtime offers, layer limits |
| [`host-baseline.md`](docs/host-baseline.md) | host hardware, encoder benchmarks |
| [`latency-budget.md`](docs/latency-budget.md) | per-stage latency budget |
| [`gotchas.md`](docs/gotchas.md) | traps: Horizon OS, Sunshine, amdgpu, build |
| [`references.md`](docs/references.md) | where to read instead of guessing |
| [`voice-control.md`](docs/voice-control.md) | voice control design |

## License

MIT.
