// Package control is how a headset finds this machine and learns what it
// offers, before any window opens.
//
// Two protocols on one number: a UDP probe answered with a datagram, and an
// HTTP endpoint with the full picture. The datagram has to fit in one packet
// and is only ever "here I am"; everything the client needs to open a window —
// which monitors exist, how big they are, which port each is served on — comes
// from the HTTP call it makes to the address that answered.
package control

import (
	_ "embed"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"os/user"
	"strings"
	"time"

	"github.com/vr-meta/linux-vr/host/internal/config"
	"github.com/vr-meta/linux-vr/host/internal/monitors"
)

// The settings page, compiled in. One file, no external requests: a page that
// needs a CDN cannot be opened on the network it is meant to configure.
//
//go:embed ui.html
var uiPage []byte

// Version is stamped by main so the page can show it.
var Version = "dev"

// Discovery is a UDP broadcast rather than mDNS, on purpose. mDNS would mean
// avahi on the host and NsdManager in the client — two moving parts and two
// failure modes for a question with one line in it. This works on a machine
// with nothing installed, and a server outside the network is reached by typing
// its address, which is what that case needs anyway.
const (
	probePrefix = "LINUXVR-DISCOVER"
	protoTag    = "linux-vr"
	// The terminal is a separate product answering the same probe on its own
	// port. Saying which service this is keeps a client from opening the wrong
	// kind of window against the right machine.
	serviceTag = "desktop"
	protoVer   = 1
)

// Announcement is what a server says about itself in a datagram. Deliberately
// small: everything here has to fit in one packet.
type Announcement struct {
	Proto    string `json:"proto"`
	Service  string `json:"service"`
	Version  int    `json:"version"`
	Name     string `json:"name"`
	Port     int    `json:"port"`
	User     string `json:"user"`
	OS       string `json:"os"`
	Monitors int    `json:"monitors"`
}

// Info is the full answer, over HTTP.
type Info struct {
	Announcement
	Desktop  Size               `json:"desktop"`
	Screens  []Screen           `json:"screens"`
	Services map[string]Service `json:"services"`
}

type Size struct {
	Width  int `json:"width"`
	Height int `json:"height"`
}

// Screen is a monitor as the client needs it: what to call it, how big it is,
// and where to get its picture.
type Screen struct {
	monitors.Monitor
	Streaming bool `json:"streaming"`
}

// Service says whether a part of the server can actually do its job, and why
// not when it cannot. The client shows the reason rather than opening a window
// that never works.
type Service struct {
	Port      int    `json:"port,omitempty"`
	Available bool   `json:"available"`
	Detail    string `json:"detail,omitempty"`
}

// Server answers both protocols, and serves the settings page.
type Server struct {
	Name   string
	Bind   string
	Port   int
	Layout monitors.Layout

	// Status is asked for on every request rather than stored, so a service
	// that died is reported as dead.
	Status func() map[string]Service
	// Streaming reports which monitors are being encoded right now.
	Streaming func() map[int]bool

	// Settings is the live configuration, and Apply changes it. Both are
	// supplied by main so that this package does not own the file.
	Settings func() config.Config
	Apply    func(config.Config) error
	// TestASR posts a short sound to the configured endpoint and reports how it
	// went, so a key can be checked without putting the headset on.
	TestASR func() (time.Duration, error)
	// AllowRemoteConfig opens the settings endpoints to the whole network.
	// Off by default — see the note on requireLocal.
	AllowRemoteConfig bool
}

// Serve handles the control API and the settings page.
func (s *Server) Serve(listener net.Listener) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/info", s.info)
	mux.HandleFunc("/v1/config", s.config)
	mux.HandleFunc("/v1/asr/test", s.testASR)
	// A bare / so that typing the address into a browser gives you the settings
	// rather than a 404 — the first thing anyone does when a server "does not
	// work" is open it in a browser.
	mux.HandleFunc("/", s.page)

	server := &http.Server{
		Handler:     woven(mux),
		ReadTimeout: 10 * time.Second,
		// Transcription round-trips through the gateway on /v1/asr/test, and a
		// cold model can take a while to answer.
		WriteTimeout: 90 * time.Second,
	}
	return server.Serve(listener)
}

func (s *Server) info(w http.ResponseWriter, r *http.Request) {
	streaming := map[int]bool{}
	if s.Streaming != nil {
		streaming = s.Streaming()
	}
	screens := make([]Screen, 0, len(s.Layout.Monitors))
	for _, m := range s.Layout.Monitors {
		screens = append(screens, Screen{Monitor: m, Streaming: streaming[m.Index]})
	}

	services := map[string]Service{}
	if s.Status != nil {
		services = s.Status()
	}

	w.Header().Set("Content-Type", "application/json")
	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(Info{
		Announcement: s.announcement(),
		Desktop:      Size{s.Layout.Width, s.Layout.Height},
		Screens:      screens,
		Services:     services,
	})
}

