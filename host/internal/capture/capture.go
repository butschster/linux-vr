// Package capture serves each monitor as a live H.264 stream.
//
// The difference from the shell script this replaces is who owns the socket.
// ffmpeg used to listen itself, which meant it served exactly one connection
// and exited when the client went away, so the host needed a restart loop
// around it. Here the server owns the listener and starts an encoder per
// connection: reconnecting works by construction, and — the part that actually
// matters on a laptop — nothing is encoded while no one is watching.
package capture

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/vr-meta/linux-vr/host/internal/config"
	"github.com/vr-meta/linux-vr/host/internal/monitors"
)

// Service holds one listener per monitor.
type Service struct {
	cfg    config.Capture
	bind   string
	layout monitors.Layout

	mu      sync.Mutex
	current map[int]*encoder // monitor index -> the encoder feeding it
}

type encoder struct {
	cmd  *exec.Cmd
	conn net.Conn
}

func New(cfg config.Capture, bind string, layout monitors.Layout) *Service {
	return &Service{cfg: cfg, bind: bind, layout: layout, current: map[int]*encoder{}}
}

// Available reports whether capture can work at all. The client is told, so a
// missing encoder shows up as "this server has no video" rather than as a
// window that never paints.
func (s *Service) Available() (bool, string) {
	if !config.HasCommand("ffmpeg") {
		return false, "ffmpeg is not installed"
	}
	device := s.settings().Device
	if _, err := os.Stat(device); err != nil {
		return false, device + " does not exist"
	}
	for _, m := range s.layout.Monitors {
		if m.CRTC >= 0 {
			return true, ""
		}
	}
	// Without a CRTC id kmsgrab captures whichever output it feels like, which
	// on a two-monitor desk is a coin toss.
	return false, "no CRTC ids; DRM state needs root, see `linux-vr-server doctor`"
}

// Serve starts a listener for every monitor and blocks until they all fail.
func (s *Service) Serve() error {
	if len(s.layout.Monitors) == 0 {
		return fmt.Errorf("no monitors to capture")
	}
	errs := make(chan error, len(s.layout.Monitors))
	for _, m := range s.layout.Monitors {
		go func(m monitors.Monitor) { errs <- s.serveMonitor(m) }(m)
	}
	return <-errs
}

func (s *Service) serveMonitor(m monitors.Monitor) error {
	address := net.JoinHostPort(s.bind, strconv.Itoa(m.Port))
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return fmt.Errorf("monitor %d: %w", m.Index, err)
	}
	log.Printf("[capture] monitor %d (%s) %dx%d on :%d",
		m.Index, m.Connector, m.Width, m.Height, m.Port)

	for {
		conn, err := listener.Accept()
		if err != nil {
			return err
		}
		go s.stream(m, conn)
	}
}

// stream encodes for exactly one client.
//
// A second client on the same monitor replaces the first rather than joining
// it: two kmsgrab captures of one CRTC cost twice the GPU for the same picture,
// and the case this actually happens in is a window that was closed without the
// socket noticing yet. The newest window is the one someone is looking at.
func (s *Service) stream(m monitors.Monitor, conn net.Conn) {
	defer conn.Close()

	cmd := s.command(m)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		log.Printf("[capture] monitor %d: %v", m.Index, err)
		return
	}
	cmd.Stderr = prefixWriter{fmt.Sprintf("[capture %d] ", m.Index)}
	// ffmpeg reads its input from KMS, not from stdin; leaving stdin attached
	// to the server's terminal makes it swallow keystrokes meant for the log.
	cmd.Stdin = nil

	if err := cmd.Start(); err != nil {
		log.Printf("[capture] monitor %d: cannot start ffmpeg: %v", m.Index, err)
		return
	}
	log.Printf("[capture] monitor %d: client %s, encoding", m.Index, conn.RemoteAddr())

	s.replace(m.Index, &encoder{cmd: cmd, conn: conn})

	// The client never sends anything on this socket, so a read returning is
	// how a closed window is noticed. Without it, an encoder for a window that
	// went away keeps running until the TCP stack times out.
	go func() {
		var scratch [1]byte
		_, _ = conn.Read(scratch[:])
		stop(cmd)
	}()

	_, copyErr := io.Copy(conn, stdout)
	stop(cmd)
	_ = cmd.Wait()
	s.clear(m.Index, cmd)

	if copyErr != nil {
		log.Printf("[capture] monitor %d: client gone (%v)", m.Index, copyErr)
	} else {
		log.Printf("[capture] monitor %d: encoder stopped", m.Index)
	}
}

func (s *Service) replace(index int, e *encoder) {
	s.mu.Lock()
	previous := s.current[index]
	s.current[index] = e
	s.mu.Unlock()

	if previous != nil {
		log.Printf("[capture] monitor %d: a newer client took over", index)
		_ = previous.conn.Close()
		stop(previous.cmd)
	}
}

