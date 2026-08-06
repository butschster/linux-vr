# Third-party code in this module

`src/main/java/com/termux/terminal/` and `src/main/java/com/termux/view/TerminalRenderer.java`
come from [termux/termux-app](https://github.com/termux/termux-app), whose
`terminal-emulator` and `terminal-view` libraries are released under the
Apache 2.0 licence (see the exceptions in that repository's `LICENSE.md`; they
derive from Android Terminal Emulator by Jack Palevich).

Writing an xterm emulator from scratch is a trap — the escape sequences a shell,
`vim` and Claude Code actually emit are far beyond what a specification summary
suggests, and this one is tested against exactly that traffic.

## What was changed

- `JNI.java` and `TerminalSession.java` are **not** included. Both exist to fork
  a local process through a native pty; here the pty is on the Linux host at the
  other end of a socket, and `dev.butschster.linuxvr.term.HostSession` takes their
  place.
- `TerminalSessionClient.java` is reduced to the methods `TerminalEmulator` and
  `Logger` actually call. Upstream it is typed in terms of `TerminalSession`,
  which is one of the files that is gone.
- `ByteQueue.java` is dropped with `TerminalSession`, its only user.
- Nothing else is edited. Upstream fixes can be copied over the files as they are.

`TerminalView.java` (1500 lines of gestures, text selection and mouse reporting)
is deliberately not vendored — `TermView` is written against the emulator
directly, because a controller ray needs almost none of it.
