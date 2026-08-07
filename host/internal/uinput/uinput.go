// Package uinput creates virtual input devices in the kernel.
//
// Why uinput at all, rather than a Wayland protocol: mutter does not implement
// zwp_virtual_keyboard, so wtype and everything built on it is a dead end on
// GNOME. uinput sits below the compositor and works regardless of it — the user
// only has to be in the `input` group.
//
// Raw ioctls rather than a library: this is a handful of numbers and two
// structs, and every Go uinput package on offer wraps exactly this while
// bringing its own opinions about what a device should look like.
package uinput

import (
	"fmt"
	"os"
	"syscall"
	"time"
	"unsafe"
)

// Event types and the codes this project needs.
const (
	EvSyn = 0x00
	EvKey = 0x01
	EvRel = 0x02
	EvAbs = 0x03

	SynReport = 0x00

	RelHWheel = 0x06
	RelWheel  = 0x08

	AbsX = 0x00
	AbsY = 0x01

	BtnLeft   = 0x110
	BtnRight  = 0x111
	BtnMiddle = 0x112

	// Without this property libinput decides an absolute device must be a
	// touchscreen and turns coordinates into taps rather than pointer motion.
	PropPointer = 0x00

	// The pointer works in a fixed grid rather than in pixels: the headset does
	// not know the desktop resolution, and libinput scales this range onto
	// whatever the screen actually is.
	AbsMax = 65535
)

func iow(nr, size uintptr) uintptr { return (1 << 30) | (size << 16) | ('U' << 8) | nr }
func io_(nr uintptr) uintptr       { return ('U' << 8) | nr }

var (
	uiDevCreate  = io_(1)
	uiDevDestroy = io_(2)
	uiDevSetup   = iow(3, 92)
	uiAbsSetup   = iow(4, 28)
	uiSetEvBit   = iow(100, 4)
	uiSetKeyBit  = iow(101, 4)
	uiSetRelBit  = iow(102, 4)
	uiSetAbsBit  = iow(103, 4)
	uiSetPropBit = iow(110, 4)
)

// Device is an open /dev/uinput handle, before or after the device exists.
type Device struct {
	file *os.File
}

// Open takes the handle. Everything else is configuration until Create.
func Open() (*Device, error) {
	file, err := os.OpenFile("/dev/uinput", os.O_WRONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		if os.IsPermission(err) {
			return nil, fmt.Errorf("cannot open /dev/uinput: %w\n"+
				"Add yourself to the `input` group:\n"+
				"    sudo usermod -aG input $USER\n"+
				"then log out and back in", err)
		}
		return nil, err
	}
	return &Device{file: file}, nil
}

// These ioctls take the bit as an immediate value, not a pointer to one.
// Handing over the address of a buffer makes the kernel read that address as
// the bit number and reject it with EINVAL — the mistake costs an afternoon
// because the error says nothing about which argument was wrong.
func (d *Device) set(request, bit uintptr) error {
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, d.file.Fd(), request, bit); errno != 0 {
		return fmt.Errorf("uinput ioctl %#x(%d): %w", request, bit, errno)
	}
	return nil
}

func (d *Device) EnableEvent(ev uintptr) error  { return d.set(uiSetEvBit, ev) }
func (d *Device) EnableKey(code uintptr) error  { return d.set(uiSetKeyBit, code) }
func (d *Device) EnableRel(code uintptr) error  { return d.set(uiSetRelBit, code) }
func (d *Device) EnableAbs(code uintptr) error  { return d.set(uiSetAbsBit, code) }
func (d *Device) EnableProp(prop uintptr) error { return d.set(uiSetPropBit, prop) }

// SetupAbs declares an absolute axis and its range.
func (d *Device) SetupAbs(code uint16, min, max int32) error {
	// uinput_abs_setup: u16 code, 2 bytes padding, then input_absinfo — value,
	// minimum, maximum, fuzz, flat, resolution, all s32.
	var buf [28]byte
	put16(buf[0:], code)
	put32(buf[4:], 0)
	put32(buf[8:], uint32(min))
	put32(buf[12:], uint32(max))
	return d.ioctlBuf(uiAbsSetup, buf[:])
}

// Create brings the device into existence.
func (d *Device) Create(name string, vendor, product uint16) error {
	// uinput_setup: input_id (bustype, vendor, product, version), char name[80],
	// u32 ff_effects_max.
	var buf [92]byte
	put16(buf[0:], 0x03) // BUS_USB — libinput treats a virtual bus with suspicion
	put16(buf[2:], vendor)
	put16(buf[4:], product)
	put16(buf[6:], 1)
	copy(buf[8:88], name)
	if err := d.ioctlBuf(uiDevSetup, buf[:]); err != nil {
		return err
	}
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, d.file.Fd(), uiDevCreate, 0); errno != 0 {
		return fmt.Errorf("UI_DEV_CREATE: %w", errno)
	}
	// udev needs a moment to notice the device. Writing immediately means the
	// first events land nowhere, which looks like a device that does not work
	// rather than one that is not ready.
	time.Sleep(300 * time.Millisecond)
	return nil
}

func (d *Device) ioctlBuf(request uintptr, buf []byte) error {
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, d.file.Fd(), request,
		uintptr(unsafe.Pointer(&buf[0])))
	if errno != 0 {
		return fmt.Errorf("uinput ioctl %#x: %w", request, errno)
	}
	return nil
}

// Emit writes one input event.
func (d *Device) Emit(evType, code uint16, value int32) error {
	// input_event on 64-bit Linux: struct timeval (two longs), u16 type,
	// u16 code, s32 value. A zero timestamp means "now" to the kernel.
	var buf [24]byte
	put16(buf[16:], evType)
	put16(buf[18:], code)
	put32(buf[20:], uint32(value))
	_, err := d.file.Write(buf[:])
	return err
}

// Sync ends an event group. Nothing an event says takes effect until this.
func (d *Device) Sync() error { return d.Emit(EvSyn, SynReport, 0) }

func (d *Device) Close() error {
	_, _, _ = syscall.Syscall(syscall.SYS_IOCTL, d.file.Fd(), uiDevDestroy, 0)
	return d.file.Close()
}

func put16(b []byte, v uint16) {
	b[0] = byte(v)
	b[1] = byte(v >> 8)
}

func put32(b []byte, v uint32) {
	b[0] = byte(v)
	b[1] = byte(v >> 8)
	b[2] = byte(v >> 16)
	b[3] = byte(v >> 24)
}
