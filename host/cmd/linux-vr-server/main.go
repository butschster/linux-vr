// Command linux-vr-server presents this machine's desktop to a VR headset.
//
// It captures each monitor through KMS, encodes it with VAAPI and serves it;
// it injects the pointer the headset's ray drives; it turns dictation into text
// at the cursor; and it answers discovery probes so a headset on the same
// network finds it without anyone typing an address.
//
// This replaces a set of Python scripts and a shell script started by hand. The
// reason is installation, not speed: a server is something you put on a machine
// and forget, and one static binary with a systemd unit is a different
// proposition from four scripts plus an interpreter plus whatever the
// distribution decided python3 means this year.
//
// Shells are not served here. A terminal in the headset is a different product
// with a different wire — see the linux-terminal repository.
package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/vr-meta/linux-vr/host/internal/capture"
	"github.com/vr-meta/linux-vr/host/internal/config"
	"github.com/vr-meta/linux-vr/host/internal/control"
	"github.com/vr-meta/linux-vr/host/internal/input"
	"github.com/vr-meta/linux-vr/host/internal/monitors"
	"github.com/vr-meta/linux-vr/host/internal/tray"
	"github.com/vr-meta/linux-vr/host/internal/voice"
)

// version is stamped in at build time; see the Makefile.
var version = "dev"

