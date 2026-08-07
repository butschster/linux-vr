# Latency budget

The motion-to-photon target for a desktop is softer than for a VR game: the head
moves, the content does not, and compositor reprojection of the layer hides most
of it. But **click-to-photon** for the mouse and for typing is stricter —
reprojection does not help there at all.

Target end-to-end: **40–60 ms**.

| Stage | Budget | Status |
|---|---|---|
| Capture + Ubuntu compositor | 3–8 ms | KMS/DMA-BUF, zero-copy — within budget |
| Encode (hardware, slice-based) | 3–6 ms | measured 160 fps at 1440p, 1.78× headroom |
| Network (Wi-Fi 6) | 4–12 ms, tail past 30 | out of scope, reference confirmed on Windows |
| Client jitter buffer | 5–15 ms | **the main tuning target** |
| `MediaCodec` decode | 5–10 ms | not measured |
| Composition layer → photons | 8–14 ms at 90 Hz | set by the compositor |

Every number except the encoder still needs verification on this hardware.

## Measure p99, not the mean

One dropped frame per second feels worse than a steady extra 10 ms. The
Moonlight overlay reports decode time, network latency and dropped frames —
look at the tail of the distribution and the loss percentage.

## The transport is ours, and that is now a decision

Sunshine was once the plan for this layer. It is not any more: it replaces one
layer and cannot give multiple independent monitor surfaces, which is the whole
point of the project. See `CLAUDE.md`.

What that costs is exactly the row above. **We have no FEC, no loss recovery and
no bitrate adaptation** — on TCP a lost packet becomes head-of-line blocking,
which is a frozen picture rather than a degraded one.

Whether that matters is the measurement this page is still missing. If p99 and
loss on the real link turn out bad, the choice is between taking Sunshine
wholesale and putting FEC on our own transport. Until then, no feature list
settles it.

## Where a custom client wins

The jitter buffer is where Moonlight makes a reasonable default and a custom
client can do better.

Moonlight submits a frame "as soon as it decodes". The right thing is to tie
submission to the **predicted display time from `xrWaitFrame`**: hand the frame
to the compositor when the compositor will actually take it, not when it happens
to be ready.

That removes the beat between the independent stream and headset rates, which
otherwise shows up as periodic hitching even when the numbers look flat.

## Encoder settings that matter

To verify when moving from Sunshine defaults to a custom configuration.

- **H.264 High, not HEVC** for the first iteration. HEVC saves ~30% bitrate but
  adds decode latency on `MediaCodec`. The difference may turn out to be within
  noise — measure both on XR2 Gen 2 before choosing.
- **Intra-refresh instead of periodic IDR.** A keyframe every N frames is a
  bitrate spike, which over Wi-Fi turns into a dropped frame and a noticeable
  hitch in the headset. Intra-refresh spreads I-macroblocks evenly. Supported by
  both VAAPI and NVENC.
- **CBR**, zero-latency profile, **B-frames off**, one reference frame.
- **Slice-based output** — the encoder emits slices as they are ready and
  transmission starts before the frame is complete. Saves roughly one frame time
  at the end of the pipeline. Available on this hardware: every profile goes
  through `VAEntrypointEncSlice`.
