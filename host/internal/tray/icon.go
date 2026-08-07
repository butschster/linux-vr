package tray

// The icon is drawn here rather than shipped as a file.
//
// Two states have to be told apart at a glance — something is watching, or
// nothing is — and drawing them costs a few loops against carrying two PNGs
// that have to stay in step with the code that chooses between them. image/png
// is in the standard library, so this adds nothing to the binary but itself.

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"log"
	"sync"
)

// 22 px is what GNOME's panel renders a tray icon at. Drawing at exactly that
// size avoids the smearing that scaling a larger bitmap down produces on
// one-pixel strokes.
const size = 22

var (
	// Light grey, because the panel this sits in is dark on every desktop this
	// runs on. Monochrome is also what a tray icon is conventionally.
	idleColour = color.NRGBA{0xd0, 0xd3, 0xda, 0xff}
	liveColour = color.NRGBA{0x5f, 0xc9, 0x87, 0xff}

	once             sync.Once
	idlePNG, livePNG []byte
)

// icon returns the PNG for a state. Encoded once: it never changes, and the
// panel asks for it every time the state flips.
func icon(live bool) []byte {
	once.Do(func() {
		idlePNG = encode(draw(false))
		livePNG = encode(draw(true))
	})
	if live {
		return livePNG
	}
	return idlePNG
}

// draw paints a screen, with a dot on it when something is watching.
func draw(live bool) *image.NRGBA {
	colour := idleColour
	if live {
		colour = liveColour
	}
	img := image.NewNRGBA(image.Rect(0, 0, size, size))

	fill := func(x0, y0, x1, y1 int) {
		for y := y0; y <= y1; y++ {
			for x := x0; x <= x1; x++ {
				if x >= 0 && y >= 0 && x < size && y < size {
					img.SetNRGBA(x, y, colour)
				}
			}
		}
	}

	// The screen: a two-pixel frame, hollow, so it reads as a display rather
	// than as a solid block at this size.
	const left, top, right, bottom = 2, 4, 19, 15
	fill(left, top, right, top+1)
	fill(left, bottom-1, right, bottom)
	fill(left, top, left+1, bottom)
	fill(right-1, top, right, bottom)

	// The stand.
	fill(10, bottom+1, 11, bottom+3)
	fill(6, bottom+4, 15, bottom+5)

	// A filled dot inside the screen while a window is watching. Present or
	// absent reads faster than a change of colour, which is why the icon does
	// both rather than either.
	if live {
		fill(9, 8, 12, 11)
	}
	return img
}

func encode(img *image.NRGBA) []byte {
	var buffer bytes.Buffer
	if err := png.Encode(&buffer, img); err != nil {
		// Cannot happen for an in-memory image, but an icon that silently
		// becomes zero bytes is a puzzle worth one line to avoid.
		log.Printf("[tray] cannot encode the icon: %v", err)
	}
	return buffer.Bytes()
}