func main() {
	var (
		flagConfig = flag.String("config", "", "configuration file (default: ~/.config/linuxvr/config.json)")
		flagBind   = flag.String("bind", "", "address to listen on, overriding the configuration")
		flagName   = flag.String("name", "", "name shown in the headset, overriding the configuration")
	)
	flag.Usage = usage
	flag.Parse()

	log.SetFlags(log.Ltime)

	command := flag.Arg(0)
	if command == "" {
		command = "serve"
	}

	configPath := *flagConfig
	if configPath == "" {
		configPath = config.Path()
	}
	cfg, err := config.Load(configPath)
	if err != nil {
		log.Fatalf("configuration: %v", err)
	}
	if *flagBind != "" {
		cfg.Bind = *flagBind
	}
	if *flagName != "" {
		cfg.Name = *flagName
	}

	switch command {
	case "serve":
		serve(cfg, configPath)
	case "monitors":
		printMonitors()
	case "doctor":
		doctor(cfg)
	case "version":
		fmt.Printf("linux-vr-server %s\n", version)
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", command)
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintf(os.Stderr, `linux-vr-server %s — the desktop half of a VR desktop.

    linux-vr-server              serve; the headset finds this machine by itself
    linux-vr-server monitors     print the monitor table every part of this agrees on
    linux-vr-server doctor       check what is missing before blaming the headset
    linux-vr-server version

Options:
`, version)
	flag.PrintDefaults()
	fmt.Fprintf(os.Stderr, "\nConfiguration: %s\n", config.Path())
}

// ---------------------------------------------------------------------- serve

func serve(cfg config.Config, configPath string) {
	layout, err := detect()
	if err != nil {
		log.Fatalf("%v\n\nRun `linux-vr-server doctor` to see what is missing.", err)
	}

	log.Printf("%s — desktop %dx%d, %d monitor(s)",
		cfg.Name, layout.Width, layout.Height, len(layout.Monitors))
	for _, m := range layout.Monitors {
		log.Printf("    [%d] %-10s %dx%d at +%d+%d  port %d",
			m.Index, m.Connector, m.Width, m.Height, m.X, m.Y, m.Port)
	}

	services := map[string]control.Service{}

	// Input and voice both need /dev/uinput. Losing them is not a reason to
	// refuse to start: a desktop you can look at but not click is still worth
	// more than an error message, and the client is told which half is missing.
	var pointer *input.Pointer
	if p, err := input.NewPointer(layout.Width, layout.Height); err != nil {
		log.Printf("[input] unavailable: %v", err)
		services["input"] = control.Service{Available: false, Detail: firstLine(err.Error())}
	} else {
		pointer = p
		defer pointer.Close()
		services["input"] = control.Service{Port: config.InputPort, Available: true}
		go listenAndServe("input", cfg.Bind, config.InputPort, func(l net.Listener) error {
			return input.New(pointer, layout).Serve(l)
		})
	}

	speech, err := voice.New(cfg.ASR)
	if err != nil {
		log.Printf("[voice] unavailable: %v", err)
		services["voice"] = control.Service{Available: false, Detail: firstLine(err.Error())}
		speech = nil
	} else {
		defer speech.Close()
		if available, detail := speech.Available(); !available {
			// Typing and key presses still work; only recognition does not, and
			// it can be configured from the settings page without a restart.
			log.Printf("[voice] %s — dictation will fail, typing will not", detail)
		}
		go listenAndServe("voice", cfg.Bind, config.VoicePort, speech.Serve)
	}

	frames := capture.New(cfg.Capture, cfg.Bind, layout)
	if available, detail := frames.Available(); available {
		services["capture"] = control.Service{Available: true}
		go func() {
			if err := frames.Serve(); err != nil {
				log.Printf("[capture] stopped: %v", err)
			}
		}()
	} else {
		log.Printf("[capture] unavailable: %s", detail)
		services["capture"] = control.Service{Available: false, Detail: detail}
	}

	// Settings are shared with the control server so the page can change them.
	// A mutex rather than anything cleverer: this is written when someone
	// presses Save and read a few times a second.
	live := &settings{cfg: cfg, path: configPath, capture: frames, voice: speech}

	control.Version = version
	api := &control.Server{
		Name: cfg.Name, Bind: cfg.Bind, Port: config.ControlPort, Layout: layout,
		Status:            func() map[string]control.Service { return live.status(services) },
		Streaming:         frames.Streaming,
		Settings:          live.get,
		Apply:             live.apply,
		AllowRemoteConfig: cfg.AllowRemoteConfig,
	}
	if speech != nil {
		api.TestASR = speech.Test
	}
	go api.ServeDiscovery()

	// The icon is the only thing this server has that says "I am running" to
	// someone who is not wearing the headset. Never fatal: a host reached only
	// over ssh has no session bus, and refusing to serve a desktop because
	// nothing can draw an icon would be absurd.
	if cfg.Tray && tray.Available() {
		go tray.Start(live.trayState)
	} else if cfg.Tray {
		log.Printf("[tray] no desktop session here, running without an icon")
	}

	address := net.JoinHostPort(cfg.Bind, strconv.Itoa(config.ControlPort))
	listener, err := net.Listen("tcp", address)
	if err != nil {
		log.Fatalf("cannot listen on %s: %v", address, err)
	}
	log.Printf("[control] settings and status: http://localhost:%d", config.ControlPort)
	log.Printf("[control] what a client reads:  http://%s/v1/info", address)
	log.Fatal(api.Serve(listener))
}

// ------------------------------------------------------------------- settings

// settings is the configuration while the server is running, and the one place
// that knows how to put a change into effect.
//
// Some of it applies immediately — a transcription endpoint is read per
// utterance — and some of it applies to the next thing that starts, because an
// encoder's flags are fixed when it is launched. Saying which is which is the
// job of this type, and of the sentence the page shows next to each field.
type settings struct {
	mu      sync.RWMutex
	cfg     config.Config
	path    string
	capture *capture.Service
	voice   *voice.Service
}

// trayState is what the status icon shows: who is watching, and the address to
// type into a headset that did not find this machine by itself.
func (s *settings) trayState() tray.State {
	cfg := s.get()
	state := tray.State{
		Name:    cfg.Name,
		Local:   fmt.Sprintf("http://localhost:%d", config.ControlPort),
		Address: fmt.Sprintf("%s:%d", localAddress(), config.ControlPort),
	}
	for _, c := range s.capture.Connections() {
		where := c.Connector
		if where == "" {
			where = fmt.Sprintf("screen %d", c.Monitor)
		}
		state.Streams = append(state.Streams, fmt.Sprintf("%s → %s", where, c.Client))
	}
	// One problem, not a list: the icon has a tooltip, not a report, and the
	// first thing that is broken is the one worth fixing.
	if available, detail := s.capture.Available(); !available {
		state.Problem = "no video: " + detail
	} else if s.voice != nil {
		if available, detail := s.voice.Available(); !available {
			state.Problem = "no dictation: " + detail
		}
	}
	return state
}

// localAddress is the address a headset on the same network would use.
//
// Found by asking the routing table which source address a packet to the
// outside would take, rather than by listing interfaces: on a machine with
// docker, wireguard and two physical NICs, the list is long and the answer is
// not the first entry.
func localAddress() string {
	conn, err := net.Dial("udp", "192.0.2.1:9")
	if err != nil {
		return "this machine"
	}
	defer conn.Close()
	host, _, err := net.SplitHostPort(conn.LocalAddr().String())
	if err != nil {
		return "this machine"
	}
	return host
}

func (s *settings) get() config.Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cfg
}

