# The terminal client: requirements and design

Why a native terminal at all is argued in [`terminal-client.md`](terminal-client.md).
This document is the thing itself: what it has to do, how it is built, and what
the wire between the two halves looks like.

Code: [`host/pty-agent.py`](../host/pty-agent.py) and `client/term/`.

## Scope

A window in Horizon OS holding one shell on the Ubuntu host, with a bar under it
whose buttons change with what is running in that shell.

It is not a replacement for the streamed desktop. It covers the case that is used
most — a terminal and an AI CLI in it — and it covers that case better than a
video of a terminal can. Everything else (a browser, a GUI, watching something
while you work) stays with `client/panel`.

## Functional requirements

**Terminal**

- **FR-1** A window shows one shell running on the host, in a directory chosen at
  runtime. Text is drawn from a font at the panel's own resolution; nothing is
  encoded, scaled or resampled on the way.
- **FR-2** The emulation is good enough for what is actually run: a shell, Claude
  Code, `vim`, a pager, `git`. That means real xterm handling — alternate screen,
  scroll regions, 256 colours, wide characters — not a line printer.
- **FR-3** The grid follows the window. Resizing the window resizes the pty, so
  programs reflow instead of wrapping wrongly.
- **FR-4** Scrollback is reachable without a keyboard: dragging the text scrolls it,
  and the bar has page keys.
- **FR-5** Text size is adjustable from the bar, and every change re-derives the
  column count and tells the host.
- **FR-6** Several windows can be open at once, each with its own shell — one per
  project, the way tabs are used at the desk.
- **FR-7** If the host agent is not running yet, or goes away, the client keeps
  retrying and says so, rather than needing to be relaunched.

**The bar**

- **FR-8** The bar always shows where the shell is (path, git branch, dirty count)
  and what is running in front of it.
- **FR-9** At a shell prompt the bar offers navigation: the directory above, the
  directories inside the current one, and the projects opened most often at the
  desk — the same frecency list the desktop terminal uses.
- **FR-10** When a program is in the foreground, the bar offers that program's keys
  instead: `Esc` and mode switching for Claude Code, `:w`/`:q` for `vim`, `q` and
  paging for a pager.
- **FR-11** Which program is in front is **read, not guessed** (see below).
- **FR-12** A button either types its text or types it and runs it, and which one is
  a property of the button. Typing without running is the default for anything you
  would add words to.
- **FR-13** The project's Claude Code skills appear as buttons when Claude Code is
  in front — one press instead of typing a slash command.
- **FR-14** Adding a tool to the bar does not require rebuilding the app.

**Input**

- **FR-15** The system keyboard can be summoned from the bar and types into the pty.
- **FR-16** Dictation types the transcript into the pty at the cursor, without
  submitting it, so a misheard word can be fixed on the line it landed on.
- **FR-17** The keys a terminal cannot live without and no soft keyboard offers
  reliably — `Esc`, `Tab`, `^C`, `^D`, `^L`, `^R`, arrows — are always one press away.

## Non-goals

Text selection and copy-paste inside the terminal; mouse reporting; tabs and splits
inside the window (the shell already stacks windows, and `tmux` runs inside this
perfectly well); detached sessions that survive closing the window; SSH from the
headset to a third machine.

## Shape

```
Ubuntu host                                  Quest 3
┌───────────────────────────┐                ┌──────────────────────────┐
│ pty-agent.py :9103        │                │ TermActivity             │
│   ├─ pty ── bash ── claude│◄──── TCP ─────►│   ├─ HostSession (socket)│
│   ├─ tcgetpgrp → /proc    │   framed       │   ├─ TermView  (draws)   │
│   └─ builds the buttons   │   messages     │   └─ ContextBar (buttons)│
├───────────────────────────┤                └──────────────────────────┘
│ voice-agent.py :9102      │◄──── PCM ──────── Dictation
│   (asr: transcribe only)  │ ───── text ─────►
└───────────────────────────┘
```

### Why the buttons are computed on the host

The host is the side that knows things. It owns the pty, so it can read the
foreground process group; it has the filesystem, so it can list directories and
skills; it has the frecency file the desktop terminal writes. The client would
have to ask for all of it anyway.

The practical consequence is FR-14: the per-tool key sets live in one table in
`pty-agent.py`, and teaching the bar about a new tool is an edit to that table.

The client stays deliberately dumb. It draws `label`, colours it by `style`, and on
a press sends `send` — plus a carriage return when `enter` is set. It knows nothing
about what any particular tool means.

### Detection is not a guess

A pty has a foreground process group. `os.tcgetpgrp(master_fd)` returns it,
`/proc/<pgid>/cmdline` says what it is, and `/proc/<pgid>/cwd` says where. No
window titles, no watching for prompt patterns, no heuristics.

This matters because a bar that guesses wrong is worse than a fixed one: keys that
move on their own, at the moment you reach for them, are a hazard rather than a
convenience.

The classifier looks at every argument, not only `argv[0]`: tools are routinely
launched through an interpreter, and the name that matters is further along the
line. `node` is only reported when nothing more specific was found.

Polling is 4 Hz and costs two `/proc` reads. The listings behind it — directories,
git status, skills — are recomputed only when the answer changes, and a context
message is sent only when it differs from the last one.