// page serves the settings UI. It is one embedded file with no external
// requests in it: a server whose page needs the internet to render is a server
// that cannot be configured on the network it is meant to work on.
func (s *Server) page(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write(uiPage)
}

// ---------------------------------------------------------------- the settings

// settingsBody is the configuration as the page sees it.
//
// The key is never in it. Reading it back would mean any browser on the network
// could collect the key of every machine running this, and there is no reason
// the page needs it: it only has to know whether one is set.
type settingsBody struct {
	Name    string         `json:"name"`
	Bind    string         `json:"bind"`
	Capture config.Capture `json:"capture"`
	ASR     asrBody        `json:"asr"`

	// Everything below is read-only context for the page.
	Effective  effectiveASR `json:"effective"`
	ConfigPath string       `json:"configPath"`
	Editable   bool         `json:"editable"`
	Version    string       `json:"version"`
}

type asrBody struct {
	Provider string `json:"provider"`
	URL      string `json:"url"`
	Model    string `json:"model"`
	// KeySet says a key exists without saying what it is.
	KeySet bool `json:"keySet"`
}

// effectiveASR is what will actually be used, after the presets and the
// environment have had their say. Without this the page can show a URL the
// server is not using, because an exported variable quietly wins over the file.
type effectiveASR struct {
	Endpoint string   `json:"endpoint"`
	Model    string   `json:"model"`
	FromEnv  []string `json:"fromEnv"`
}

// patch is the incoming shape. Key is a pointer so that three cases stay
// distinguishable: absent means leave it alone, "" means clear it, and a value
// means set it. A plain string would make "do not touch" and "delete" the same
// request, and the page would erase the key every time you renamed the machine.
type patch struct {
	Name    *string         `json:"name"`
	Capture *config.Capture `json:"capture"`
	ASR     *struct {
		Provider *string `json:"provider"`
		URL      *string `json:"url"`
		Model    *string `json:"model"`
		Key      *string `json:"key"`
	} `json:"asr"`
}

func (s *Server) config(w http.ResponseWriter, r *http.Request) {
	if s.Settings == nil {
		http.Error(w, "not configurable", http.StatusNotImplemented)
		return
	}
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, s.settingsBody(r))
	case http.MethodPut, http.MethodPost:
		if !s.requireLocal(w, r) {
			return
		}
		s.update(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) settingsBody(r *http.Request) settingsBody {
	cfg := s.Settings()
	return settingsBody{
		Name:    cfg.Name,
		Bind:    cfg.Bind,
		Capture: cfg.Capture,
		ASR: asrBody{
			Provider: cfg.ASR.Provider,
			URL:      cfg.ASR.URL,
			Model:    cfg.ASR.Model,
			KeySet:   cfg.ASR.APIKey() != "",
		},
		Effective: effectiveASR{
			Endpoint: cfg.ASR.Endpoint(),
			Model:    cfg.ASR.ModelName(),
			FromEnv:  overriddenByEnvironment(),
		},
		ConfigPath: config.Path(),
		Editable:   s.AllowRemoteConfig || isLocal(r),
		Version:    Version,
	}
}

func (s *Server) update(w http.ResponseWriter, r *http.Request) {
	var incoming patch
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&incoming); err != nil {
		http.Error(w, "cannot read the settings: "+err.Error(), http.StatusBadRequest)
		return
	}

	cfg := s.Settings()
	if incoming.Name != nil && strings.TrimSpace(*incoming.Name) != "" {
		cfg.Name = strings.TrimSpace(*incoming.Name)
	}
	if incoming.Capture != nil {
		// Only the fields worth changing from a browser. The DRM device is
		// deliberately not one of them: getting it wrong captures nothing, and
		// the doctor command is where that conversation belongs.
		if incoming.Capture.FPS > 0 {
			cfg.Capture.FPS = clamp(incoming.Capture.FPS, 5, 240)
		}
		if incoming.Capture.QP > 0 {
			cfg.Capture.QP = clamp(incoming.Capture.QP, 10, 51)
		}
	}
	if incoming.ASR != nil {
		if incoming.ASR.Provider != nil {
			switch *incoming.ASR.Provider {
			case config.ProviderOpenAI, config.ProviderCustom:
				cfg.ASR.Provider = *incoming.ASR.Provider
			default:
				http.Error(w, "unknown provider", http.StatusBadRequest)
				return
			}
		}
		if incoming.ASR.URL != nil {
			cfg.ASR.URL = strings.TrimSpace(*incoming.ASR.URL)
		}
		if incoming.ASR.Model != nil {
			cfg.ASR.Model = strings.TrimSpace(*incoming.ASR.Model)
		}
		if incoming.ASR.Key != nil {
			cfg.ASR.Key = strings.TrimSpace(*incoming.ASR.Key)
		}
	}

	if err := s.Apply(cfg); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	s.Name = cfg.Name
	log.Printf("[control] settings updated from %s", r.RemoteAddr)
	writeJSON(w, s.settingsBody(r))
}

