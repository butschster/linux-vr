// Package input injects pointer input coming from the headset into the Linux
// session.
//
// Why a virtual absolute pointer rather than a relative mouse: the headset knows
// where the ray hits the layer, not how far the pointer should travel.
// Converting that into relative deltas would need the current cursor position,
// which no Wayland client is allowed to read. An absolute device sidesteps the
// problem entirely.
package input

import (
	"bufio"
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"

	"github.com/vr-meta/linux-vr/host/internal/monitors"
	"github.com/vr-meta/linux-vr/host/internal/uinput"
)

// Pointer is the one virtual device every window shares.
//
// The monitor rectangle is not stored here but passed per move: each connection
// drives a different screen and they arrive interleaved.
type Pointer struct {
	mu      sync.Mutex
	device  *uinput.Device
	desktop [2]int
}

var buttons = map[string]uintptr{
	"left":   uinput.BtnLeft,
	"right":  uinput.BtnRight,
	"middle": uinput.BtnMiddle,
}

// NewPointer creates the device.
func NewPointer(desktopWidth, desktopHeight int) (*Pointer, error) {
	device, err := uinput.Open()
	if err != nil {
		return nil, err
	}

	for _, ev := range []uintptr{uinput.EvKey, uinput.EvAbs, uinput.EvRel, uinput.EvSyn} {
		if err := device.EnableEvent(ev); err != nil {
			return nil, err
		}
	}
	for _, code := range buttons {
		if err := device.EnableKey(code); err != nil {
			return nil, err
		}
	}
	for _, axis := range []uintptr{uinput.AbsX, uinput.AbsY} {
		if err := device.EnableAbs(axis); err != nil {
			return nil, err
		}
	}
	for _, rel := range []uintptr{uinput.RelWheel, uinput.RelHWheel} {
		if err := device.EnableRel(rel); err != nil {
			return nil, err
		}
	}
	if err := device.EnableProp(uinput.PropPointer); err != nil {
		return nil, err
	}
	for _, axis := range []uint16{uinput.AbsX, uinput.AbsY} {
		if err := device.SetupAbs(axis, 0, uinput.AbsMax); err != nil {
			return nil, err
		}
	}
	if err := device.Create("linux-vr pointer", 0x1234, 0x5678); err != nil {
		return nil, err
	}
	return &Pointer{device: device, desktop: [2]int{desktopWidth, desktopHeight}}, nil
}

// Move places the pointer at a fraction of a monitor's rectangle.
func (p *Pointer) Move(x, y float64, region *monitors.Monitor) {
	x = clamp(x)
	y = clamp(y)

	// Fractions of the window become fractions of the whole desktop, so that
	// pointing at the middle of a streamed monitor lands in the middle of that
	// monitor and not in the middle of the desk.
	if region != nil && p.desktop[0] > 0 && p.desktop[1] > 0 {
		x = (float64(region.X) + x*float64(region.Width)) / float64(p.desktop[0])
		y = (float64(region.Y) + y*float64(region.Height)) / float64(p.desktop[1])
	}

	p.mu.Lock()
	defer p.mu.Unlock()
	_ = p.device.Emit(uinput.EvAbs, uinput.AbsX, int32(x*uinput.AbsMax))
	_ = p.device.Emit(uinput.EvAbs, uinput.AbsY, int32(y*uinput.AbsMax))
	_ = p.device.Sync()
}

// Button presses or releases one of left, right, middle.
func (p *Pointer) Button(name string, pressed bool) {
	code, ok := buttons[name]
	if !ok {
		return
	}
	value := int32(0)
	if pressed {
		value = 1
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	_ = p.device.Emit(uinput.EvKey, uint16(code), value)
	_ = p.device.Sync()
}

// Scroll moves the wheel by whole clicks.
func (p *Pointer) Scroll(dx, dy int32) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if dy != 0 {
		_ = p.device.Emit(uinput.EvRel, uinput.RelWheel, dy)
	}
	if dx != 0 {
		_ = p.device.Emit(uinput.EvRel, uinput.RelHWheel, dx)
	}
	_ = p.device.Sync()
}

func (p *Pointer) Close() { _ = p.device.Close() }

func clamp(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

// -------------------------------------------------------------------- server

// Service speaks the pointer protocol.
//
// One command per line, deliberately human-readable so it can be driven from a
// terminal while debugging:
//
//	list          reply with the monitor list, then `end`
//	use <n>       this connection drives monitor n (index or connector name)
//	m <x> <y>     absolute position within that monitor, floats in 0..1
//	d <button>    press    (left | right | middle)
//	u <button>    release
//	s <dx> <dy>   scroll, integer clicks
type Service struct {
	pointer *Pointer
	layout  monitors.Layout
}

func New(pointer *Pointer, layout monitors.Layout) *Service {
	return &Service{pointer: pointer, layout: layout}
}

func (s *Service) Serve(listener net.Listener) error {
	for {
		conn, err := listener.Accept()
		if err != nil {
			return err
		}
		if tcp, ok := conn.(*net.TCPConn); ok {
			_ = tcp.SetNoDelay(true)
		}
		// A goroutine per connection: windows send interleaved, and a single
		// handler would let one window freeze the others.
		go s.handle(conn)
	}
}

func (s *Service) handle(conn net.Conn) {
	defer conn.Close()
	address := conn.RemoteAddr().String()
	log.Printf("[input] connected: %s", address)
	defer log.Printf("[input] disconnected: %s", address)

	// Each connection carries its own monitor. They arrive interleaved from
	// several windows, so this cannot live on the shared pointer.
	var region *monitors.Monitor

	scanner := bufio.NewScanner(conn)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) == 0 {
			continue
		}
		switch fields[0] {
		case "list":
			// The headset asks how many screens exist so it can open one window
			// per screen. Discovering it at runtime is the only way to be right
			// on every desk.
			var reply strings.Builder
			for _, m := range s.layout.Monitors {
				fmt.Fprintf(&reply, "monitor %d %s %d %d %d\n",
					m.Index, m.Connector, m.Width, m.Height, m.Port)
			}
			reply.WriteString("end\n")
			if _, err := conn.Write([]byte(reply.String())); err != nil {
				return
			}
		case "use":
			if len(fields) < 2 {
				continue
			}
			m := s.layout.Resolve(fields[1])
			if m == nil {
				log.Printf("[input] %s: unknown monitor %q", address, fields[1])
				continue
			}
			region = m
			log.Printf("[input] %s: drives [%d] %s", address, m.Index, m.Connector)
		case "m":
			if len(fields) < 3 {
				continue
			}
			x, err1 := strconv.ParseFloat(fields[1], 64)
			y, err2 := strconv.ParseFloat(fields[2], 64)
			if err1 == nil && err2 == nil {
				s.pointer.Move(x, y, region)
			}
		case "d", "u":
			if len(fields) < 2 {
				continue
			}
			s.pointer.Button(fields[1], fields[0] == "d")
		case "s":
			if len(fields) < 3 {
				continue
			}
			dx, err1 := strconv.Atoi(fields[1])
			dy, err2 := strconv.Atoi(fields[2])
			if err1 == nil && err2 == nil {
				s.pointer.Scroll(int32(dx), int32(dy))
			}
		}
		// A malformed line is not worth dropping the connection over: the
		// window sending it is showing a desktop someone is working in.
	}
}
