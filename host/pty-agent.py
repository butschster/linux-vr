#!/usr/bin/env python3
"""A shell on the host, served to the headset as text instead of as video.

The streamed desktop rasterises a terminal, compresses it with H.264 and scales
it into a panel; every stage of that can only lose sharpness. Here the bytes the
shell produces are sent as bytes, and the headset draws the glyphs itself at the
panel's own resolution.

What this agent adds beyond a plain socket-to-pty pipe is **context**: it knows
which directory the shell is in and which program is in the foreground, and it
turns that into a list of buttons for the client to draw. That is not a guess —
a pty has a foreground process group, so the answer is read from /proc.

Wire protocol, one connection per terminal session. Every frame is

    type (1 byte) | length (4 bytes, big endian) | payload

    client -> host                      host -> client
    0x01 raw bytes for the pty          0x81 raw bytes from the pty
    0x02 {"cols":N,"rows":N}            0x82 context JSON (see context())
    0x03 {"op":...} request             0x83 {"code":N} the shell exited

Requests (0x03):
    {"op":"refresh"}                    push the context again
    {"op":"signal","name":"int"}        SIGINT to the foreground group

Buttons are computed here rather than in the client on purpose: the host is the
side that knows what is running and what is installed, so adding a tool is an
edit to ACTIONS below and not a new APK.

    ./pty-agent.py                      serve on :9103, shell starts in $HOME
    ./pty-agent.py --cwd ~/repos        serve, shell starts elsewhere
    ./pty-agent.py --dump-context .     print what the client would receive
"""

import argparse
import fcntl
import json
import os
import pty
import select
import shlex
import signal
import socket
import struct
import subprocess
import sys
import termios
import threading
import time

DEFAULT_PORT = 9103

# client -> host
MSG_DATA = 0x01
MSG_RESIZE = 0x02
MSG_REQUEST = 0x03
# host -> client
MSG_OUT = 0x81
MSG_CONTEXT = 0x82
MSG_EXIT = 0x83

# The foreground process is re-read this often. Cheap — two /proc reads — and
# the listings behind it are only recomputed when the answer actually changes.
POLL_SECONDS = 0.25

HOME = os.path.expanduser("~")

# Frecency data belongs to the terminal fork in ~/repos/home/terminal, which
# writes it on every project switch. Reusing the file rather than starting a
# second history means the headset opens the directories the desk already uses.
USAGE_FILE = os.path.join(HOME, ".config/wezterm/project_usage.json")

ESC = "\x1b"


# ---------------------------------------------------------------- classifying

# A tool is recognised by the basename of any argument in its command line, not
# only argv[0]: Claude Code runs as `node .../cli.js`, so argv[0] is "node" and
# the name that matters is further along.
TOOL_NAMES = {
    "claude": "claude",
    "codex": "codex",
    "aider": "aider",
    "vim": "vim",
    "nvim": "vim",
    "vi": "vim",
    "nano": "nano",
    "less": "pager",
    "more": "pager",
    "man": "pager",
    "git": "git",
    "lazygit": "tui",
    "htop": "tui",
    "top": "tui",
    "btop": "tui",
    "cch": "tui",
    "cgit": "tui",
    "ssh": "ssh",
    "psql": "psql",
    "python": "python",
    "python3": "python",
    "node": "node",
    "npm": "node",
    "bash": "shell",
    "zsh": "shell",
    "sh": "shell",
    "fish": "shell",
}


def classify(argv):
    """Which known tool this command line is, or None."""
    for arg in argv:
        if arg.startswith("-"):
            continue
        name = os.path.basename(arg)
        if name in TOOL_NAMES:
            kind = TOOL_NAMES[name]
            # "node" is a host for other tools far more often than it is the
            # tool; only report it when nothing more specific was found.
            if kind == "node":
                continue
            return kind, name
    for arg in argv:
        if os.path.basename(arg) == "node":
            return "node", "node"
    return None, os.path.basename(argv[0]) if argv else "?"


