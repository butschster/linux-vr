// Package voice turns speech and typed strings from the headset into text at
// the cursor, and presses the keys a headset has no other way to press.
//
// Why the clipboard rather than typing the text out: uinput works in scancodes
// tied to the keyboard layout, so Cyrillic, punctuation and anything non-ASCII
// becomes garbage or depends on which layout happens to be active. wtype does
// not help either — it needs zwp_virtual_keyboard, which mutter does not
// implement, so on GNOME Wayland that is a dead end. The clipboard depends on
// neither: wl-copy carries finished UTF-8 and Ctrl+V is two scancodes that mean
// the same thing in any layout.
//
// Voice and keyboard converge here on purpose: both produce a string, and
// inserting a string is the part that is awkward on Wayland, so it is written
// once.
package voice

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"mime/multipart"
	"net"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/vr-meta/linux-vr/host/internal/config"
)

// Whisper resamples everything to 16 kHz mono internally, so recording at that
// rate on the headset is lossless for recognition and keeps the upload small.
const expectedRate = 16000

// Service holds the virtual keyboard and where to send audio.
//
// The endpoint is held in an atomic pointer because it is editable while the
// server runs: changing a key in the UI should take effect on the next
// utterance, not on the next reboot.
type Service struct {
	asr      atomic.Pointer[config.ASR]
	keyboard *Keyboard
}

func New(asr config.ASR) (*Service, error) {
	keyboard, err := NewKeyboard()
	if err != nil {
		return nil, err
	}
	service := &Service{keyboard: keyboard}
	service.SetASR(asr)
	return service, nil
}

// SetASR swaps the endpoint. Safe at any time; an utterance already in flight
// finishes against the settings it started with.
func (s *Service) SetASR(asr config.ASR) { s.asr.Store(&asr) }

func (s *Service) settings() config.ASR { return *s.asr.Load() }

func (s *Service) Close() { s.keyboard.Close() }

// Available reports whether transcription is configured. Typing and key presses
// work regardless, which is why this is not fatal.
func (s *Service) Available() (bool, string) {
	asr := s.settings()
	if asr.Endpoint() == "" {
		if asr.Provider == config.ProviderOpenAI {
			return false, "no API key for OpenAI"
		}
		return false, "no transcription endpoint configured"
	}
	if asr.Provider == config.ProviderOpenAI && asr.APIKey() == "" {
		return false, "no API key for OpenAI"
	}
	return true, ""
}

// Test posts a short sound and reports what came back, so a key or a URL can be
// checked from the UI without putting the headset on. An empty transcript is a
// pass: the endpoint answered, which is the whole question.
func (s *Service) Test() (time.Duration, error) {
	started := time.Now()
	// 0.6 s of a quiet 220 Hz tone rather than silence. Some gateways reject a
	// completely silent upload, and the point is to test the plumbing.
	samples := make([]byte, 0, expectedRate*2*6/10)
	for i := 0; i < expectedRate*6/10; i++ {
		v := int16(1200 * math.Sin(2*math.Pi*220*float64(i)/float64(expectedRate)))
		samples = append(samples, byte(v), byte(v>>8))
	}
	_, err := s.transcribe(wav(samples, expectedRate, 1))
	return time.Since(started), err
}

// Serve handles connections. Each carries one utterance or one string:
//
//	"pcm <rate> <channels>\n" + signed 16-bit samples
//	    transcribe and insert at the cursor, then return the text so the
//	    client can show what was heard. Insertion is direct on purpose: a
//	    shell line is itself the place to fix a misheard word, and a review
//	    step turns two seconds of dictation into four.
//
//	"asr <rate> <channels>\n" + samples
//	    transcribe and return the text without inserting it. For a client
//	    that owns a pty and writes the transcript into it directly.
//
//	"text\n" + UTF-8            insert as is — the on-screen keyboard path
//	"key <name>\n"              press a key or a chord: ctrl+c, shift+tab, enter
//
// All of them end when the client half-closes.
func (s *Service) Serve(listener net.Listener) error {
	for {
		conn, err := listener.Accept()
		if err != nil {
			return err
		}
		go s.handle(conn)
	}
}

func (s *Service) handle(conn net.Conn) {
	defer conn.Close()
	address := conn.RemoteAddr().String()

	// A minute is generous for a five-minute recording cap on the client side
	// and short enough that a stuck connection frees the socket by itself.
	_ = conn.SetDeadline(time.Now().Add(60 * time.Second))

	data, err := io.ReadAll(conn)
	if err != nil && len(data) == 0 {
		return
	}
	if len(data) == 0 {
		return
	}

	switch {
	case bytes.HasPrefix(data, []byte("key ")):
		combo := strings.ToLower(strings.TrimSpace(string(data[4:])))
		if err := s.keyboard.Press(combo); err != nil {
			log.Printf("[voice] %s: key %s (%v)", address, combo, err)
		} else {
			log.Printf("[voice] %s: key %s", address, combo)
		}
		_, _ = conn.Write([]byte("\n"))

	case bytes.HasPrefix(data, []byte("text\n")):
		text := strings.TrimSpace(string(data[5:]))
		log.Printf("[voice] %s: typed %q", address, text)
		if text != "" {
			s.insert(text)
		}
		_, _ = conn.Write([]byte(text + "\n"))

	default:
		s.transcribeAndReply(conn, address, data)
	}
}

