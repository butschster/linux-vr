// Package config holds everything the server needs to know that is not
// discovered at runtime, plus the port numbers every service agrees on.
//
// The file is written on first run rather than documented and left to the user
// to create: a server you have to configure before it starts is a server you
// cannot hand to yourself six months later.
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// Ports. Streams are spaced ten apart so the fixed services fit in the gap
// after the first one without renumbering anything when a monitor is added.
//
// The client, the capture supervisor and the input service all address monitors
// by index and derive the port the same way. A disagreement here is invisible
// until someone clicks on the wrong screen.
const (
	ControlPort    = 9099 // TCP: the control API and the UI. UDP: discovery.
	StreamPortBase = 9100
	StreamPortStep = 10
	InputPort      = 9101
	VoicePort      = 9102
	// 9103 is deliberately not used here: it belongs to the terminal server,
	// which is a separate product that may be running on the same machine.
)

// StreamPort is the port a monitor's H.264 stream is served on.
func StreamPort(index int) int { return StreamPortBase + index*StreamPortStep }

// Config is the on-disk configuration. Every field has a working default, so an
// empty file and a missing file behave the same.
type Config struct {
	// Name is what the headset shows in its server list. A hostname is a poor
	// label when two machines are both "ubuntu", so it is settable.
	Name string `json:"name"`
	// Bind is the address the services listen on. 0.0.0.0 is the useful
	// default and also the honest one — see the note in docs/server.md about
	// what an open input port means on a shared network.
	Bind string `json:"bind"`

	// AllowRemoteConfig opens the settings page and its endpoints to the whole
	// network. Off by default: the settings hold an API key and change what the
	// machine does, and there is no authentication anywhere in this project, so
	// loopback is the only boundary available without inventing one.
	AllowRemoteConfig bool `json:"allowRemoteConfig"`

	// Tray puts an icon in the desktop's status area. On by default, because a
	// service with no window is otherwise invisible until something goes wrong.
	Tray bool `json:"tray"`

	Capture Capture `json:"capture"`
	ASR     ASR     `json:"asr"`
}

// Capture holds the encoder settings. The reasoning behind the values is in
// docs/host-baseline.md and in the comments of the capture package; they are
// measured on this hardware, not copied from a guide.
type Capture struct {
	Device string `json:"device"` // DRM node kmsgrab reads
	FPS    int    `json:"fps"`    // must match the display, not the headset
	QP     int    `json:"qp"`     // constant quality; a desktop is mostly static
	Sudo   bool   `json:"sudo"`   // kmsgrab needs CAP_SYS_ADMIN
}

// Transcription providers. Both speak the same wire — OpenAI's audio
// transcription API — which is why self-hosted gateways are worth supporting at
// all: there is nothing to write twice.
const (
	ProviderOpenAI = "openai"
	ProviderCustom = "custom"

	OpenAIEndpoint = "https://api.openai.com/v1/audio/transcriptions"
)

// ASR points at a transcription endpoint: OpenAI's, or one of your own.
//
// The server proxies to it rather than the headset calling it directly, for two
// reasons. The key stays on one machine instead of being shipped to every
// headset that connects. And the host is where the text has to end up anyway —
// it is the side holding the clipboard and the cursor.
type ASR struct {
	// Provider selects a preset. With "openai" the URL is filled in and only a
	// key is needed; with "custom" the URL is yours.
	Provider string `json:"provider"`
	URL      string `json:"url"`
	Key      string `json:"key"`
	Model    string `json:"model"`
	// Language as an ISO code, or empty to let the model guess.
	//
	// Not a nicety. Measured against a local whisper.cpp on one second of a
	// 220 Hz tone: with no language it answered " (electronic music)", with
	// "ru" it answered " [музыка]". The model detects from the audio, and for
	// short dictated phrases in a language it did not expect, the guess is
	// wrong often enough to make the feature useless.
	Language string `json:"language"`
}

// APIKey is the key to send. The environment wins over the file, so an existing
// shell profile keeps working and the key can be kept out of the file entirely
// by anyone who prefers that.
//
// When it is in the file, the file is written 0600 — see Save. That is a
// deliberate trade: a service you configure from a UI has to store what you
// typed, and refusing to would only push the key into a wrapper script, which
// is not an improvement.
func (a ASR) APIKey() string {
	if key := os.Getenv("LINUXVR_ASR_KEY"); key != "" {
		return key
	}
	return a.Key
}

// Endpoint is where audio is posted, with the provider's preset applied.
func (a ASR) Endpoint() string {
	if url := os.Getenv("LINUXVR_ASR_URL"); url != "" {
		return url
	}
	if a.Provider == ProviderOpenAI {
		if a.URL != "" {
			return a.URL
		}
		return OpenAIEndpoint
	}
	return a.URL
}