def proc_argv(pid):
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as f:
            raw = f.read()
    except OSError:
        return []
    return [p.decode("utf-8", "replace") for p in raw.split(b"\0") if p]


def proc_cwd(pid):
    try:
        return os.readlink(f"/proc/{pid}/cwd")
    except OSError:
        return None


# ------------------------------------------------------------------- listings


def short(path):
    """A path as it should read on a button: ~ for home, project-relative."""
    if path == HOME:
        return "~"
    if path.startswith(HOME + "/"):
        return "~/" + path[len(HOME) + 1:]
    return path


def subdirs(cwd, limit=40):
    """Directories inside cwd, the ones you would cd into next.

    Dot-directories are skipped except .claude: it is the one hidden directory
    this project is regularly inside.
    """
    try:
        entries = sorted(os.scandir(cwd), key=lambda e: e.name.lower())
    except OSError:
        return []
    out = []
    for e in entries:
        if len(out) >= limit:
            break
        try:
            if not e.is_dir():
                continue
        except OSError:
            continue
        if e.name.startswith(".") and e.name != ".claude":
            continue
        out.append({"name": e.name, "git": os.path.isdir(os.path.join(e.path, ".git"))})
    return out


def favorites(limit=14):
    """Frequently used projects, ordered the way the desktop terminal orders them."""
    try:
        with open(USAGE_FILE, "r") as f:
            usage = json.load(f)
    except (OSError, ValueError):
        return []
    rows = []
    for path, entry in usage.items():
        if not os.path.isdir(path):
            continue
        rows.append({
            "path": path.rstrip("/"),
            "label": os.path.basename(path.rstrip("/")) or path,
            "count": int(entry.get("count", 0)),
            "last": int(entry.get("last", 0)),
        })
    rows.sort(key=lambda r: (-r["count"], -r["last"]))
    rows = rows[:limit]

    # Two projects can share a basename — this machine has `home/terminal` and
    # `cve-tools/terminal` — and two identical buttons are worse than a long one.
    seen = {}
    for row in rows:
        seen.setdefault(row["label"], []).append(row)
    for label, group in seen.items():
        if len(group) > 1:
            for row in group:
                parent = os.path.basename(os.path.dirname(row["path"]))
                row["label"] = f"{parent}/{label}" if parent else label
    return rows


def project_root(cwd):
    """Nearest ancestor that looks like a project: .claude first, then .git."""
    path = cwd
    while True:
        if os.path.isdir(os.path.join(path, ".claude")) or os.path.isdir(os.path.join(path, ".git")):
            return path
        parent = os.path.dirname(path)
        if parent == path:
            return None
        path = parent


def frontmatter(manifest):
    """name/description out of a SKILL.md header, without a YAML dependency."""
    fields = {}
    try:
        with open(manifest, "r", errors="replace") as f:
            if f.readline().strip() != "---":
                return fields
            for line in f:
                line = line.rstrip("\n")
                if line.strip() == "---":
                    break
                if ":" in line and not line.startswith((" ", "\t", "-")):
                    key, value = line.split(":", 1)
                    fields[key.strip()] = value.strip().strip("'\"")
    except OSError:
        pass
    return fields


def skills_in(directory, origin):
    out = []
    try:
        entries = sorted(os.scandir(directory), key=lambda e: e.name)
    except OSError:
        return out
    for e in entries:
        manifest = os.path.join(e.path, "SKILL.md")
        if not os.path.isfile(manifest):
            continue
        fields = frontmatter(manifest)
        out.append({
            "name": fields.get("name", e.name),
            "desc": fields.get("description", ""),
            "origin": origin,
        })
    return out


def skills(root):
    """Project skills first — those are the ones worth a button."""
    found = []
    if root:
        found += skills_in(os.path.join(root, ".claude/skills"), "project")
    found += skills_in(os.path.join(HOME, ".claude/skills"), "user")
    return found


