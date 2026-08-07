---
name: install
description: Install linux-vr from a GitHub release — the server onto this Linux machine, the app onto the headset — and configure it until doctor is clean and a window opens. Use when asked to install, update, set up, or put the latest version on a machine or a headset.
---

# Installing linux-vr from a release

Two halves from **one release**. They are built from a single commit and the
wire between them is only guaranteed for a matched pair; a headset a version
ahead of its host is the kind of bug that costs an evening and looks like a
network problem.

Do not build from source to install. `make dev` is for working on the code; it
signs with a per-machine debug key, which then has to be uninstalled before a
real release will go on.

```sh
REPO=vr-meta/linux-vr
gh release view --repo $REPO --json tagName,publishedAt \
  --jq '"latest: \(.tagName)  \(.publishedAt)"'
```

## The Linux machine

### 1. The binary

```sh
sudo curl -fL \
  https://github.com/vr-meta/linux-vr/releases/latest/download/linux-vr-server-linux-amd64 \
  -o /usr/local/bin/linux-vr-server
sudo chmod +x /usr/local/bin/linux-vr-server
linux-vr-server version          # must match the release you meant to install
```

`-arm64` instead for an ARM host. The binary is static: no runtime, no
libraries, nothing to install alongside it.

### 2. The two permissions it cannot grant itself

```sh
sudo usermod -aG input $USER
```

That is `/dev/uinput`, for the pointer and the virtual keyboard. **It takes
effect on the next login** — `newgrp input` in a shell is not enough for a
service started by systemd.

```sh
echo "$USER ALL=(root) NOPASSWD: /usr/bin/ffmpeg" | sudo tee /etc/sudoers.d/linux-vr
```

That is for `kmsgrab`, which needs `CAP_SYS_ADMIN` to capture through KMS. `-n`
is passed to sudo by the server, so without this rule the encoder fails at once
instead of waiting on a password prompt nobody is there to answer. Check
`which ffmpeg` matches the path in the rule.

### 3. Ask the machine what it is missing

```sh
linux-vr-server doctor
```

Every check in it is a failure that has actually happened on this hardware. Do
not proceed past a `FAIL` — each one is a symptom that is invisible from inside
the headset. The two that come up most:

- **no CRTC ids** — DRM state needs root; without it `kmsgrab` captures whichever
  output it feels like, which on a two-monitor desk is a coin toss
- **nothing renders tray icons** — on Ubuntu, `gnome-extensions enable
  ubuntu-appindicators@ubuntu.com`. The icon registers on the bus regardless and
  is simply drawn by nothing, which is indistinguishable from a server that
  never started

### 4. Run it as a service

From a clone, `make install` writes the unit and starts it. By hand:

```sh
systemctl --user enable --now linux-vr-server
systemctl --user status linux-vr-server --no-pager
journalctl --user -u linux-vr-server -f
```

A **user** service, not a system one: it asks mutter which monitors exist, which
needs the session bus; it pastes into the session clipboard; and the pointer it
creates belongs in the session. Root has none of those.

### 5. Configure it

Open **`http://localhost:9099`** on the machine itself. Name, capture rate and
quality, and where speech is recognised. Settings can only be changed from the
machine — from anywhere else the page is read-only and the endpoints answer 403,
because there is no authentication anywhere in this project and the settings
hold an API key.

Dictation needs an endpoint speaking the OpenAI transcription API: a key for the
hosted one, or any address of your own. A local model counts — run it on
`127.0.0.1` and no audio leaves the building. The **Test it** button says whether
it works without putting the headset on.

## The headset

```sh
gh release download --repo vr-meta/linux-vr --pattern linux-vr.apk --dir /tmp
adb install -r /tmp/linux-vr.apk
adb shell dumpsys package dev.butschster.linuxvr | grep versionName
```

The version must match the server's. If adb cannot see the headset, or says
`unauthorized`, or the install fails on a signature, the `headset` skill covers
every one of those.

It appears in the library under **Unknown Sources**.

## Proving it works, before wearing anything

```sh
linux-vr-server doctor                                   # clean
curl -s http://localhost:9099/v1/info | head -40         # screens and services
timeout 3 nc 127.0.0.1 9100 | wc -c                      # bytes means the encoder runs
```

A non-zero byte count from that last one is the whole host pipeline — KMS
capture, VAAPI encode, the socket — proven in three seconds. Zero means read the
`capture` lines in the log; the reason is always there.

Then from the headset itself:

```sh
HOST=$(hostname -I | awk '{print $1}')
adb shell "curl -s --max-time 3 http://$HOST:9099/v1/info | head -c 120"
```

If that answers but the app's server list is empty, the network is dropping
broadcast, not failing to run a server. Type the address in instead.

## Updating

Same two commands, both halves, same release:

```sh
sudo curl -fL https://github.com/vr-meta/linux-vr/releases/latest/download/linux-vr-server-linux-amd64 \
  -o /usr/local/bin/linux-vr-server && sudo chmod +x /usr/local/bin/linux-vr-server
systemctl --user restart linux-vr-server

gh release download --repo vr-meta/linux-vr --pattern linux-vr.apk --dir /tmp --clobber
adb install -r /tmp/linux-vr.apk
```

`-r` keeps saved servers. Releases are signed with a stable key, so an update
never needs an uninstall — if one is demanded, say so rather than working around
it, because it means the key changed.

## Related

- `headset` — adb, wireless, logs, and why a screenshot is black
- `release` — cutting a release and verifying its artefacts
- `vr-diagnostics` — a window that is black, frozen, or whose pointer misses
