package dev.butschster.linuxvr.term;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSessionClient;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A shell running on the host, seen from here as bytes and a bit of context.
 *
 * <p>Upstream Termux couples its {@code TerminalSession} to a local process through JNI.
 * This one has no process to fork: the pty lives on the Linux host and reaches us over
 * a socket, so the transport is replaced and the emulator above it is untouched.
 *
 * <p>The emulator is not thread safe, and Termux's answer — do all of it on the main
 * thread — is the right one here too. The reader thread only hands buffers over; the
 * coalescing matters because a build log arrives as thousands of small reads and one
 * post per read would spend the frame budget on message dispatch.
 */
public class HostSession extends TerminalOutput implements TerminalSessionClient {

    private static final String TAG = "linux-vr";

    static final int MSG_DATA = 0x01;
    static final int MSG_RESIZE = 0x02;
    static final int MSG_REQUEST = 0x03;
    static final int MSG_OUT = 0x81;
    static final int MSG_CONTEXT = 0x82;
    static final int MSG_EXIT = 0x83;

    /** Scrollback. 4000 lines is about a full build log and costs a couple of MB. */
    private static final int TRANSCRIPT_ROWS = 4000;

    public interface Listener {
        /** New output has been fed to the emulator; redraw. */
        void onTextChanged();

        /** The host says where we are and what is in front. */
        void onContext(JSONObject context);

        /** Human readable connection state, shown in the bar. */
        void onStatus(String message, boolean connected);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TerminalEmulator emulator;
    private Socket socket;
    private final LinkedBlockingQueue<byte[]> outbound = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private boolean everConnected;

    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private boolean drainScheduled;

    private int columns = 80, rows = 24, cellWidth = 10, cellHeight = 20;

    public HostSession(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    // ------------------------------------------------------------------ life

    /**
     * Called once the view knows how big a cell is. The emulator cannot exist before
     * that — its whole state is sized in columns and rows — so this is also where the
     * connection starts.
     */
    public void updateSize(int columns, int rows, int cellWidth, int cellHeight) {
        if (columns < 4 || rows < 2) return;
        this.columns = columns;
        this.rows = rows;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;

        if (emulator == null) {
            emulator = new TerminalEmulator(this, columns, rows, cellWidth, cellHeight,
                    TRANSCRIPT_ROWS, this);
            start();
        } else {
            emulator.resize(columns, rows, cellWidth, cellHeight);
        }
        sendResize();
    }

    private void start() {
        running = true;
        new Thread(this::reader, "term-reader").start();
    }

    public void close() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // -------------------------------------------------------------- transport

    /**
     * Connect, read frames, reconnect. Retrying matters more than it looks: the agent
     * is usually started by hand on the host, so the app is frequently up first, and
     * without this the only cure would be relaunching the window.
     */
    private void reader() {
        while (running) {
            Thread writerThread = null;
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 4000);
                s.setTcpNoDelay(true);
                socket = s;
                status("connected to " + host, true);
                if (everConnected) {
                    // A new connection is a new shell; leaving the dead one's screen
                    // on display invites reading stale output as live.
                    main.post(() -> {
                        if (emulator != null) emulator.reset();
                        listener.onTextChanged();
                    });
                }
                everConnected = true;

                final Socket connected = s;
                writerThread = new Thread(() -> writer(connected), "term-writer");
                writerThread.start();
                sendResize();

                DataInputStream in = new DataInputStream(s.getInputStream());
                while (running) {
                    int kind = in.readUnsignedByte();
                    int length = in.readInt();
                    if (length < 0 || length > 8 * 1024 * 1024) {
                        throw new IOException("bad frame " + length);
                    }
                    byte[] payload = new byte[length];
                    in.readFully(payload);
                    dispatch(kind, payload);
                }
            } catch (IOException e) {
                Log.w(TAG, "session ended: " + e.getMessage());
                status("no host — retrying (" + e.getMessage() + ")", false);
            } finally {
                socket = null;
                // Unblock the writer, which is parked on take(), and drop whatever it
                // was about to send: those bytes belong to a shell that is gone.
                outbound.clear();
                outbound.offer(new byte[0]);
            }
            if (!running) break;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                break;
            }
            if (writerThread != null) writerThread.interrupt();
        }
    }

    private void dispatch(int kind, byte[] payload) {
        switch (kind) {
            case MSG_OUT:
                synchronized (pending) {
                    pending.add(payload);
                    if (!drainScheduled) {
                        drainScheduled = true;
                        main.post(this::drain);
                    }
                }
                break;
            case MSG_CONTEXT:
                try {
                    JSONObject ctx = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                    main.post(() -> listener.onContext(ctx));
                } catch (Exception e) {
                    Log.w(TAG, "bad context: " + e.getMessage());
                }
                break;
            case MSG_EXIT:
                status("shell exited", false);
                break;
            default:
                break;
        }
    }

    private void drain() {
        ArrayDeque<byte[]> batch;
        synchronized (pending) {
            batch = new ArrayDeque<>(pending);
            pending.clear();
            drainScheduled = false;
        }
        if (emulator == null) return;
        for (byte[] chunk : batch) emulator.append(chunk, chunk.length);
        listener.onTextChanged();
    }

    /** Bound to one connection: when that socket is replaced, this thread is done. */
    private void writer(Socket connected) {
        try {
            OutputStream out = connected.getOutputStream();
            while (running && socket == connected) {
                byte[] frame = outbound.take();
                if (frame.length == 0) continue;
                out.write(frame);
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "writer ended: " + e.getMessage());
        }
    }

    private void frame(int kind, byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = (byte) kind;
        out[1] = (byte) (payload.length >>> 24);
        out[2] = (byte) (payload.length >>> 16);
        out[3] = (byte) (payload.length >>> 8);
        out[4] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 5, payload.length);
        outbound.offer(out);
    }

    private void sendResize() {
        if (socket == null) return;
        frame(MSG_RESIZE, ("{\"cols\":" + columns + ",\"rows\":" + rows + "}")
                .getBytes(StandardCharsets.UTF_8));
    }

    public void request(String json) {
        frame(MSG_REQUEST, json.getBytes(StandardCharsets.UTF_8));
    }

    private void status(String message, boolean connected) {
        main.post(() -> listener.onStatus(message, connected));
    }

    // ---------------------------------------------------------- TerminalOutput

    @Override
    public void write(byte[] data, int offset, int count) {
        if (count <= 0) return;
        byte[] copy = new byte[count];
        System.arraycopy(data, offset, copy, 0, count);
        frame(MSG_DATA, copy);
    }

    /** Write one character, optionally as Alt+key (an ESC prefix, as terminals mean it). */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        String text = new String(Character.toChars(codePoint));
        if (prependEscape) text = "\u001b" + text;
        write(text);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
    }

    @Override
    public void onCopyTextToClipboard(String text) {
    }

    @Override
    public void onPasteTextFromClipboard() {
    }

    @Override
    public void onBell() {
    }

    @Override
    public void onColorsChanged() {
    }

    // ----------------------------------------------------- TerminalSessionClient

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        main.post(listener::onTextChanged);
    }

    @Override
    public void logError(String tag, String message) {
        Log.e(TAG, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(TAG, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(TAG, message);
    }

    @Override
    public void logDebug(String tag, String message) {
    }

    @Override
    public void logVerbose(String tag, String message) {
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.w(TAG, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.w(TAG, "error", e);
    }
}