## Protocol

One TCP connection is one terminal session. Closing the window closes the shell.
Every frame is

```
type (1 byte) │ length (4 bytes, big endian) │ payload
```

| Direction | Type | Payload |
|---|---|---|
| client → host | `0x01` | raw bytes for the pty |
| client → host | `0x02` | `{"cols":N,"rows":N}` |
| client → host | `0x03` | `{"op":"refresh"}` or `{"op":"signal","name":"int"}` |
| host → client | `0x81` | raw bytes from the pty |
| host → client | `0x82` | context JSON |
| host → client | `0x83` | `{"code":N}` — the shell exited |

Context JSON, trimmed to what the client reads:

```json
{
  "cwd_label": "~/repos/home/linux-vr",
  "tool": "claude", "tool_name": "claude",
  "git": {"branch": "main", "dirty": 3},
  "skills": [{"name": "vr-diagnostics", "desc": "…"}],
  "groups": [
    {"name": "tool", "actions": [
      {"label": "Esc", "send": "\u001b", "style": "warn", "enter": false, "hint": "interrupt"}
    ]},
    {"name": "favorites", "actions": []},
    {"name": "common",    "actions": []}
  ]
}
```

The first group is what the moment is about — the program's keys, or where you can
go. The rest share the row below it.

`style` is a colour, not a meaning: `up`, `dir`, `git`, `fav`, `cmd`, `skill`,
`warn`, `key`.

## What is reused and what is written

Writing an xterm emulator from scratch is a trap. The escape sequences a shell,
`vim` and Claude Code actually emit are far past what a specification summary
suggests, and Termux's `terminal-emulator` has been tested against exactly that
traffic for a decade. It is vendored (Apache 2.0) — see
[`client/term/NOTICE.md`](../client/term/NOTICE.md) for what was changed.

What is **not** reused is Termux's `TerminalView`: 1500 lines of gestures, text
selection and mouse reporting, almost none of which a controller ray wants.
`TermView` is written against the emulator and the renderer directly, and is about
a tenth of that.

The transport is the other rewrite. Upstream `TerminalSession` forks a local
process through JNI; here the pty is on the host, so `HostSession` replaces it and
the emulator above it is untouched.

## Text size

From [`readability.md`](readability.md): comfort begins around **0.39° per glyph**,
and the size that lands there follows from the panel's angular size rather than
from taste. The default is a 20 px cell, adjustable from the bar in 2 px steps.

The streamed desktop had to fight for that number — the panel window is sized at
2048dp specifically so the 2560-wide stream arrives one pixel per pixel, because a
0.625 downscale turned a 15 px font into 9 px. None of that applies here: the
glyphs are drawn at the panel's resolution by definition, so the only thing between
the font and the eye is the compositor.

## Dictation

The existing voice agent is reused unchanged apart from one new mode: `asr`
transcribes and returns the text **without inserting it anywhere**.

That mode is the whole point. Over the streamed desktop the transcript had to be
pushed into somebody else's window, and on GNOME Wayland that means the clipboard
plus `Ctrl+V` — because `uinput` speaks scancodes tied to the keyboard layout and
mutter does not implement `zwp_virtual_keyboard`. Here the client owns the pty and
simply writes the characters to it. The whole class of layout problems does not get
solved; it stops existing.

## Status

Working and verified on the host:

- [x] pty agent: shell, resize, framing, 1 MB of output through the pipe
- [x] foreground detection — verified switching between a shell, `less` and `claude`
- [x] context switching: directory buttons at a prompt, tool keys under a tool
- [x] project skills as buttons (10 found in this repository)
- [x] `asr` mode in the voice agent
- [x] client builds — 320 KB APK

Not yet verified, because it needs the headset:

- [ ] anything on the device at all: rendering, sizing, input, the bar
- [ ] whether 20 px is the right default in a window of this size
- [ ] the system keyboard against `TYPE_NULL` on Horizon OS
- [ ] dictation end to end into the pty

## Running

```sh
./host/pty-agent.py --cwd ~/repos          # :9103
./host/voice-agent.py                      # :9102, only needed for dictation
```

```sh
cd client && ./gradlew :term:assembleDebug
adb install -r term/build/outputs/apk/debug/term-debug.apk
echo 192.168.1.10 > host.txt
adb push host.txt /sdcard/Android/data/dev.butschster.linuxvr.term/files/
adb shell am start -n dev.butschster.linuxvr.term/.TermActivity
adb logcat -s linux-vr
```

Push `host.txt` with `adb push`, not `adb shell echo >`: the latter creates it mode
660 owned by `shell`, and the app cannot read it.

To see what the bar will offer without a headset in reach:

```sh
./host/pty-agent.py --dump-context ~/repos/home/linux-vr
```

## Next

1. Run it in the headset and fix what the first ten minutes show.
2. Font: `Typeface.MONOSPACE` is Droid Sans Mono. A shipped programming font would
   be measurably better at this angular size, and it is a one-line change.
3. Report the shell's own scrollback position in the bar; right now scrolling back
   is invisible until you look at the text.
4. Buttons for `git` when a repository is dirty — commit, diff, push — which is the
   most common reason to leave the AI CLI at all.
5. `tmux` as an option for detached sessions, rather than building session
   persistence here.