func (s *settings) apply(cfg config.Config) error {
	// Written first: a setting that took effect but was not saved is worse than
	// one that did neither, because it comes back on the next restart and
	// nobody remembers why.
	if err := config.Save(s.path, cfg); err != nil {
		return fmt.Errorf("cannot write %s: %w", s.path, err)
	}

	s.mu.Lock()
	s.cfg = cfg
	s.mu.Unlock()

	if s.voice != nil {
		s.voice.SetASR(cfg.ASR)
	}
	s.capture.SetConfig(cfg.Capture)
	log.Printf("[settings] saved to %s", s.path)
	return nil
}

// status fills in what each service reports right now. Capture and voice are
// asked rather than remembered, because both can be fixed from the page while
// the server runs and a cached "not working" would outlive the fix.
func (s *settings) status(base map[string]control.Service) map[string]control.Service {
	current := make(map[string]control.Service, len(base))
	for name, service := range base {
		current[name] = service
	}
	available, detail := s.capture.Available()
	current["capture"] = control.Service{Available: available, Detail: detail}
	if s.voice != nil {
		available, detail := s.voice.Available()
		current["voice"] = control.Service{
			Port: config.VoicePort, Available: available, Detail: detail}
	}
	return current
}

// detect reads the monitor layout, waiting for the desktop if it has to.
//
// A user service starts when the session does, which can be before gnome-shell
// is answering on the bus — and mutter also goes briefly unresponsive under
// load, which was observed on this machine. Either way the layout arrives a few
// seconds later, and exiting instead of waiting turns a hiccup into a service
// that has to be restarted by hand.
func detect() (monitors.Layout, error) {
	const attempts = 6
	var err error
	for attempt := 1; attempt <= attempts; attempt++ {
		var layout monitors.Layout
		if layout, err = monitors.Detect(); err == nil {
			return layout, nil
		}
		if attempt < attempts {
			log.Printf("[monitors] %v — retrying (%d/%d)", err, attempt, attempts)
			time.Sleep(5 * time.Second)
		}
	}
	return monitors.Layout{}, err
}

func listenAndServe(name, bind string, port int, serve func(net.Listener) error) {
	address := net.JoinHostPort(bind, strconv.Itoa(port))
	listener, err := net.Listen("tcp", address)
	if err != nil {
		log.Printf("[%s] cannot listen on %s: %v", name, address, err)
		return
	}
	log.Printf("[%s] on %s", name, address)
	if err := serve(listener); err != nil {
		log.Printf("[%s] stopped: %v", name, err)
	}
}

// ------------------------------------------------------------------- monitors

func printMonitors() {
	layout, err := monitors.Detect()
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("desktop %dx%d\n", layout.Width, layout.Height)
	for _, m := range layout.Monitors {
		crtc := strconv.Itoa(m.CRTC)
		if m.CRTC < 0 {
			crtc = "? (needs root, see doctor)"
		}
		fmt.Printf("  [%d] %-10s %dx%d at +%d+%d  crtc=%s  port=%d\n",
			m.Index, m.Connector, m.Width, m.Height, m.X, m.Y, crtc, m.Port)
	}
}

// --------------------------------------------------------------------- doctor

