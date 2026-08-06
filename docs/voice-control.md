# Voice control and text input

Hold a controller button, speak, release. Then one of two things happens: text
appears wherever the focus is, or an action runs.

The trigger is on the controller rather than the keyboard: the goal is to drive
the desktop by voice, with hands free and not resting on a keyboard.

## Two modes, and why they must be explicit

| Mode | What you say | What happens |
|---|---|---|
| **Dictation** | "hello, how are you" | text is inserted at the focus |
| **Command** | "switch window" | an action runs |

The temptation to have one mode and infer intent from content is a trap. A
misclassification in one direction types the words "switch window" into a
document; in the other it silently swallows a dictated sentence. Both are
infuriating in the same way: unpredictability.

**Solution: separate buttons.** Trigger for dictation, A for commands (or the
reverse). Deterministic, explainable, and it needs neither a classifier model
nor trust in one.

## Design

```
Quest (our client)                       Ubuntu (host agent)
  button held
     │
  AAudio, 16 kHz mono PCM
     │
     └──── PCM + mode tag over TCP ─────►  buffer
  button released                             │
     └──── end marker ───────────────►        ▼
                                        Whisper (gateway or local)
                                              │ text
                              ┌───────────────┴───────────────┐
                              ▼                               ▼
                        DICTATION mode                  COMMAND mode
                              │                               │
                     save clipboard                    phrase lookup
                     wl-copy "text"                          │
                     uinput: Ctrl+V                    key sequence
                     restore clipboard                       │
                              │                              ▼
                              ▼                        action performed
                     text at the focus
```

The trigger lives entirely on the client: OpenXR actions, no global hotkey
capture on the host and no reverse "start recording" channel.

## Trap: GNOME Wayland does not expose window control

This is the main constraint on command mode.

Under X11 an external program could enumerate windows and focus one (`wmctrl`,
`xdotool`). **Under Wayland with mutter there is no such API.** An application
can neither list windows nor focus someone else's. That is the Wayland security
model, not an oversight. The workarounds — a GNOME Shell extension with its own
D-Bus interface — mean a separate component that breaks on every GNOME update.

**So commands are expressed as key combinations, not API calls.** `uinput`
sends those without any permissions and they work exactly as if a human had
pressed them:

| Phrase | What we send |
|---|---|
| "switch window" | `Alt+Tab` |
| "close window" | `Alt+F4` |
| "second desktop" | `Super+2` |
| "open terminal" | `Ctrl+Alt+T` |
| "search" | `Super`, then text |
| "copy" / "paste" | `Ctrl+C` / `Ctrl+V` |

This is not a compromise: key combinations are the only interface available from
outside on GNOME Wayland that does not depend on the shell version.

## Trap: text cannot be typed character by character

For dictation mode.

`uinput` works in **scancodes tied to the keyboard layout**. Cyrillic,
punctuation, anything non-ASCII turns into garbage or depends on which layout
happens to be active at insertion time. The user switches language and the
output breaks.

`wtype` does not help: it uses the `zwp_virtual_keyboard` protocol, which
**mutter does not implement**. On GNOME Wayland that is a dead end.

**The clipboard depends on neither the layout nor the input protocol.**
`wl-copy` puts down finished UTF-8 and `Ctrl+V` is two scancodes that are the
same in any layout.

A hard requirement: **save and restore the previous clipboard contents.**
Otherwise dictation silently destroys whatever the user copied a minute ago —
behaviour that is maddening and not obvious to diagnose.

## Command parsing

Start with simple phrase lookup against a dictionary. Whisper produces text
stable enough for that to work, and the behaviour stays predictable: no match
means nothing happens, which is better than doing the wrong thing.

Bring in a language model for parsing only if the dictionary proves
insufficient. It carries its own cost: latency, money, and above all
non-determinism exactly where the opposite is wanted.

Separately: **commands must be confirmed.** Voice misfires more often than a
keyboard, and a misheard "close window" is lost work. At minimum, show the
recognised phrase in the corner of the layer before acting; destructive actions
get an explicit confirmation.

## Where Whisper runs

No external OpenAI API needed: **use a self-hosted Whisper on a GPU.**

`whisper-large-v3-turbo` (faster-whisper CT2) behind an OpenAI-compatible
gateway. Standard endpoint: `POST /v1/audio/transcriptions`; the model occupies
about 3 GB of VRAM.

Gateway address and key come from the environment:

```sh
LINUXVR_ASR_URL=http://<gateway>:4000/v1/audio/transcriptions
LINUXVR_ASR_KEY=<key>
LINUXVR_ASR_MODEL=whisper-large-v3-turbo
```

### A limitation accepted on purpose for now

The gateway is reachable **only over VPN**. From the VR desktop host the route
goes through a tunnel with a measured **RTT of 74 ms**, plus inference and
transfer.

**For the concept this does not matter** — around a second is acceptable, and
one path instead of two means half the code and no fork during debugging.

What is being deferred knowingly, so it is not a surprise later:

- **Command latency.** A two-word command that fires after a second feels like
  it did not fire, and the user presses again. Measure and decide once the
  concept works.
- **Tunnel dependency.** VPN down means voice control down. For a daily-use
  tool that is a poor dependency.

Both are cured the same way: a local `whisper.cpp` as a second implementation
behind the same interface. On a Ryzen 7 6800U with 16 threads the `small` model
transcribes a short phrase faster than real time. **So the recognition interface
is made swappable from the start** — that is the one thing worth building in
now, because retrofitting it costs more than anticipating it.

### Audio pre-processing is not needed

Off-the-shelf sidecars for audio preparation (normalising to 16 kHz mono Opus,
splitting long recordings on silence) solve the problem of hour-long recordings
that do not fit one request.

We have a few seconds of push-to-talk, and the client already records 16 kHz
mono — exactly what Whisper wants. Neither transcoding nor splitting applies.

The gateway key comes from the environment only. It never enters a config file
or the repository.

## Client requirements

- OpenXR actions on controller buttons — one per mode.
- `android.permission.RECORD_AUDIO`. For a `NativeActivity` the runtime
  permission request goes through JNI — a small amount of extra work.
- Capture via **AAudio**, 16 kHz mono PCM — exactly what Whisper consumes, with
  no resampling.
- On-layer feedback: recording in progress, and what was recognised. Without
  feedback a voice interface feels broken even when it works.

## Host requirements

- A daemon that accepts audio, transcribes, and executes.
- `wl-clipboard` for the clipboard, `uinput` for scancodes — both available;
  the user is already in the `input` group.

## Implementation order

To be done after milestone C, once a working stream and control channel exist.

1. Host agent: transcribe a stub file → insert via the clipboard. Testable
   without the headset.
2. Command dictionary → scancodes. Also without the headset.
3. Client: permission, AAudio, buttons, PCM upload.
4. Connect the two and measure the delay from button release to result.
5. Local `whisper.cpp`, compared against the gateway.

Target latency: **under one second** from releasing the button. Stricter for
commands, where delay reads as "it did not work" and the user presses again.
