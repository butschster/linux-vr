// Package tray puts the server in the desktop's status area, so that a service
// with no window still says whether it is running and who is connected to it.
//
// "Is it running, and is anyone watching" should not need a terminal to answer.
// The icon answers the first by existing and the second by changing; the menu
// answers the rest and opens the settings page.
//
// On GNOME this is a StatusNotifierItem over D-Bus, rendered by the Ubuntu
// AppIndicator extension. Verified on the target machine rather than assumed:
// gnome-shell owns org.kde.StatusNotifierWatcher there and other applications
// already appear in the panel.
//
// The library is pure Go on Linux — cgo only on macOS, which this never builds
// for — so the binary stays static and the arm64 cross-build in CI keeps
// working. That is the reason this is a dependency rather than four hundred
// lines of hand-written SNI and dbusmenu: the protocol is well served by
// something that already exists, and the sibling terminal server made the same
// call.
package tray

import (
	"bytes"
	"fmt"
	"log"
	"os"
	"os/exec"
	"strings"
	"time"

	"fyne.io/systray"
	"github.com/godbus/dbus/v5"
)

// The name a desktop's status area registers. Nothing owning it means nothing
// will render an icon, however correctly it is published.
const watcherName = "org.kde.StatusNotifierWatcher"

// Where this came from. In the menu because a machine running a server someone
// sideloaded six months ago should be able to say where to read about it.
const repoURL = "https://github.com/vr-meta/linux-vr"

// Version is stamped by main, so the menu can say which build is running when
// a headset and a host disagree about the wire.
var Version = "dev"

// State is what the icon has to show. Polled rather than pushed, because the
// things it reports — a window opening, an encoder stopping, a key going
// missing — happen in three packages and none of them should have to know that
// a tray exists.
type State struct {
	Name    string   // what this machine calls itself
	Local   string   // http://localhost:9099
	Address string   // 192.168.0.10:9099, for typing into a headset
	Streams []string // "HDMI-1 → 192.168.0.50", one per window watching
	Problem string   // the first thing that is not working, if anything is
}

func (s State) live() bool { return len(s.Streams) > 0 }

// How many connections the menu lists. Past a handful of lines a menu stops
// being glanceable, and the settings page is one click away.
const maxListed = 6

// Start puts the icon up and keeps it current. It blocks, so run it in a
// goroutine; systray owns the thread it is given.
//
// Failure is never fatal anywhere it is called. A host reached only over ssh
// has no session bus, and refusing to serve a desktop because nothing can draw
// an icon would be absurd.
func Start(status func() State) {
	if err := waitForPanel(2 * time.Minute); err != nil {
		log.Printf("[tray] %v", err)
		return
	}
	systray.Run(func() { ready(status) }, func() {})
}

// waitForPanel blocks until something is listening for tray icons.
//
// This is not paranoia, it is the ordinary case. A user service starts with the
// session, and the GNOME extension that provides the watcher loads after the
// shell is up — so registering immediately fails, once, and the icon never
// appears for the rest of the login. Waiting costs nothing and removes the
// whole class.
func waitForPanel(limit time.Duration) error {
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return fmt.Errorf("no session bus: %w", err)
	}
	defer conn.Close()

	deadline := time.Now().Add(limit)
	announced := false
	for {
		if owns(conn) {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("no status area after %s: nothing owns %s. "+
				"On Ubuntu run `gnome-extensions enable ubuntu-appindicators@ubuntu.com`",
				limit, watcherName)
		}
		if !announced {
			log.Printf("[tray] waiting for the desktop's status area")
			announced = true
		}
		time.Sleep(2 * time.Second)
	}
}

// PanelPresent reports whether an icon would be rendered right now. Used by
// doctor, which is where a missing status area should be found rather than by
// noticing an icon that never turned up.
func PanelPresent() bool {
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return false
	}
	defer conn.Close()
	return owns(conn)
}

func owns(conn *dbus.Conn) bool {
	var owned bool
	err := conn.BusObject().Call("org.freedesktop.DBus.NameHasOwner", 0, watcherName).Store(&owned)
	return err == nil && owned
}

