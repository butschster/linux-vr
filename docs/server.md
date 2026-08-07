# The server

One binary on the Linux machine whose desktop you want to see. It captures each
monitor, injects the pointer the headset's ray drives, turns dictation into text
at the cursor, and answers discovery probes so the headset finds it without
anyone typing an address.

Code: [`host/`](../host). Built with `make server`, installed with `make install`.

## Why a binary and not the scripts it replaces

The previous version was four Python scripts and a shell script, each on its own
port, each started by hand in its own terminal. That is the right shape for
finding out whether an idea works, and the wrong shape for something you install
on a machine and forget about. A server has to survive a reboot, say what is
wrong with it, and not depend on what the distribution decided `python3` means
this year.

The rewrite is Go for one reason worth stating: everything the Python did was
`ioctl`, `/proc` and sockets, and all three map across without loss. Nothing
clever was ported, so nothing clever broke.

## What runs where

```
 headset                                     Linux host
┌───────────────────┐                       ┌──────────────────────────────┐
│ ServersActivity   │──── UDP probe ───────►│ :9099  discovery + control   │
│                   │◄─── announcement ─────│                              │
│ SessionActivity   │──── GET /v1/info ────►│                              │
├───────────────────┤                       ├──────────────────────────────┤
│ PanelActivity     │◄─── H.264 ────────────│ :9100  monitor 0             │
│   MediaCodec      │◄─── H.264 ────────────│ :9110  monitor 1             │
│   pointer ────────┼──── commands ────────►│ :9101  uinput pointer        │
├───────────────────┤                       ├──────────────────────────────┤
│ ControlActivity   │──── PCM / text ──────►│ :9102  Whisper + clipboard   │
└───────────────────┘                       └──────────────────────────────┘
```

Streams are spaced ten apart, so the fixed services fit in the gap after the
first one and adding a monitor renumbers nothing.