func (s *Server) testASR(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.requireLocal(w, r) {
		return
	}
	if s.TestASR == nil {
		http.Error(w, "dictation is not running", http.StatusNotImplemented)
		return
	}
	took, err := s.TestASR()
	result := map[string]any{"ok": err == nil, "ms": took.Milliseconds()}
	if err != nil {
		result["error"] = err.Error()
	}
	writeJSON(w, result)
}

// requireLocal keeps the settings to the machine itself unless told otherwise.
//
// The rest of this server is open on the network by design — that is what a
// headset needs. Settings are different: they hold an API key and they change
// what the machine does, and there is no authentication anywhere in this
// project. Loopback is the one boundary available without inventing one.
func (s *Server) requireLocal(w http.ResponseWriter, r *http.Request) bool {
	if s.AllowRemoteConfig || isLocal(r) {
		return true
	}
	http.Error(w,
		"settings can only be changed from the machine itself.\n"+
			"Open http://localhost:9099 there, or set \"allowRemoteConfig\": true "+
			"in the configuration if you accept that anyone on this network can "+
			"change them.", http.StatusForbidden)
	return false
}

func isLocal(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// overriddenByEnvironment lists the settings the environment is winning, so the
// page can say why editing a field changed nothing.
func overriddenByEnvironment() []string {
	var found []string
	for _, name := range []string{"LINUXVR_ASR_URL", "LINUXVR_ASR_KEY", "LINUXVR_ASR_MODEL"} {
		if os.Getenv(name) != "" {
			found = append(found, name)
		}
	}
	return found
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(value)
}

func clamp(v, low, high int) int {
	if v < low {
		return low
	}
	if v > high {
		return high
	}
	return v
}

func (s *Server) announcement() Announcement {
	return Announcement{
		Proto: protoTag, Service: serviceTag, Version: protoVer,
		Name: s.Name, Port: s.Port,
		User: currentUser(), OS: osRelease(),
		Monitors: len(s.Layout.Monitors),
	}
}

// ServeDiscovery answers probes until the socket fails.
//
// Not fatal when it cannot start: a server that cannot be discovered can still
// be typed in, and refusing to run at all would turn a nuisance into an outage.
func (s *Server) ServeDiscovery() {
	address := &net.UDPAddr{IP: net.ParseIP(s.Bind), Port: s.Port}
	conn, err := net.ListenUDP("udp4", address)
	if err != nil {
		log.Printf("[control] discovery unavailable on udp/%d: %v", s.Port, err)
		return
	}
	defer conn.Close()

	reply, err := json.Marshal(s.announcement())
	if err != nil {
		return
	}
	log.Printf("[control] answering discovery probes on udp/%d", s.Port)

	buffer := make([]byte, 512)
	for {
		n, from, err := conn.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		if !strings.HasPrefix(string(buffer[:n]), probePrefix) {
			continue
		}
		if _, err := conn.WriteToUDP(reply, from); err != nil {
			log.Printf("[control] cannot answer %s: %v", from, err)
		}
	}
}

// woven logs requests. There are few of them and each one is a headset trying
// to connect, which is precisely what you want in the log when it does not.
func woven(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("[control] %s %s from %s", r.Method, r.URL.Path, r.RemoteAddr)
		next.ServeHTTP(w, r)
	})
}

func currentUser() string {
	if u, err := user.Current(); err == nil {
		return u.Username
	}
	return os.Getenv("USER")
}

// osRelease reads PRETTY_NAME so the headset can tell two machines apart by
// more than their hostname.
func osRelease() string {
	data, err := os.ReadFile("/etc/os-release")
	if err != nil {
		return "linux"
	}
	for _, line := range strings.Split(string(data), "\n") {
		if value, found := strings.CutPrefix(line, "PRETTY_NAME="); found {
			return strings.Trim(value, `"`)
		}
	}
	return "linux"
}

// LookupPort is the port both protocols use, exported so main can log it
// without importing config twice.
const LookupPort = config.ControlPort