func (s *Service) clear(index int, cmd *exec.Cmd) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if e := s.current[index]; e != nil && e.cmd == cmd {
		delete(s.current, index)
	}
}

// SetConfig changes the encoder settings.
//
// It applies to the next encoder that starts, not to one already running:
// ffmpeg's flags are fixed at exec, and restarting a live stream to change a
// quantiser would blank the window someone is working in.
func (s *Service) SetConfig(cfg config.Capture) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cfg = cfg
}

func (s *Service) settings() config.Capture {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.cfg
}

// Streaming reports which monitors are being encoded right now, for the
// control API.
func (s *Service) Streaming() map[int]bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	active := make(map[int]bool, len(s.current))
	for index := range s.current {
		active[index] = true
	}
	return active
}

// command builds the encoder invocation.
//
// Every flag here was chosen against a measurement; the reasoning is kept
// because it is the part that is expensive to rediscover.
func (s *Service) command(m monitors.Monitor) *exec.Cmd {
	cfg := s.settings()
	fps := strconv.Itoa(cfg.FPS)

	args := []string{
		"-hide_banner", "-loglevel", "warning",
		// Capture through KMS: ffmpeg gets the framebuffer as a DMA-BUF and
		// hands it straight to VAAPI, with no copy of the frame through system
		// memory. The price is CAP_SYS_ADMIN, which is why sudo is here.
		"-device", cfg.Device,
		"-f", "kmsgrab",
		// The rate must match the display, not the headset. Asking kmsgrab for
		// more than the display produces breaks timestamp generation outright:
		// capturing a 60 Hz output at 90 gave ~11000 duplicated frames with
		// output time frozen at 0.01 s, and the client saw one still frame.
		//
		// The mismatch with the headset is harmless, and that is the point of a
		// composition layer: it is world-locked and the compositor reprojects
		// it at 90 Hz whether or not a new frame arrived.
		"-framerate", fps,
	}
	if m.CRTC >= 0 {
		args = append(args, "-crtc_id", strconv.Itoa(m.CRTC))
	}
	args = append(args,
		"-i", "-",
		"-vf", fmt.Sprintf("hwmap=derive_device=vaapi,scale_vaapi=w=%d:h=%d:format=nv12",
			m.Width, m.Height),
		"-c:v", "h264_vaapi", "-profile:v", "high",
		// Constant quality, not constant bitrate. A desktop is mostly static and
		// CBR is the wrong trade for it: measured with CBR 100M on a still
		// desktop, 341 of 358 frames were duplicates, each padded to a megabyte;
		// the Wi-Fi link could not carry it, ffmpeg blocked on the socket write
		// and the pipeline ran at 0.49x real time.
		"-rc_mode", "CQP", "-global_quality", strconv.Itoa(cfg.QP),
		// B-frames add reordering latency and their bitrate saving is not needed.
		"-bf", "0",
		// One IDR per second: a client connecting at an arbitrary moment waits
		// at most a second for a picture, without the bitrate spikes that a
		// shorter GOP turns into dropped frames over Wi-Fi.
		"-g", fps,
		"-flags", "+low_delay", "-fflags", "+nobuffer", "-flush_packets", "1",
		"-f", "h264", "pipe:1",
	)

	if cfg.Sudo {
		// -n so a missing sudo rule fails immediately instead of waiting on a
		// password prompt nobody is there to answer.
		return exec.Command("sudo", append([]string{"-n", "ffmpeg"}, args...)...)
	}
	return exec.Command("ffmpeg", args...)
}

// stop ends an encoder.
//
// Closing the pipe is usually enough — ffmpeg gets EPIPE on its next write and
// exits — but a still desktop under CQP can go a second between writes, and
// under sudo the signal has to travel one process further. sudo relays what it
// receives to the command, so SIGTERM to the wrapper reaches ffmpeg; SIGKILL
// after a grace period covers the case where it does not.
func stop(cmd *exec.Cmd) {
	if cmd.Process == nil {
		return
	}
	_ = cmd.Process.Signal(syscall.SIGTERM)
	go func() {
		time.Sleep(2 * time.Second)
		_ = cmd.Process.Signal(syscall.SIGKILL)
	}()
}

// prefixWriter tags ffmpeg's diagnostics with the monitor they belong to.
type prefixWriter struct{ prefix string }

func (w prefixWriter) Write(p []byte) (int, error) {
	log.Printf("%s%s", w.prefix, trimNewline(string(p)))
	return len(p), nil
}

func trimNewline(s string) string {
	for len(s) > 0 && (s[len(s)-1] == '\n' || s[len(s)-1] == '\r') {
		s = s[:len(s)-1]
	}
	return s
}
