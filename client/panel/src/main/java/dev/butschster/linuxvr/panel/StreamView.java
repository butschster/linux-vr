package dev.butschster.linuxvr.panel;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Shows the streamed desktop and forwards pointer input back to the host.
 *
 * The decoder writes straight into the SurfaceView's surface, so a frame still
 * never passes through system memory on its way to the screen. What changes
 * compared with the immersive client is who owns the placement: here the shell
 * does, which is what makes the app coexist with other windows.
 */
public class StreamView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "linux-vr";
    private static final int STREAM_PORT = 9100;
    private static final int INPUT_PORT = 9101;

    private String host = "";
    private volatile boolean running = false;
    private Thread videoThread;
    private Socket inputSocket;
    private OutputStream inputOut;

    // Input events arrive on the UI thread, and Android forbids socket writes
    // there. A bounded queue drained by a sender thread keeps the pointer off
    // the main thread; when it overflows the oldest position is dropped, which
    // is right for a pointer since the newest position supersedes it anyway.
    private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(64);

    private float lastU = -1f;
    private float lastV = -1f;

    public StreamView(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(true);
    }

    public void setHost(String host) {
        this.host = host;
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (host.isEmpty()) {
            Log.e(TAG, "no host configured, nothing to show");
            return;
        }
        running = true;
        videoThread = new Thread(() -> runVideo(holder.getSurface()), "video");
        videoThread.start();
        new Thread(this::connectInput, "input").start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "panel surface " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        if (videoThread != null) {
            try {
                videoThread.join(2000);
            } catch (InterruptedException ignored) {
            }
        }
        closeInput();
    }

    // ---------------------------------------------------------------- video

    private void runVideo(Surface surface) {
        while (running) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, STREAM_PORT), 3000);
                socket.setTcpNoDelay(true);
                Log.i(TAG, "connected to " + host + ":" + STREAM_PORT);
                decodeLoop(socket.getInputStream(), surface);
            } catch (IOException e) {
                // The host re-listens after every disconnect, so a refusal here
                // usually means we arrived during the gap between two ffmpeg
                // runs. Retrying beats leaving a black panel.
                Log.w(TAG, "stream connect failed: " + e.getMessage());
            }
            sleep(1000);
        }
    }

    private void decodeLoop(InputStream in, Surface surface) throws IOException {
        // Width and height here are advisory: the decoder takes the real
        // geometry from the SPS in the stream.
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 2560, 1440);
        format.setInteger("low-latency", 1);

        MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, surface, null, 0);
        codec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        new Thread(() -> drainOutput(codec, info), "drain").start();

        byte[] chunk = new byte[64 * 1024];
        byte[] pending = new byte[1 << 20];
        int pendingLen = 0;
        long ptsUs = 0;

        try {
            while (running) {
                int n = in.read(chunk);
                if (n < 0) break;

                if (pendingLen + n > pending.length) {
                    // A single access unit larger than a megabyte means the
                    // stream is not what we think it is; start over rather than
                    // grow without bound.
                    pendingLen = 0;
                    continue;
                }
                System.arraycopy(chunk, 0, pending, pendingLen, n);
                pendingLen += n;

                int cursor = findStartCode(pending, 0, pendingLen);
                if (cursor < 0) continue;

                int auStart = cursor;
                while (true) {
                    int codeLen = startCodeLength(pending, cursor);
                    int next = findStartCode(pending, cursor + codeLen, pendingLen);
                    if (next < 0) break;

                    int nalType = pending[cursor + codeLen] & 0x1F;
                    if (nalType == 1 || nalType == 5) {
                        // A VCL NAL closes the access unit: everything from the
                        // previous boundary up to and including it is one frame.
                        int size = next - auStart;
                        int index = codec.dequeueInputBuffer(10000);
                        if (index >= 0) {
                            ByteBuffer buf = codec.getInputBuffer(index);
                            buf.clear();
                            buf.put(pending, auStart, Math.min(size, buf.capacity()));
                            codec.queueInputBuffer(index, 0, Math.min(size, buf.capacity()), ptsUs, 0);
                            ptsUs += 1000000 / 60;
                        }
                        auStart = next;
                    }
                    cursor = next;
                }

                System.arraycopy(pending, auStart, pending, 0, pendingLen - auStart);
                pendingLen -= auStart;
            }
        } finally {
            try {
                codec.stop();
                codec.release();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    private void drainOutput(MediaCodec codec, MediaCodec.BufferInfo info) {
        // Release frames as soon as they decode. A live stream is not paced by
        // presentation time: latency costs more than smoothness here.
        while (running) {
            try {
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, true);
                }
            } catch (IllegalStateException e) {
                return;
            }
        }
    }

    private static int startCodeLength(byte[] b, int at) {
        return (b[at + 2] == 1) ? 3 : 4;
    }

    private static int findStartCode(byte[] b, int from, int limit) {
        for (int i = Math.max(from, 0); i + 3 <= limit; i++) {
            if (b[i] == 0 && b[i + 1] == 0) {
                if (b[i + 2] == 1) return i;
                if (i + 4 <= limit && b[i + 2] == 0 && b[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- input

    private void connectInput() {
        while (running) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, INPUT_PORT), 3000);
                s.setTcpNoDelay(true);
                inputSocket = s;
                inputOut = s.getOutputStream();
                Log.i(TAG, "input agent connected");
                pumpOutbox();
            } catch (IOException e) {
                Log.w(TAG, "no input agent: " + e.getMessage());
            }
            closeInput();
            sleep(1000);
        }
    }

    private void pumpOutbox() throws IOException {
        while (running) {
            String line;
            try {
                line = outbox.take();
            } catch (InterruptedException e) {
                return;
            }
            OutputStream out = inputOut;
            if (out == null) return;
            out.write(line.getBytes());
            out.flush();
        }
    }

    private void closeInput() {
        try {
            if (inputSocket != null) inputSocket.close();
        } catch (IOException ignored) {
        }
        inputSocket = null;
        inputOut = null;
    }

    private void send(String line) {
        if (!outbox.offer(line)) {
            // Full: drop the oldest and retry once. Never block — this runs on
            // the UI thread.
            outbox.poll();
            outbox.offer(line);
        }
    }

    private void sendPosition(float x, float y) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float u = Math.min(Math.max(x / w, 0f), 1f);
        float v = Math.min(Math.max(y / h, 0f), 1f);
        if (Math.abs(u - lastU) < 0.0002f && Math.abs(v - lastV) < 0.0002f) return;
        lastU = u;
        lastV = v;
        send(String.format(Locale.US, "m %.5f %.5f%n", u, v));
    }

    // The shell delivers the controller ray as hover events while the trigger
    // is up and as touch events once it is pressed. Both have to be handled or
    // the pointer only moves while clicking.
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_HOVER_MOVE
                || event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
            sendPosition(event.getX(), event.getY());
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        sendPosition(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                send("d left\n");
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                send("u left\n");
                return true;
            default:
                return true;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
