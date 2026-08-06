package dev.butschster.linuxvr.term;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Speech into the terminal.
 *
 * <p>The recognition itself is the host's existing voice agent, unchanged. What is
 * different from the streamed desktop is where the text ends up: there it had to be
 * pushed into somebody else's window through the clipboard and Ctrl+V, because uinput
 * speaks scancodes and mutter does not implement the virtual keyboard protocol. Here the
 * client owns the pty, so the transcript is simply written to it — the whole class of
 * layout problems disappears rather than being worked around.
 */
public class Dictation {

    private static final String TAG = "linux-vr";
    private static final int VOICE_PORT = 9102;
    private static final int SAMPLE_RATE = 16000;   // what Whisper resamples to anyway
    private static final long MAX_MS = 5 * 60 * 1000;

    public interface Listener {
        void onLevel(float level);

        void onElapsed(long millis);

        void onRecognising();

        /** Transcript, or null when there was nothing to hear. */
        void onResult(String text, String problem);
    }

    private final String host;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean recording;
    private long startedAt;

    public Dictation(String host, Listener listener) {
        this.host = host;
        this.listener = listener;
    }

    public boolean isRecording() {
        return recording;
    }

    public void start() {
        if (recording) return;
        recording = true;
        startedAt = System.currentTimeMillis();
        new Thread(this::record, "dictation").start();
        tick();
    }

    public void stop() {
        if (!recording) return;
        recording = false;
        main.post(listener::onRecognising);
    }

    private void tick() {
        if (!recording) return;
        main.post(() -> listener.onElapsed(System.currentTimeMillis() - startedAt));
        main.postDelayed(this::tick, 500);
    }

    private void record() {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            finish(null, "microphone unavailable");
            return;
        }

        AudioRecord recorder;
        try {
            // VOICE_RECOGNITION rather than MIC: noise handling meant for speech.
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4);
        } catch (SecurityException e) {
            finish(null, "no permission to record");
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            finish(null, "recorder did not initialise");
            return;
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[minBuffer];
        recorder.startRecording();
        while (recording) {
            int n = recorder.read(buffer, 0, buffer.length);
            if (n > 0) {
                captured.write(buffer, 0, n);
                float level = rms(buffer, n);
                main.post(() -> listener.onLevel(level));
            }
            if (System.currentTimeMillis() - startedAt > MAX_MS) {
                Log.i(TAG, "dictation hit the five minute limit");
                recording = false;
                main.post(listener::onRecognising);
            }
        }
        recorder.stop();
        recorder.release();

        byte[] pcm = captured.toByteArray();
        Log.i(TAG, "captured " + (pcm.length / (SAMPLE_RATE * 2.0)) + "s");
        if (pcm.length == 0) {
            finish(null, "nothing recorded");
            return;
        }
        transcribe(pcm);
    }

    private void transcribe(byte[] pcm) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, VOICE_PORT), 3000);
            OutputStream out = socket.getOutputStream();
            // "asr" and not "pcm": transcribe only, do not paste anywhere.
            out.write(("asr " + SAMPLE_RATE + " 1\n").getBytes("UTF-8"));
            out.write(pcm);
            out.flush();
            socket.shutdownOutput();

            socket.setSoTimeout(60000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String text = in.readLine();
            Log.i(TAG, "recognised: " + text);
            if (text == null || text.trim().isEmpty()) finish(null, "nothing recognised");
            else finish(text.trim(), null);
        } catch (IOException e) {
            Log.w(TAG, "recognition failed: " + e.getMessage());
            finish(null, "recognition failed");
        }
    }

    private void finish(String text, String problem) {
        recording = false;
        main.post(() -> listener.onResult(text, problem));
    }

    /** Root mean square of a 16-bit little-endian block, normalised to 0..1. */
    private static float rms(byte[] pcm, int length) {
        long sum = 0;
        int samples = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long) sample * sample;
            samples++;
        }
        if (samples == 0) return 0f;
        return (float) (Math.sqrt(sum / (double) samples) / 32768.0);
    }
}
