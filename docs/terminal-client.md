# A native terminal, instead of streaming one

An idea worth taking seriously, written down before it is implemented.

## The observation

Most of the time this project is used, what is on screen is a terminal — a shell
and Claude Code. And a terminal is text.

What the current design does with that text: rasterises it on the host,
compresses it with H.264, sends it over Wi-Fi as megabits, decodes it, and scales
it into a panel. Every one of those steps can only lose sharpness, and none of
them can add any back. The readability work in [`readability.md`](readability.md)
is entirely about limiting that loss.

A native terminal skips all of it. Glyphs are drawn at the panel's own
resolution, from the font, with no compression and no scaling.

## What it buys

| | Streamed desktop | Native terminal |
|---|---|---|
| Text sharpness | limited by encode, scale and optics | limited by optics alone |
| Bandwidth | ~2 Mbit/s idle, tens under motion | bytes per keystroke |
| Latency | capture + encode + network + decode | network only |
| Selection, scrollback, copy | pixels — none of it | real text |
| Pointer needed | yes | no |
| Works over a slow link | no | yes |

The bandwidth line is not a micro-optimisation. It changes what the tool is:
a terminal client works over a phone tether or from another city, where a
1440p video stream does not.

## What it costs

Everything that is not a terminal. No browser, no GUI, no watching a video
while you work. So this is a **second client, not a replacement** — the streamed
desktop keeps the general case, the terminal takes the case that is used most.

## Shape of it

Three pieces, none exotic:

1. **A PTY on the host.** Either SSH, which brings authentication and encryption
   already solved, or a small agent next to the existing ones that opens a shell
   and pipes it over a socket. SSH is the better default: reusing an audited
   protocol beats inventing a worse one, and it works from outside the LAN.

2. **A terminal emulator on the headset.** This is the real work — parsing the
   escape sequences that a shell, `vim` and Claude Code actually emit. Writing
   one from scratch is a trap; the sensible move is to reuse a library that
   already does it. Termux's `terminal-emulator` module is Apache-2.0, separable
   from the app, and battle-tested against exactly this traffic.

3. **A renderer.** A monospace font at the panel's native resolution. The
   measurement that already exists applies directly: comfort begins around
   0.39° per glyph, so the font size follows from the panel's angular size
   rather than from taste.

## Why it fits what already exists

The host side barely changes. The input agent, the voice agent and its Whisper
path all deal in text and keys, and a terminal is the one place where that text
was always headed. Dictation into a native terminal is strictly better than
dictation into a video of one: no clipboard round trip, no `Ctrl+V`, no
uinput — the client already has the characters and can simply write them to the
PTY.

That also removes the whole class of problems documented in
[`gotchas.md`](gotchas.md) about layouts, scancodes and `zwp_virtual_keyboard`:
those exist only because text had to be pushed into somebody else's window.

## Reading the project from it

The natural next step once a terminal exists: the client knows which project the
session is in, so it can read that project's own files — skills, `CLAUDE.md`,
docs — and offer them without a round trip through the shell. Worth designing
only after the terminal itself works.

## Order

1. SSH or an agent PTY, driven from a throwaway client, to prove the loop.
2. Terminal emulation with a reused library.
3. Rendering, sized from the readability measurement.
4. Voice straight into the PTY, which deletes the clipboard path.
5. Project awareness.

Steps 1 and 2 decide whether this is worth it. If escape-sequence handling turns
out to be a slog even with a library, the streamed desktop is already good
enough and this stays an idea.