func (s *Service) transcribeAndReply(conn net.Conn, address string, data []byte) {
	rate, channels := expectedRate, 1
	insert := true

	if bytes.HasPrefix(data, []byte("pcm ")) || bytes.HasPrefix(data, []byte("asr ")) {
		insert = bytes.HasPrefix(data, []byte("pcm "))
		header, rest, found := bytes.Cut(data, []byte("\n"))
		if !found {
			return
		}
		fields := strings.Fields(string(header))
		if len(fields) >= 3 {
			if v, err := strconv.Atoi(fields[1]); err == nil && v > 0 {
				rate = v
			}
			if v, err := strconv.Atoi(fields[2]); err == nil && v > 0 {
				channels = v
			}
		}
		data = rest
	}

	seconds := float64(len(data)) / float64(rate*channels*2)
	log.Printf("[voice] %s: %d bytes, %.1fs at %d Hz", address, len(data), seconds, rate)
	if seconds < 0.2 {
		log.Printf("[voice] too short to be speech, ignoring")
		return
	}

	text, err := s.transcribe(wav(data, rate, channels))
	if err != nil {
		log.Printf("[voice] recognition failed: %v", err)
		_, _ = conn.Write([]byte("\n"))
		return
	}
	if text != "" && insert {
		s.insert(text)
	}
	_, _ = conn.Write([]byte(text + "\n"))
}

// insert puts text at the cursor and gives the clipboard back.
//
// Without restoring it, dictation silently destroys whatever the user copied a
// minute ago — maddening, and not obvious to diagnose.
func (s *Service) insert(text string) {
	saved := clipboardGet()
	clipboardSet([]byte(text))
	// The compositor needs a moment to notice the new selection; pasting
	// immediately can insert the previous contents.
	time.Sleep(100 * time.Millisecond)
	_ = s.keyboard.Paste()
	time.Sleep(200 * time.Millisecond)
	clipboardSet(saved)
}

// ------------------------------------------------------------------ clipboard

func clipboardGet() []byte {
	out, err := exec.Command("wl-paste", "--no-newline").Output()
	if err != nil {
		return nil
	}
	return out
}

func clipboardSet(data []byte) {
	cmd := exec.Command("wl-copy")
	cmd.Stdin = bytes.NewReader(data)
	_ = cmd.Run()
}

// ---------------------------------------------------------------- recognition

// wav wraps raw samples in the smallest header an HTTP gateway will accept.
func wav(pcm []byte, rate, channels int) []byte {
	var buf bytes.Buffer
	byteRate := rate * channels * 2
	buf.WriteString("RIFF")
	_ = binary.Write(&buf, binary.LittleEndian, uint32(36+len(pcm)))
	buf.WriteString("WAVEfmt ")
	_ = binary.Write(&buf, binary.LittleEndian, uint32(16))         // chunk size
	_ = binary.Write(&buf, binary.LittleEndian, uint16(1))          // PCM
	_ = binary.Write(&buf, binary.LittleEndian, uint16(channels))   //
	_ = binary.Write(&buf, binary.LittleEndian, uint32(rate))       //
	_ = binary.Write(&buf, binary.LittleEndian, uint32(byteRate))   //
	_ = binary.Write(&buf, binary.LittleEndian, uint16(channels*2)) // block align
	_ = binary.Write(&buf, binary.LittleEndian, uint16(16))         // bits per sample
	buf.WriteString("data")
	_ = binary.Write(&buf, binary.LittleEndian, uint32(len(pcm)))
	buf.Write(pcm)
	return buf.Bytes()
}

func (s *Service) transcribe(audio []byte) (string, error) {
	asr := s.settings()
	url := asr.Endpoint()
	if url == "" {
		return "", fmt.Errorf("no transcription endpoint configured")
	}

	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	if err := form.WriteField("model", asr.ModelName()); err != nil {
		return "", err
	}
	part, err := form.CreateFormFile("file", "speech.wav")
	if err != nil {
		return "", err
	}
	if _, err := part.Write(audio); err != nil {
		return "", err
	}
	if err := form.Close(); err != nil {
		return "", err
	}

	request, err := http.NewRequest(http.MethodPost, url, &body)
	if err != nil {
		return "", err
	}
	request.Header.Set("Content-Type", form.FormDataContentType())
	if key := asr.APIKey(); key != "" {
		request.Header.Set("Authorization", "Bearer "+key)
	}

	started := time.Now()
	client := &http.Client{Timeout: 60 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		snippet, _ := io.ReadAll(io.LimitReader(response.Body, 200))
		return "", fmt.Errorf("gateway returned %s: %s", response.Status, strings.TrimSpace(string(snippet)))
	}

	var payload struct {
		Text string `json:"text"`
	}
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		return "", err
	}
	text := strings.TrimSpace(payload.Text)
	log.Printf("[voice] recognised in %.2fs: %q", time.Since(started).Seconds(), text)
	return text, nil
}