// ModelName is what the endpoint is asked for. The defaults differ because the
// two providers ship different model names, and getting that wrong produces a
// 400 that says nothing useful.
func (a ASR) ModelName() string {
	if model := os.Getenv("LINUXVR_ASR_MODEL"); model != "" {
		return model
	}
	if a.Model != "" {
		return a.Model
	}
	if a.Provider == ProviderOpenAI {
		return "whisper-1"
	}
	return "whisper-large-v3-turbo"
}

// Default returns a configuration that works on a machine where nothing has
// been set up beyond the dependencies.
func Default() Config {
	return Config{
		Name: hostname(),
		Bind: "0.0.0.0",
		Tray: true,
		Capture: Capture{
			Device: firstRenderNode(),
			FPS:    60,
			QP:     23,
			Sudo:   true,
		},
		// No provider preset by default: a fresh install has no key and no
		// gateway, and guessing "openai" would only produce 401s from a
		// service the user never chose.
		ASR: ASR{Provider: ProviderCustom},
	}
}

// Path is where the configuration lives.
func Path() string {
	if dir := os.Getenv("XDG_CONFIG_HOME"); dir != "" {
		return filepath.Join(dir, "linuxvr", "config.json")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "linuxvr", "config.json")
}

// Load reads the configuration, filling in defaults for anything absent and
// writing the file if it does not exist yet.
func Load(path string) (Config, error) {
	if path == "" {
		path = Path()
	}
	cfg := Default()

	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return cfg, Save(path, cfg)
	}
	if err != nil {
		return cfg, err
	}
	// Unmarshalling over the defaults leaves absent fields alone, so a file
	// holding only {"name": "..."} keeps every other default.
	if err := json.Unmarshal(data, &cfg); err != nil {
		return cfg, fmt.Errorf("%s: %w", path, err)
	}
	cfg.fill()
	return cfg, nil
}

// Save writes the configuration, creating the directory if needed.
func Save(path string, cfg Config) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	// 0600, not 0644: this file can hold an API key now that the provider is
	// configurable. A key in a world-readable file is the kind of thing nobody
	// notices until it matters.
	if err := os.WriteFile(path, append(data, '\n'), 0o600); err != nil {
		return err
	}
	// WriteFile only applies the mode when it creates the file, so a config
	// written before keys were storable would keep its 0644 forever. Chmod
	// every time instead — it costs nothing and it is not worth remembering.
	return os.Chmod(path, 0o600)
}

// fill repairs a configuration whose fields were explicitly set to zero.
func (c *Config) fill() {
	d := Default()
	if c.Name == "" {
		c.Name = d.Name
	}
	if c.Bind == "" {
		c.Bind = d.Bind
	}
	if c.ASR.Provider == "" {
		c.ASR.Provider = ProviderCustom
	}
	if c.Capture.Device == "" {
		c.Capture.Device = d.Capture.Device
	}
	if c.Capture.FPS <= 0 {
		c.Capture.FPS = d.Capture.FPS
	}
	if c.Capture.QP <= 0 {
		c.Capture.QP = d.Capture.QP
	}
}

func hostname() string {
	name, err := os.Hostname()
	if err != nil || name == "" {
		return "linux-vr"
	}
	return name
}

// firstRenderNode picks the DRM node to capture from.
//
// card0 is not always the right answer — on a machine with an integrated GPU
// and a discrete one the numbering depends on probe order — but the alternative
// is refusing to start until the user names one, and the doctor command says
// which nodes exist when the guess is wrong.
func firstRenderNode() string {
	matches, _ := filepath.Glob("/dev/dri/card*")
	if len(matches) == 0 {
		return "/dev/dri/card0"
	}
	// Prefer a node whose connectors are actually connected; kmsgrab on an
	// unused node produces an empty capture rather than an error.
	for _, node := range matches {
		if hasConnectedOutput(filepath.Base(node)) {
			return node
		}
	}
	return matches[len(matches)-1]
}

func hasConnectedOutput(card string) bool {
	entries, err := filepath.Glob("/sys/class/drm/" + card + "-*/status")
	if err != nil {
		return false
	}
	for _, entry := range entries {
		status, err := os.ReadFile(entry)
		if err == nil && strings.TrimSpace(string(status)) == "connected" {
			return true
		}
	}
	return false
}

// HasCommand reports whether an external tool the server shells out to exists.
// Used by the doctor command, which is the only place that should be surprised.
func HasCommand(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}