// doctor checks the things that are wrong far more often than the code is.
// Every entry here is a failure that has actually happened and cost time.
func doctor(cfg config.Config) {
	ok := true
	check := func(condition bool, good, bad string) {
		if condition {
			fmt.Printf("  ok    %s\n", good)
			return
		}
		ok = false
		fmt.Printf("  FAIL  %s\n", bad)
	}

	fmt.Printf("linux-vr-server %s\n\n", version)
	fmt.Printf("configuration: %s\n", config.Path())
	fmt.Printf("name: %s   bind: %s\n\n", cfg.Name, cfg.Bind)

	fmt.Println("tools")
	check(config.HasCommand("ffmpeg"), "ffmpeg", "ffmpeg is missing — no video without it")
	check(config.HasCommand("busctl"),
		"busctl", "busctl is missing — the monitor layout comes from mutter over D-Bus")
	check(config.HasCommand("wl-copy") && config.HasCommand("wl-paste"),
		"wl-clipboard", "wl-clipboard is missing — dictation inserts text through the clipboard")

	fmt.Println("\ndevices")
	_, uinputErr := os.OpenFile("/dev/uinput", os.O_WRONLY, 0)
	check(uinputErr == nil, "/dev/uinput is writable",
		"cannot write /dev/uinput — run: sudo usermod -aG input $USER, then log out and back in")
	_, deviceErr := os.Stat(cfg.Capture.Device)
	check(deviceErr == nil, cfg.Capture.Device+" exists",
		cfg.Capture.Device+" does not exist — set capture.device in the configuration")
	if outputs := monitors.ConnectedOutputs(); len(outputs) > 0 {
		fmt.Printf("  ok    connected outputs: %s\n", strings.Join(outputs, ", "))
	} else {
		fmt.Println("  ok    connected outputs: none reported")
	}

	fmt.Println("\nprivileges")
	// kmsgrab needs CAP_SYS_ADMIN. Without a password-less rule the encoder
	// starts and dies immediately, which looks exactly like a broken stream.
	sudoOK := exec.Command("sudo", "-n", "true").Run() == nil
	check(!cfg.Capture.Sudo || sudoOK, "sudo works without a password",
		"sudo asks for a password — capture cannot start unattended; see docs/server.md")

	fmt.Println("\nlayout")
	layout, err := monitors.Detect()
	if err != nil {
		ok = false
		fmt.Printf("  FAIL  %v\n", err)
	} else {
		fmt.Printf("  ok    desktop %dx%d, %d monitor(s)\n",
			layout.Width, layout.Height, len(layout.Monitors))
		for _, m := range layout.Monitors {
			if m.CRTC < 0 {
				ok = false
				fmt.Printf("  FAIL  [%d] %s has no CRTC id — DRM state needs root\n",
					m.Index, m.Connector)
			} else {
				fmt.Printf("  ok    [%d] %s crtc=%d port=%d\n",
					m.Index, m.Connector, m.CRTC, m.Port)
			}
		}
	}

	fmt.Println("\nstatus icon")
	switch {
	case !cfg.Tray:
		fmt.Println("  --    turned off in the configuration")
	case !tray.Available():
		fmt.Println("  --    no desktop session here; the server runs fine without an icon")
	case tray.PanelPresent():
		fmt.Println("  ok    the desktop has somewhere to put it")
	default:
		// Found the hard way: with the extension disabled the icon registers
		// on the bus and is rendered by nothing, which looks identical to a
		// server that failed to start.
		fmt.Println("  FAIL  nothing renders tray icons on this desktop")
		fmt.Println("        gnome-extensions enable ubuntu-appindicators@ubuntu.com")
		ok = false
	}

	fmt.Println("\ndictation")
	if endpoint := cfg.ASR.Endpoint(); endpoint == "" {
		fmt.Println("  --    no transcription endpoint; typing works, speech does not")
		fmt.Printf("        set one at http://localhost:%d\n", config.ControlPort)
	} else {
		fmt.Printf("  ok    %s (%s)\n", endpoint, cfg.ASR.ModelName())
		if cfg.ASR.APIKey() == "" {
			fmt.Println("  --    no API key; fine if the endpoint is open")
		}
	}

	fmt.Println()
	if ok {
		fmt.Println("Everything needed is in place.")
		return
	}
	fmt.Println("Something above will stop this working. Fix it before blaming the headset.")
	os.Exit(1)
}

func firstLine(text string) string {
	line, _, _ := strings.Cut(text, "\n")
	return line
}