Shells are not served here. A terminal in the headset is a different product
with a different wire — see [linux-terminal](https://github.com/vr-meta/linux-terminal).
It answers the same kind of probe on **:9103** and says `"service": "terminal"`,
which is how a client tells the two apart on one machine.

## Installing

```sh
make install
```

That builds the binary into `/usr/local/bin`, writes a **user** systemd unit and
starts it. A user service, not a system one: it asks mutter which monitors exist,
which needs your session bus; it pastes into your clipboard; and the pointer it
creates belongs in your session. Root has none of those.

Two things it cannot give itself:

```sh
sudo usermod -aG input $USER        # /dev/uinput, then log out and back in
```

and a password-less rule for the encoder, because `kmsgrab` requires
`CAP_SYS_ADMIN`:

```sh
echo "$USER ALL=(root) NOPASSWD: /usr/bin/ffmpeg" | sudo tee /etc/sudoers.d/linux-vr
```

Run `linux-vr-server doctor` before blaming anything else. Every check in it is
a failure that has actually happened and cost an evening.

## The settings page

Open **`http://localhost:9099`** on the machine itself. One page, served by the
binary, with no external requests in it — a page that needs a CDN cannot be
opened on the network it exists to configure.

It shows what the headset would see (screens, which of them are being encoded
right now, which services work and why not) and lets you change the things worth
changing without editing JSON: the name, the encoder rate and quality, and where
speech is recognised. There is a **Test it** button next to the dictation
settings that posts a short sound to the endpoint and reports what came back, so
a wrong key is one click to find rather than a puzzle discovered in the headset.

**Settings can only be changed from the machine itself.** From anywhere else the
page is read-only and the endpoints answer 403. The rest of this server is open
on the network by design — that is what a headset needs — but the settings hold
an API key and change what the machine does, and there is no authentication
anywhere in this project. Loopback is the only boundary available without
inventing one. If that is not what you want, set `"allowRemoteConfig": true` and
understand what you are accepting.

## Configuration

`~/.config/linuxvr/config.json`, written on first run, mode **0600** because it
can hold an API key:

```json
{
  "name": "beelink",
  "bind": "0.0.0.0",
  "allowRemoteConfig": false,
  "capture": { "device": "/dev/dri/card1", "fps": 60, "qp": 23, "sudo": true },
  "asr": { "provider": "custom", "url": "", "key": "", "model": "" }
}
```

`name` is what the headset shows in its list, and it is worth setting when two
machines are both called `ubuntu`.

`fps` must match the **display**, not the headset. Asking `kmsgrab` for more than
the display produces breaks timestamp generation outright: a 60 Hz output
captured at 90 gave ~11000 duplicated frames with output time frozen at 0.01 s,
and the client saw one still frame. The mismatch with the headset is harmless —
the composition layer is world-locked and the compositor reprojects it at 90 Hz
whether or not a new frame arrived.

## Dictation, and where speech goes

The **server** posts the audio, not the headset. Two reasons: the key stays on
one machine instead of being shipped to every headset that connects, and the
host is where the text has to end up anyway — it is the side holding the
clipboard and the cursor.

Two providers, both speaking the same wire, which is the OpenAI audio
transcription API:

| `provider` | What it means |
|---|---|
| `openai` | the hosted service. Only a key is needed; the URL is filled in |
| `custom` | anything of yours that speaks the same API |

`custom` is what makes a **local model** a configuration line rather than an
architecture decision. Run `whisper.cpp`'s server, a `faster-whisper` container
or anything similar on the machine, point `url` at `127.0.0.1`, and no audio
leaves the building. There is no path to doing the recognition inside this
binary — nothing of Whisper's quality exists in pure Go, and cgo bindings would
cost exactly the property this server was rewritten for, which is that it is one
file with no dependencies.

The model name defaults per provider (`whisper-1` for OpenAI,
`whisper-large-v3-turbo` otherwise) because getting it wrong produces a 400 that
explains nothing.

Environment variables still win over the file, so an existing shell profile
keeps working:

```sh
LINUXVR_ASR_URL     LINUXVR_ASR_KEY     LINUXVR_ASR_MODEL
```

The settings page says so explicitly when one of them is set — otherwise editing
a field there appears to do nothing, which is a bad half-hour.

## Discovery

A UDP broadcast rather than mDNS. mDNS would mean avahi on the host and
`NsdManager` in the client — two moving parts and two failure modes for a
question with one line in it. This works on a machine with nothing installed,
and a server outside the network is reached by typing its address, which is what
that case needs anyway.

The headset sends `LINUXVR-DISCOVER 1` to `255.255.255.255:9099` and to each
interface's broadcast address; the server replies **unicast**, so no multicast
lock is needed on the client — which matters on Horizon OS, where a system
service cannot be assumed present.

```json
{"proto":"linux-vr","service":"desktop","version":1,"name":"beelink",
 "port":9099,"user":"butschster","os":"Ubuntu 24.04.4 LTS","monitors":2}
```

The address comes from the packet, not from the payload: a server does not
reliably know which of its addresses reached you.

## The control API

| | |
|---|---|
| `GET /` | the settings page |
| `GET /v1/info` | everything a window needs before it opens |
| `GET /v1/config` | the settings, without the key |
| `PUT /v1/config` | change them — loopback only by default |
| `POST /v1/asr/test` | post a short sound and report what came back |

`GET http://host:9099/v1/info` — everything a window needs before it opens.

```json
{
  "name": "beelink",
  "desktop": {"width": 5120, "height": 1440},
  "screens": [
    {"index":0,"connector":"HDMI-1","x":0,"y":0,"width":2560,"height":1440,
     "port":9100,"crtc":364,"streaming":false}
  ],
  "services": {
    "capture": {"available": true},
    "input":   {"port": 9101, "available": true},
    "voice":   {"port": 9102, "available": false,
                "detail": "LINUXVR_ASR_URL is not set"}
  }
}
```

`services` is the part worth having. A host without `ffmpeg`, or without the
sudo rule, or without `/dev/uinput`, looks identical from inside a window — black
picture, dead pointer. Asking first turns that into a sentence on screen.

`curl` it. That is the point of HTTP here rather than a private framing.

## Capture starts when someone watches

The server owns the stream listener and runs one encoder per connection. The
shell script it replaces had `ffmpeg` listen itself, which meant it served
exactly one connection and exited when the client went away, so the host needed
a restart loop around it.

Two consequences, both wanted: reconnecting works by construction, and nothing
is encoded while no window is open — which on a laptop is the difference between
a warm machine and a hot one.

A second client on the same monitor replaces the first rather than joining it.
Two `kmsgrab` captures of one CRTC cost twice the GPU for the same picture, and
the case this actually happens in is a window that was closed without the socket
noticing yet.

## The wire protocols

Unchanged from the Python agents, deliberately: they were proven, and the client
already spoke them.

**Video, `:9100 + 10·index`** — raw H.264, high profile, no container. Connect
and read.

**Pointer, `:9101`** — one command per line, human-readable so it can be driven
from a terminal while debugging:

```
list          reply with the monitor list, then `end`
use <n>       this connection drives monitor n (index or connector name)
m <x> <y>     absolute position within that monitor, floats in 0..1
d <button>    press    (left | right | middle)
u <button>    release
s <dx> <dy>   scroll, integer clicks
```

One connection per window; each carries its own monitor, because they arrive
interleaved.

**Voice and keys, `:9102`** — one connection per utterance or string, ended by
the client half-closing:

```
"pcm <rate> <channels>\n" + s16 samples   transcribe and insert at the cursor
"asr <rate> <channels>\n" + s16 samples   transcribe and return, insert nothing
"text\n" + UTF-8                          insert as is
"key ctrl+c\n"                            press a key or a chord
```

Insertion goes through the clipboard, not through typed scancodes: `uinput`
works in scancodes tied to the layout, so anything non-ASCII becomes garbage,
and `wtype` needs `zwp_virtual_keyboard`, which mutter does not implement. The
previous clipboard contents are saved and put back.

## What this does not do

**There is no authentication.** Anything on the network that can reach `:9101`
can move your pointer and click. That is an honest description of a tool for one
desk, not a recommendation: on a network you do not control, set `bind` to an
interface that is not exposed, or do not run it.

**The layout is read once, at startup.** Plugging in a monitor means restarting
the server, because the ports it listens on are derived from the monitor list.

**One encoder per monitor, one watcher per encoder.** Two headsets on one screen
is not a case this serves.