// Available reports whether there is a desktop to put an icon on. Without this
// the library waits on a bus that will never answer, and the wait is silent.
func Available() bool {
	return os.Getenv("DBUS_SESSION_BUS_ADDRESS") != "" &&
		(os.Getenv("WAYLAND_DISPLAY") != "" || os.Getenv("DISPLAY") != "")
}

func ready(status func() State) {
	state := status()

	systray.SetIcon(icon(state.live()))
	systray.SetTitle("")
	systray.SetTooltip(tooltip(state))

	settings := systray.AddMenuItem("Settings and status…", "open the server's page in a browser")
	systray.AddSeparator()

	// The lines that report rather than offer. Allocated once and hidden when
	// unused: systray has no way to remove an item, so a menu that is rebuilt
	// on every change grows without bound.
	heading := systray.AddMenuItem("", "")
	heading.Disable()
	lines := make([]*systray.MenuItem, maxListed)
	for i := range lines {
		lines[i] = systray.AddMenuItem("", "")
		lines[i].Disable()
		lines[i].Hide()
	}
	problem := systray.AddMenuItem("", "")
	problem.Disable()
	problem.Hide()

	systray.AddSeparator()
	copyAddress := systray.AddMenuItem("Copy the address",
		"put it in the clipboard for typing into a headset")
	project := systray.AddMenuItem("linux-vr "+Version+" on GitHub", repoURL)

	go func() {
		for {
			select {
			case <-settings.ClickedCh:
				open(status().Local)
			case <-copyAddress.ClickedCh:
				clipboard(status().Address)
			case <-project.ClickedCh:
				open(repoURL)
			}
		}
	}()

	go follow(status, state, heading, lines, problem)
}

// follow repaints the icon and the menu when the state changes.
//
// A second is the right period: this is a status light, not an instrument, and
// nothing it reports changes faster than a window opening.
func follow(status func() State, previous State, heading *systray.MenuItem,
	lines []*systray.MenuItem, problem *systray.MenuItem) {

	paint(previous, heading, lines, problem)
	for range time.Tick(time.Second) {
		current := status()
		if same(previous, current) {
			continue
		}
		if previous.live() != current.live() {
			systray.SetIcon(icon(current.live()))
		}
		systray.SetTooltip(tooltip(current))
		paint(current, heading, lines, problem)
		previous = current
	}
}

func paint(s State, heading *systray.MenuItem, lines []*systray.MenuItem, problem *systray.MenuItem) {
	heading.SetTitle(s.Name + "   " + s.Address)

	shown := s.Streams
	if len(shown) > maxListed {
		// Saying so, rather than silently truncating: a menu that quietly drops
		// connections is worse than one that admits it cannot list them all.
		shown = append(append([]string{}, shown[:maxListed-1]...),
			"…and more, see the settings page")
	}
	if len(shown) == 0 {
		shown = []string{"nothing is watching"}
	}
	for i, item := range lines {
		if i < len(shown) {
			item.SetTitle("   " + shown[i])
			item.Show()
			continue
		}
		item.Hide()
	}

	if s.Problem == "" {
		problem.Hide()
		return
	}
	problem.SetTitle("   " + s.Problem)
	problem.Show()
}

func tooltip(s State) string {
	parts := []string{s.Name + "  " + s.Address}
	if s.live() {
		parts = append(parts, s.Streams...)
	} else {
		parts = append(parts, "nothing is watching")
	}
	if s.Problem != "" {
		parts = append(parts, s.Problem)
	}
	return strings.Join(parts, "\n")
}

func same(a, b State) bool {
	if a.Name != b.Name || a.Address != b.Address || a.Problem != b.Problem {
		return false
	}
	if len(a.Streams) != len(b.Streams) {
		return false
	}
	for i := range a.Streams {
		if a.Streams[i] != b.Streams[i] {
			return false
		}
	}
	return true
}

func open(url string) {
	// xdg-open rather than a browser by name: which browser is the user's
	// business, and the desktop already knows the answer.
	if err := exec.Command("xdg-open", url).Start(); err != nil {
		log.Printf("[tray] cannot open %s: %v", url, err)
	}
}

func clipboard(text string) {
	cmd := exec.Command("wl-copy")
	cmd.Stdin = bytes.NewReader([]byte(text))
	if err := cmd.Run(); err != nil {
		log.Printf("[tray] cannot copy the address: %v", err)
	}
}
