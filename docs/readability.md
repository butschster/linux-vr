# Text readability on Quest 3

Measured 2026-08-06 with milestone B, live in the headset. This is the central
number of the project: the virtual display resolution, the desktop font scaling
and the whole monitor layout follow from it.

## Conditions

| | |
|---|---|
| Layer | cylindrical, 2560×1440 |
| Angular width | 50° horizontal |
| Distance | 1.5 m |
| Density | **51.2 pixels per degree** |
| Source | H.264, 90 fps, ~97 Mbit/s, DejaVu Sans Mono |

## Result

| Size | Verdict | Angular size |
|---|---|---|
| 24 px | comfortable with margin | 0.47° |
| **20 px** | **comfortable** | **0.39°** |
| 18 px | readable | 0.35° |
| 16 px | borderline | 0.31° |
| below 16 px | unreadable | < 0.31° |

The moving bar ran smoothly and the frame counter advanced without gaps, so
these numbers come from a healthy pipeline rather than from artefacts.

The first impression was stricter (threshold 18, comfort 24). Once the eye
adapted, the boundary moved down one step. That is a useful observation in
itself: **the readability threshold in VR depends on adaptation**, so it should
not be measured in the first minute.

## What follows

### Glyph angular size decides, not pixel density

Verified live: **stretch the layer to maximum and even 10 px becomes readable.**

This refutes the obvious-sounding conclusion "you need 51 px/degree or text
falls apart". The mechanism is different:

```
density:       R = W / θ            pixels per degree
angular size:  α = h / R = h·θ / W  degrees per glyph
```

Widening θ lowers R but **raises α** — every glyph becomes angularly larger.
Readability follows α, not R.

| θ | R, px/deg | 10 px glyph | 15 px glyph | 20 px glyph |
|---|---|---|---|---|
| 50° | 51.2 | 0.20° ✗ | 0.29° ✗ | **0.39°** ✓ |
| 66° | 38.8 | 0.26° ✗ | **0.39°** ✓ | 0.52° ✓ |
| 110° | 23.3 | **0.43°** ✓ | 0.64° ✓ | 0.86° ✓ |

The comfort threshold is **α ≈ 0.39°** and it is independent of θ. What θ
decides is which font size lands inside it.

There is a second constraint from below: once R drops well under ~25 px/degree
(the panel's effective density) the source no longer carries enough detail for
the panel to show, and glyphs turn mushy. The working corridor is
**R between 25 and 50 px/degree**.

### Ubuntu settings: don't scale, widen the layer

The practical conclusion is the opposite of the first guess.

Ubuntu's default UI font is about 15 pixels. Instead of pushing scaling to
125–150%, it is enough to **widen the layer to roughly 66°**: then 15 px lands
exactly on 0.39° and everything reads at 100% scaling.

| Option | θ | Ubuntu scaling | Columns in view |
|---|---|---|---|
| narrow layer, large font | 50° | 125% | ~225 |
| **wide layer, stock font** | **66°** | **100%** | **~284** |

**Verified in practice 2026-08-06.** A screenshot of a real Ubuntu desktop at
stock settings (scale `1.0`, UI `Ubuntu Sans 11` ≈ 14.7 px, terminal
`Ubuntu Sans Mono 13` ≈ 17.3 px) shown on a 66° layer reads effortlessly —
both the UI and the terminal.

Predicted values: 0.39° for the UI, 0.44° for the terminal. Both matched the
measured comfort threshold. **Desktop scaling is not needed.**

The wide-layer option wins on three counts: more visible text at the same
readability, no fight with display scaling (which still breaks applications on
Linux), and less bitrate spent per useful pixel — R drops from 51 to 39, closer
to what the panel actually resolves.

### The limit on widening is optics, not arithmetic

The table permits stretching to 110°, but in practice you should not:

- the edges of such a layer land in the **worst zone of the lenses**, where
  sharpness drops regardless of what we transmitted;
- reading the edge requires **turning your head** rather than moving your eyes;
- R falls below 25 px/degree and text starts to smear.

A sensible ceiling for one layer is **60–75°**. Beyond that, add layers.

### More area means more layers

Past ~75° the right answer is **multiple independent layers**, one per monitor,
each with its own resolution and density. The Quest 3 compositor allows 32, so
the limit is the encoder and the network, not the compositor.

### How much text fits

Text density per degree of field of view is capped by the eye and the optics,
not by our pipeline: at α = 0.39° with a monospace font (advance ≈ 0.6 of
height) the ceiling is **about 4.3 columns per degree**, for any combination
of θ and W.

- 66° layer: **~284 columns**, an 80-column terminal spans ≈ 19°
- 50° layer: ~225 columns

So a 66°-wide layer comfortably holds **three 80-column terminals** side by
side. That is the reference point for layout.

## To check next

- **`XR_META_recommended_layer_resolution`** — the runtime reports the optimal
  resolution for a layer with a given placement. Worth querying for θ = 66° and
  checking whether the answer falls inside the 25–50 px/degree corridor derived
  above. If it does, we can trust it instead of re-measuring by hand on every
  layout change.
- **`XR_FB_composition_layer_settings`** — per-layer sharpening and
  supersampling. It may push the readability threshold down, which would mean
  more area at the same density.
- Repeat the measurement on **Quest 3S**: different optics (Fresnel), smaller
  panel and FOV, so the threshold will arrive sooner.
