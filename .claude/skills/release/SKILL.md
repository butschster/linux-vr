---
name: release
description: Cut a release of linux-vr — tag it, watch both halves build, and verify the artefacts actually arrived and actually work. Use when asked to release, ship, cut a tag or publish a version. Every check here exists because something got past the previous one.
---

# Releasing linux-vr

A release is not a tag. A tag is the cheap part. The release is the pair of
artefacts someone downloads onto a machine and a headset, and the only way to
know it is good is to fetch what was published and ask it questions.

**The tag is the version.** Nothing is edited by hand: the server takes it from
`-ldflags -X main.version`, the APK from `-PlinuxvrVersion`. If you find
yourself bumping a number in a file, something has regressed — fix that instead.

## Before tagging

Run these; do not assume any of them.

```sh
git branch --show-current          # main
git status --short                 # empty
git log --oneline origin/main -1   # your commit is on the remote
gh run list --limit 1              # CI green for that commit
```

A tag on a commit whose CI has not finished is a coin toss. Wait for it.

Decide the version from what changed, and remember Android: `versionCode` is
derived as `major*10000 + minor*100 + patch`, so it only ever increases if the
version does. Going backwards makes an APK that will not install over its
predecessor.

## Tagging

The annotated tag's message is the release description — GitHub shows it, and
`generate_release_notes` adds the commit list underneath. Write it for someone
who has never seen the project: what it is, what changed, what is measured.

```sh
git tag -a v1.2.3 -m "1.2.3 — one line on what this is

Two or three paragraphs. What a reader gets, not what the diff did."
git push origin v1.2.3
```

## Watching it build

```sh
gh run list --workflow=release.yml --limit 1
gh run view <id>            # server, client, publish — all three must pass
```

`publish` only runs for `refs/tags/v*`. A `workflow_dispatch` run builds the
artefacts and stops, which is how you test the workflow without cutting a tag —
but note it can only be dispatched if `release.yml` is on the **default branch**.

## Verifying — the part that matters

Never trust the green tick. Download what was actually published:

```sh
mkdir -p /tmp/rel && cd /tmp/rel && rm -f ./*
gh release download vX.Y.Z -R vr-meta/linux-vr
gh release view vX.Y.Z --json assets --jq '.assets[] | "\(.name) \(.size)"'
```

Three assets, no more and no fewer: `linux-vr-server-linux-amd64`,
`linux-vr-server-linux-arm64`, `linux-vr.apk`.

**The server binary answers for itself.**

```sh
chmod +x linux-vr-server-linux-amd64
./linux-vr-server-linux-amd64 version        # must equal the tag, without the v
file linux-vr-server-linux-amd64 | grep -o "statically linked"
file linux-vr-server-linux-arm64 | grep -o "ARM aarch64"
```

Dynamically linked means `CGO_ENABLED=0` was lost somewhere and the binary will
fail on an older distribution than the runner. The arm64 file being x86 means
the loop over architectures broke and nobody noticed, because it still uploads.

**The APK has to be asked its version, not assumed.**

```sh
unzip -p linux-vr.apk AndroidManifest.xml | strings -el | grep -m1 -E '^[0-9]+\.[0-9]+\.[0-9]+'
```

This exists because **v1.0.0 shipped reporting `versionName=0.2`**. The number
was hardcoded in `build.gradle.kts`, the workflow never passed the tag, and
nothing noticed until the APK was installed on a headset and asked. Both are
fixed; this check is what keeps them fixed.

**The signing key is the one failure that cannot be repaired later.**

Android refuses an update signed by a different key than the installed copy. The
workflow falls back to a debug key when `ANDROID_KEYSTORE_BASE64` is missing —
the build still succeeds, and every release would be signed with a different
ephemeral key. Confirm the step ran rather than being skipped:

```sh
gh run view <id> --json jobs \
  --jq '.jobs[] | select(.name=="client") | .steps[] | "\(.conclusion)  \(.name)"'
```

`Restore the signing key` must say `success`. If it says `skipped`, the secret is
gone from the repository and the release must be deleted and re-cut, not patched.

**Install it, if a headset is attached.**

```sh
adb install -r linux-vr.apk
adb shell dumpsys package dev.butschster.linuxvr | grep versionName
adb shell monkey -p dev.butschster.linuxvr -c android.intent.category.LAUNCHER 1
sleep 3 && adb shell pidof dev.butschster.linuxvr && echo ALIVE || echo DEAD
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` here is **expected exactly once**: when the
headset holds a locally built, debug-signed copy. Uninstall and install again.
Seeing it a second time, between two released builds, means the signing key
changed and that is a real defect.

A successful `am start` proves nothing on Horizon OS — check `pidof`.

## When something is wrong

While nothing depends on the tag yet, fix it in place rather than shipping a
patch release for a broken artefact:

```sh
gh release delete vX.Y.Z --yes --cleanup-tag
git tag -d vX.Y.Z
# fix, commit, push, then tag again
```

Once anyone could have downloaded it, do not reuse the tag. Cut the next patch
version and say why in its message.

## After

- Say plainly what was verified and what was not. "Installs and launches" is not
  "works in the headset" — nobody has looked through the lenses yet, and
  `screencap` returns black because the windows are composition layers.
- If the release changed how the host and client talk, the version is now the
  first thing to compare when they disagree. That is why the tray menu and
  `/v1/config` both report it.
