// Package monitors is the single source of truth for which screens exist and in
// what order.
//
// Three things have to agree: capture picks a CRTC, the input service maps
// pointer coordinates into a monitor's rectangle, and the headset opens one
// window per monitor. If their orderings disagree, clicks land on the wrong
// screen — and that failure is invisible until someone tries to click something.
//
// So ordering lives here, once: monitors sorted by connector name.
//
// Two naming systems have to be reconciled. DRM calls a connector HDMI-A-1;
// GNOME's logical monitors call the same thing HDMI-1. Neither is wrong. The
// mapping is needed because the CRTC comes from DRM while the desktop rectangle
// comes from mutter.
package monitors

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/vr-meta/linux-vr/host/internal/config"
)

// Monitor is one screen, as every part of the system addresses it.
type Monitor struct {
	Index     int    `json:"index"`
	Connector string `json:"connector"`
	// CRTC is -1 when DRM state could not be read. Capture needs it; the rest
	// of the system does not, so a missing value is not fatal.
	CRTC   int `json:"crtc"`
	X      int `json:"x"`
	Y      int `json:"y"`
	Width  int `json:"width"`
	Height int `json:"height"`
	Port   int `json:"port"`
}

// Layout is the whole desk: the screens and the bounding box they form.
type Layout struct {
	Monitors []Monitor `json:"monitors"`
	Width    int       `json:"width"`  // the desktop's total width
	Height   int       `json:"height"` // and height
}

// Find returns the monitor with this index, or nil.
func (l Layout) Find(index int) *Monitor {
	for i := range l.Monitors {
		if l.Monitors[i].Index == index {
			return &l.Monitors[i]
		}
	}
	return nil
}

// Resolve accepts either an index or a connector name, whichever the client
// sends. The input protocol has always allowed both, because a connector name
// is what a human types while debugging and an index is what the client stores.
func (l Layout) Resolve(token string) *Monitor {
	if index, err := strconv.Atoi(token); err == nil {
		return l.Find(index)
	}
	for i := range l.Monitors {
		if strings.EqualFold(l.Monitors[i].Connector, token) {
			return &l.Monitors[i]
		}
	}
	return nil
}

// Detect reads the current layout from mutter and DRM.
func Detect() (Layout, error) {
	rects, width, height, err := gnomeLayout()
	if err != nil {
		return Layout{}, err
	}
	crtcs := drmCRTCs()

	names := make([]string, 0, len(rects))
	for name := range rects {
		names = append(names, name)
	}
	// Sorted by connector name, and this must not change casually: the headset
	// addresses monitors by index, so reordering silently rewires which window
	// shows which screen.
	sort.Strings(names)

	layout := Layout{Width: width, Height: height}
	for index, name := range names {
		r := rects[name]
		crtc, ok := crtcs[name]
		if !ok {
			crtc = -1
		}
		layout.Monitors = append(layout.Monitors, Monitor{
			Index:     index,
			Connector: name,
			CRTC:      crtc,
			X:         r.x, Y: r.y, Width: r.w, Height: r.h,
			Port: config.StreamPort(index),
		})
	}
	if len(layout.Monitors) == 0 {
		return layout, fmt.Errorf("no monitors found")
	}
	return layout, nil
}

type rect struct{ x, y, w, h int }

// normalise turns a DRM connector name into the form mutter uses.
var drmSuffix = regexp.MustCompile(`^([A-Za-z]+)-A-(\d+)$`)

func normalise(connector string) string {
	return drmSuffix.ReplaceAllString(connector, "$1-$2")
}