def git_head(cwd):
    """Branch and dirty-file count, or None outside a repository."""
    try:
        branch = subprocess.run(
            ["git", "-C", cwd, "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=2,
        )
        if branch.returncode != 0:
            return None
        status = subprocess.run(
            ["git", "-C", cwd, "status", "--porcelain"],
            capture_output=True, text=True, timeout=3,
        )
        dirty = len([l for l in status.stdout.splitlines() if l.strip()])
        return {"branch": branch.stdout.strip(), "dirty": dirty}
    except (OSError, subprocess.SubprocessError):
        return None


# -------------------------------------------------------------------- buttons

def key(label, send, style="key", enter=False, hint=None):
    return {"label": label, "send": send, "style": style, "enter": enter, "hint": hint}


# Per-tool key sets. This table is the whole point of the agent: what the bar
# offers follows what is running, so the keys that matter are one press away and
# the ones that do not are not on screen at all.
#
# `enter` decides whether pressing the button runs the line or only types it.
# Typing without running is deliberate for anything you would want to add words
# to — a skill invocation, a git command with a path.
# The first slot of every set is "get out of this program". That is the button
# reached for under mild panic, and panic is when position is all you have.
#
# Keys that every program needs — Esc, Enter, Tab, ^C, ^D, backspace, arrows —
# are NOT here. The client owns those and keeps them on a fixed side that context
# never rebuilds. Duplicating them here would put the same key in two places, and
# then neither is the one the hand learns.
ACTIONS = {
    "claude": lambda ctx: [
        key("⇧Tab", ESC + "[Z", "key", hint="cycle mode"),
        key("1", "1", "key", hint="permission prompt: yes"),
        key("2", "2", "key", hint="permission prompt: yes, don't ask again"),
        key("3", "3", "key", hint="permission prompt: no"),
        key("/", "/", "key", hint="commands"),
        key("!", "!", "key", hint="bash"),
        key("#", "#", "key", hint="memory"),
        key("/clear", "/clear", "cmd", enter=True),
        key("/compact", "/compact", "cmd", enter=True),
        key("/resume", "/resume", "cmd", enter=True),
    ],
    "codex": lambda ctx: [
        key("/", "/", "key"),
        key("1", "1", "key"),
        key("2", "2", "key"),
        key("3", "3", "key"),
    ],
    "vim": lambda ctx: [
        key(":q!", ":q!", "warn", enter=True, hint="quit, discarding"),
        key(":w", ":w", "cmd", enter=True),
        key(":wq", ":wq", "cmd", enter=True),
        key("/", "/", "key", hint="search"),
        key("u", "u", "key", hint="undo"),
        key("dd", "dd", "key"),
        key("gg", "gg", "key"),
        key("G", "G", "key"),
    ],
    "pager": lambda ctx: [
        key("q", "q", "warn", hint="quit"),
        key("Space", " ", "key", hint="page down"),
        key("b", "b", "key", hint="page up"),
        key("g", "g", "key", hint="top"),
        key("G", "G", "key", hint="bottom"),
        key("/", "/", "key", hint="search"),
    ],
    "tui": lambda ctx: [
        key("q", "q", "warn", hint="quit"),
        key("/", "/", "key", hint="filter"),
        key("r", "r", "key", hint="reload"),
    ],
    "python": lambda ctx: [
        key("exit()", "exit()", "cmd", enter=True, hint="quit"),
    ],
    "nano": lambda ctx: [
        key("^X", "\x18", "warn", hint="exit"),
        key("^O", "\x0f", "cmd", hint="write"),
        key("^W", "\x17", "key", hint="search"),
    ],
}

# git pipes almost everything through a pager, so `git log` with an empty bar is
# a screen you cannot leave. The same is true of anything unrecognised: a program
# that took over the terminal is far more likely to be pager-like than not.
FALLBACK_TOOL = "pager"


def shell_actions(ctx):
    """What a shell prompt is for: going somewhere, then starting something."""
    rows = []
    if ctx["up"]:
        rows.append(key("↑ ..", "cd " + shlex.quote(ctx["up"]), "up", enter=True,
                        hint=short(ctx["up"])))
    for d in ctx["dirs"]:
        rows.append(key(d["name"], "cd " + shlex.quote(d["name"]), "git" if d["git"] else "dir",
                        enter=True))
    return rows


def favorite_actions(ctx):
    return [
        key(f["label"], "cd " + shlex.quote(f["path"]), "fav", enter=True,
            hint=f"{short(f['path'])}  ·  {f['count']}×")
        for f in ctx["favorites"]
    ]


# Commands, not keys: these are things to run, and they belong with the rest of
# the content on the dynamic side. The keys they used to sit beside are the
# client's now.
def command_actions(ctx):
    if ctx["tool"] is not None:
        return []
    return [
        key("claude", "claude", "cmd", enter=True),
        key("git status", "git status", "cmd", enter=True),
        key("ls -la", "ls -la", "cmd", enter=True),
        key("^R", "\x12", "key", hint="history search"),
        key("cch", "cch", "cmd", enter=True, hint="Claude Code panel"),
        key("cgit", "cgit", "cmd", enter=True, hint="git panel"),
    ]


def skill_actions(ctx):
    """Project skills first and marked as such; the global ones are not this project."""
    project = [s for s in ctx["skills"] if s["origin"] == "project"]
    user = [s for s in ctx["skills"] if s["origin"] != "project"]
    return [
        key("/" + s["name"], "/" + s["name"] + " ", "skill", hint=s["desc"][:100])
        for s in project
    ] + [
        key("/" + s["name"], "/" + s["name"] + " ", "key", hint="global · " + s["desc"][:90])
        for s in user
    ]


# ------------------------------------------------------------------- sessions


class Session:
    """One pty, one client, and the context that follows them."""

    def __init__(self, conn, cwd, shell):
        self.conn = conn
        self.initial_cwd = cwd
        self.shell = shell
        self.pid = 0
        self.fd = -1
        self.lock = threading.Lock()
        # Everything below is cached so the 4 Hz poll costs two /proc reads.
        self.last_cwd = None
        self.last_tool = None
        self.last_root = None
        self.cache = {}

    # -- framing

    def send(self, kind, payload):
        if isinstance(payload, (dict, list)):
            payload = json.dumps(payload).encode("utf-8")
        header = struct.pack(">BI", kind, len(payload))
        with self.lock:
            try:
                self.conn.sendall(header + payload)
            except OSError:
                pass

    # -- the shell

    def start(self, cols, rows):
        pid, fd = pty.fork()
        if pid == 0:
            os.chdir(self.initial_cwd if os.path.isdir(self.initial_cwd) else HOME)
            env = dict(os.environ)
            # The client renders 256 colours and understands nothing about
            # terminfo it was not told; xterm-256color is the safe common
            # ground the emulator was written against.
            env["TERM"] = "xterm-256color"
            env["COLORTERM"] = "truecolor"
            env["LINUXVR_TERM"] = "1"
            env.pop("LINES", None)
            env.pop("COLUMNS", None)
            # This agent is often started from inside a Claude Code session — which
            # exports markers that make the next `claude` behave as a child of it:
            # transcript saving off, and a warning printed at startup. The shell in
            # the headset is not a child of anything, so those go.
            for name in [k for k in env if k.startswith("CLAUDE_CODE_") or k == "CLAUDECODE"]:
                env.pop(name, None)
            # SIGCHLD set to SIG_IGN survives exec — unlike a handler, which does not.
            # Inheriting it means every descendant that reaps its own children gets
            # ECHILD from waitpid instead of an exit status, because the kernel has
            # already reaped them. Claude Code's hooks fail exactly this way.
            signal.signal(signal.SIGCHLD, signal.SIG_DFL)
            try:
                # Not a login shell: on a pty bash is interactive already and reads
                # .bashrc, which is where the prompt and the aliases live. Asking for
                # -l instead reads the profile, and on a machine whose profile does
                # not source .bashrc that silently costs both.
                os.execvpe(self.shell, [self.shell], env)
            except OSError:
                os._exit(127)
        self.pid = pid
        self.fd = fd
        self.resize(cols, rows)

    def resize(self, cols, rows):
        if self.fd < 0:
            return
        cols = max(20, min(500, int(cols)))
        rows = max(4, min(200, int(rows)))
        try:
            fcntl.ioctl(self.fd, termios.TIOCSWINSZ,
                        struct.pack("HHHH", rows, cols, 0, 0))
        except OSError:
            pass

    # -- context

    def foreground(self):
        """The process group in front of the pty, read rather than guessed."""
        try:
            pgid = os.tcgetpgrp(self.fd)
        except OSError:
            return None, []
        if pgid <= 0:
            return None, []
        return pgid, proc_argv(pgid)

    def context(self, force=False):
        pgid, argv = self.foreground()
        is_shell = pgid == self.pid or not argv
        tool, name = (None, "shell") if is_shell else classify(argv)
        if tool == "shell":
            tool, is_shell = None, True

        cwd = proc_cwd(pgid if pgid and pgid > 0 else self.pid) or proc_cwd(self.pid) or HOME

        if not force and cwd == self.last_cwd and tool == self.last_tool:
            return None

        changed_dir = cwd != self.last_cwd
        self.last_cwd, self.last_tool = cwd, tool

        if changed_dir or force or not self.cache:
            root = project_root(cwd)
            self.cache = {
                "dirs": subdirs(cwd),
                "favorites": favorites(),
                "git": git_head(cwd),
                "root": root,
                "skills": skills(root),
            }

        ctx = {
            "cwd": cwd,
            "cwd_label": short(cwd),
            "home": HOME,
            "up": os.path.dirname(cwd) if cwd != "/" else None,
            "tool": tool,
            "tool_name": name,
            "pid": pgid,
            "root": short(self.cache["root"]) if self.cache["root"] else None,
            "git": self.cache["git"],
            "dirs": self.cache["dirs"],
            "favorites": self.cache["favorites"],
            "skills": self.cache["skills"],
        }

        # Groups in the order they are used. The keys that apply everywhere are
        # absent on purpose: the client owns those and never rebuilds them.
        if tool:
            primary = ACTIONS.get(tool, ACTIONS[FALLBACK_TOOL])(ctx)
        else:
            primary = shell_actions(ctx)

        ctx["groups"] = [
            {"name": name if tool else "go to", "actions": primary},
            {"name": "skills", "actions": skill_actions(ctx) if tool == "claude" else []},
            {"name": "projects", "actions": [] if tool else favorite_actions(ctx)},
            {"name": "run", "actions": command_actions(ctx)},
        ]
        return ctx

    def push_context(self, force=False):
        ctx = self.context(force=force)
        if ctx is not None:
            self.send(MSG_CONTEXT, ctx)

    # -- requests

    def request(self, req):
        op = req.get("op")
        if op == "refresh":
            self.push_context(force=True)
        elif op == "signal":
            self.signal(req.get("name", "int"))

    def signal(self, name):
        number = {"int": signal.SIGINT, "term": signal.SIGTERM,
                  "quit": signal.SIGQUIT, "hup": signal.SIGHUP}.get(name)
        pgid, _ = self.foreground()
        if number and pgid and pgid > 0:
            try:
                os.killpg(pgid, number)
            except OSError:
                pass

    # -- the loop

    def run(self):
        buf = b""
        need = None
        last_poll = 0.0
        self.push_context(force=True)

        while True:
            ready, _, _ = select.select([self.conn, self.fd], [], [], POLL_SECONDS)

            if self.fd in ready:
                try:
                    data = os.read(self.fd, 65536)
                except OSError:
                    data = b""
                if not data:
                    break
                self.send(MSG_OUT, data)

            if self.conn in ready:
                try:
                    chunk = self.conn.recv(65536)
                except OSError:
                    chunk = b""
                if not chunk:
                    break
                buf += chunk
                while True:
                    if need is None:
                        if len(buf) < 5:
                            break
                        kind, length = struct.unpack(">BI", buf[:5])
                        buf = buf[5:]
                        need = (kind, length)
                    kind, length = need
                    if len(buf) < length:
                        break
                    payload, buf = buf[:length], buf[length:]
                    need = None
                    self.handle(kind, payload)

            now = time.monotonic()
            if now - last_poll >= POLL_SECONDS:
                last_poll = now
                self.push_context()

        code = 0
        try:
            _, status = os.waitpid(self.pid, os.WNOHANG)
            code = os.WEXITSTATUS(status)
        except OSError:
            pass
        self.send(MSG_EXIT, {"code": code})

    def handle(self, kind, payload):
        if kind == MSG_DATA:
            try:
                os.write(self.fd, payload)
            except OSError:
                pass
        elif kind == MSG_RESIZE:
            try:
                size = json.loads(payload)
                self.resize(size.get("cols", 80), size.get("rows", 24))
            except ValueError:
                pass
        elif kind == MSG_REQUEST:
            try:
                self.request(json.loads(payload))
            except ValueError:
                pass

    def close(self):
        if self.pid:
            try:
                os.kill(self.pid, signal.SIGHUP)
            except OSError:
                pass
            # Reap it. Nothing does this for us any more — see the note in serve().
            for _ in range(20):
                try:
                    if os.waitpid(self.pid, os.WNOHANG)[0]:
                        break
                except OSError:
                    break
                time.sleep(0.05)
        if self.fd >= 0:
            try:
                os.close(self.fd)
            except OSError:
                pass
        try:
            self.conn.close()
        except OSError:
            pass


def serve_client(conn, addr, cwd, shell):
    print(f"[pty] session from {addr[0]}", flush=True)
    session = Session(conn, cwd, shell)
    try:
        session.start(cols=200, rows=50)
        session.run()
    except Exception as exc:  # a broken session must not take the agent down
        print(f"[pty] session error: {exc}", file=sys.stderr, flush=True)
    finally:
        session.close()
        print(f"[pty] session from {addr[0]} closed", flush=True)


def serve(port, cwd, shell):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", port))
    server.listen(4)
    print(f"[pty] {shell} on :{port}, starting in {cwd}", flush=True)

    # Sessions outlive their client's window only as long as the socket: a
    # closed window is a closed shell, which keeps the model simple. Detached
    # sessions are what tmux is for, and it runs inside this just fine.
    #
    # Shells are reaped explicitly when their session ends. Setting SIGCHLD to
    # SIG_IGN would be shorter and is what an earlier version did — but that
    # disposition is inherited through exec, and every descendant that waits on
    # its own children then gets ECHILD instead of an exit status.

    while True:
        conn, addr = server.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        threading.Thread(target=serve_client, args=(conn, addr, cwd, shell),
                         daemon=True).start()


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--cwd", default=HOME, help="where new shells start")
    parser.add_argument("--shell", default=os.environ.get("SHELL", "/bin/bash"))
    parser.add_argument("--dump-context", metavar="DIR",
                        help="print the context for a directory and exit")
    args = parser.parse_args()

    if args.dump_context:
        cwd = os.path.abspath(os.path.expanduser(args.dump_context))
        root = project_root(cwd)
        ctx = {
            "cwd": cwd, "cwd_label": short(cwd), "up": os.path.dirname(cwd),
            "tool": None, "root": short(root) if root else None,
            "git": git_head(cwd), "dirs": subdirs(cwd), "favorites": favorites(),
            "skills": skills(root),
        }
        ctx["groups"] = [
            {"name": "go to", "actions": shell_actions(ctx)},
            {"name": "projects", "actions": favorite_actions(ctx)},
            {"name": "run", "actions": command_actions(ctx)},
        ]
        print(json.dumps(ctx, indent=2, ensure_ascii=False))
        return

    serve(args.port, os.path.abspath(os.path.expanduser(args.cwd)), args.shell)


if __name__ == "__main__":
    main()
