package voice

import (
	"fmt"
	"strings"
	"time"

	"github.com/vr-meta/linux-vr/host/internal/uinput"
)

// Keyboard is just enough of a keyboard to paste and to press the keys a
// terminal cannot live without.
//
// Dictation inserts text but cannot submit it, and no soft keyboard offers Esc
// or Ctrl+C reliably. Without these the headset can type but cannot interrupt,
// complete or switch modes.
type Keyboard struct {
	device *uinput.Device
}

const (
	keyLeftCtrl = 29
	keyV        = 47
)

// Scancodes, not characters: this is the layout-independent half of the
// keyboard, which is exactly why text does not go through it.
var namedKeys = map[string]uint16{
	"enter":     28,
	"tab":       15,
	"esc":       1,
	"backspace": 14,
	"up":        103,
	"down":      108,
	"left":      105,
	"right":     106,
	"home":      102,
	"end":       107,
	"delete":    111,
	"space":     57,
	"l":         38,
	"c":         46,
	"d":         32,
	"r":         19,
	"z":         44,
	"v":         keyV,
}

var modifiers = map[string]uint16{
	"ctrl":  29,
	"shift": 42,
	"alt":   56,
}

func NewKeyboard() (*Keyboard, error) {
	device, err := uinput.Open()
	if err != nil {
		return nil, err
	}
	if err := device.EnableEvent(uinput.EvKey); err != nil {
		return nil, err
	}
	if err := device.EnableEvent(uinput.EvSyn); err != nil {
		return nil, err
	}

	enabled := map[uint16]bool{keyLeftCtrl: true, keyV: true}
	for _, code := range namedKeys {
		enabled[code] = true
	}
	for _, code := range modifiers {
		enabled[code] = true
	}
	for code := range enabled {
		if err := device.EnableKey(uintptr(code)); err != nil {
			return nil, err
		}
	}

	// A different product id from the pointer, so the two show up as two
	// devices in libinput's log rather than as one confusing one.
	if err := device.Create("linux-vr keyboard", 0x1234, 0x5679); err != nil {
		return nil, err
	}
	return &Keyboard{device: device}, nil
}

// Press handles "enter", "ctrl+c", "shift+tab" — modifiers first, key last.
func (k *Keyboard) Press(combo string) error {
	parts := strings.Split(combo, "+")
	name := parts[len(parts)-1]
	code, ok := namedKeys[name]
	if !ok {
		return fmt.Errorf("unknown key %q", name)
	}
	var mods []uint16
	for _, part := range parts[:len(parts)-1] {
		if mod, ok := modifiers[part]; ok {
			mods = append(mods, mod)
		}
	}
	return k.press(code, mods)
}

// Paste is Ctrl+V, the one chord the clipboard path depends on.
func (k *Keyboard) Paste() error { return k.press(keyV, []uint16{keyLeftCtrl}) }

func (k *Keyboard) press(code uint16, mods []uint16) error {
	for _, mod := range mods {
		if err := k.device.Emit(uinput.EvKey, mod, 1); err != nil {
			return err
		}
	}
	if err := k.device.Emit(uinput.EvKey, code, 1); err != nil {
		return err
	}
	if err := k.device.Sync(); err != nil {
		return err
	}
	// A key held for zero time is a key some applications never see.
	time.Sleep(20 * time.Millisecond)

	if err := k.device.Emit(uinput.EvKey, code, 0); err != nil {
		return err
	}
	// Released in reverse, the way a hand would let go.
	for i := len(mods) - 1; i >= 0; i-- {
		if err := k.device.Emit(uinput.EvKey, mods[i], 0); err != nil {
			return err
		}
	}
	return k.device.Sync()
}

func (k *Keyboard) Close() { _ = k.device.Close() }