// gnomeLayout asks mutter where each monitor sits and what mode it is in.
//
// Over busctl rather than a D-Bus library: the call is made once at startup and
// once per layout change, the reply is JSON, and a library for that is a
// dependency bought with nothing.
func gnomeLayout() (map[string]rect, int, int, error) {
	cmd := exec.Command("busctl", "--user", "--json=short", "call",
		"org.gnome.Mutter.DisplayConfig", "/org/gnome/Mutter/DisplayConfig",
		"org.gnome.Mutter.DisplayConfig", "GetCurrentState")
	out, err := runWithTimeout(cmd, 10*time.Second)
	if err != nil {
		return nil, 0, 0, fmt.Errorf("cannot read the monitor layout from mutter: %w", err)
	}

	var reply struct {
		Data []any `json:"data"`
	}
	if err := json.Unmarshal(out, &reply); err != nil {
		return nil, 0, 0, fmt.Errorf("mutter returned something unparseable: %w", err)
	}
	if len(reply.Data) < 3 {
		return nil, 0, 0, fmt.Errorf("mutter returned %d fields, expected at least 3", len(reply.Data))
	}

	// data[1]: physical monitors, each [ [connector,vendor,product,serial],
	// [modes...], props ]. A mode is [id, width, height, refresh, scale,
	// [scales], props], and the current one is marked in its props.
	sizes := map[string][2]int{}
	for _, entry := range list(reply.Data[1]) {
		monitor := list(entry)
		if len(monitor) < 2 {
			continue
		}
		spec := list(monitor[0])
		if len(spec) == 0 {
			continue
		}
		connector := str(spec[0])
		for _, m := range list(monitor[1]) {
			mode := list(m)
			if len(mode) < 7 {
				continue
			}
			props, _ := mode[6].(map[string]any)
			if variantBool(props["is-current"]) {
				sizes[connector] = [2]int{num(mode[1]), num(mode[2])}
				break
			}
		}
	}

	// data[2]: logical monitors, each [x, y, scale, transform, primary,
	// [monitors...], props]. This is where a monitor's position comes from.
	rects := map[string]rect{}
	width, height := 0, 0
	for _, entry := range list(reply.Data[2]) {
		lm := list(entry)
		if len(lm) < 6 {
			continue
		}
		x, y := num(lm[0]), num(lm[1])
		for _, m := range list(lm[5]) {
			spec := list(m)
			if len(spec) == 0 {
				continue
			}
			connector := str(spec[0])
			size, ok := sizes[connector]
			if !ok || size[0] == 0 || size[1] == 0 {
				continue
			}
			rects[normalise(connector)] = rect{x, y, size[0], size[1]}
			width = max(width, x+size[0])
			height = max(height, y+size[1])
		}
	}
	return rects, width, height, nil
}

// drmCRTCs maps a connector to the CRTC object id capture has to name.
//
// Reading debugfs needs root, but the layout above needs the user's session
// bus — and root has none. So the server stays user-side and elevates only for
// this one read.
func drmCRTCs() map[string]int {
	out, err := os.ReadFile("/sys/kernel/debug/dri/0/state")
	if err != nil {
		out = readStateWithSudo()
	}
	if len(out) == 0 {
		return nil
	}

	crtcLine := regexp.MustCompile(`^crtc\[(\d+)\]: (\S+)`)
	connectorLine := regexp.MustCompile(`^connector\[\d+\]: (\S+)`)
	crtcRef := regexp.MustCompile(`^\s+crtc=(\S+)`)

	ids := map[string]int{}    // crtc-N -> numeric object id
	result := map[string]int{} // connector -> object id
	pending := ""

	for _, line := range strings.Split(string(out), "\n") {
		if m := crtcLine.FindStringSubmatch(line); m != nil {
			id, _ := strconv.Atoi(m[1])
			ids[m[2]] = id
			continue
		}
		if m := connectorLine.FindStringSubmatch(line); m != nil {
			pending = m[1]
			continue
		}
		if m := crtcRef.FindStringSubmatch(line); m != nil && pending != "" {
			// Writeback connectors share a CRTC with a real output and would
			// otherwise overwrite it.
			if m[1] != "(null)" && !strings.HasPrefix(pending, "Writeback") {
				result[normalise(pending)] = ids[m[1]]
			}
			pending = ""
		}
	}
	return result
}

func readStateWithSudo() []byte {
	// The glob is the shell's because the path holds a card number that
	// differs per machine, and sudo does not expand it for us.
	cmd := exec.Command("sudo", "-n", "sh", "-c", "cat /sys/kernel/debug/dri/*/state")
	out, err := runWithTimeout(cmd, 10*time.Second)
	if err != nil {
		return nil
	}
	return out
}

// ConnectedOutputs lists the DRM connectors the kernel reports as connected.
// The doctor command uses it to explain a capture that produced nothing.
func ConnectedOutputs() []string {
	var found []string
	paths, _ := filepath.Glob("/sys/class/drm/card*/status")
	for _, path := range paths {
		status, err := os.ReadFile(path)
		if err != nil || strings.TrimSpace(string(status)) != "connected" {
			continue
		}
		found = append(found, filepath.Base(filepath.Dir(path)))
	}
	sort.Strings(found)
	return found
}

// -------------------------------------------------------- reading busctl JSON

// busctl's --json=short encodes a variant as {"type": "...", "data": ...}, and
// everything else as a plain value. These helpers navigate that without
// declaring a type for a shape that is really a tuple.

func list(v any) []any {
	items, _ := v.([]any)
	return items
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

func num(v any) int {
	f, _ := v.(float64)
	return int(f)
}

func variantBool(v any) bool {
	variant, ok := v.(map[string]any)
	if !ok {
		return false
	}
	b, _ := variant["data"].(bool)
	return b
}

func runWithTimeout(cmd *exec.Cmd, limit time.Duration) ([]byte, error) {
	done := make(chan struct{})
	var out []byte
	var err error
	go func() {
		out, err = cmd.Output()
		close(done)
	}()
	select {
	case <-done:
		return out, err
	case <-time.After(limit):
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		return nil, fmt.Errorf("%s timed out", cmd.Path)
	}
}
