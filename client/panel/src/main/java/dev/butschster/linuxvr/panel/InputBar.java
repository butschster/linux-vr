package dev.butschster.linuxvr.panel;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Typing and dictation, both ending up as text at the Linux cursor.
 *
 * There is no custom keyboard here on purpose: a text field is all it takes for
 * Horizon OS to raise its own system keyboard, which already handles hands,
 * controllers and layouts far better than anything written here would.
 *
 * Voice and typing converge on the host, which inserts a finished string via
 * the clipboard. That is the part that is awkward on Wayland, and it is written
 * once rather than twice.
 */
public class InputBar extends LinearLayout {

    private static final String TAG = "linux-vr";
    private static final int VOICE_PORT = 9102;

    private static final int SAMPLE_RATE = 16000;   // what Whisper wants anyway

    private final EditText field;
    private final Button mic;
    private final Button toggle;
    private boolean expanded = false;

    private String host = "";
    private volatile boolean recording = false;

    public InputBar(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(Color.argb(190, 20, 20, 24));
        setPadding(16, 8, 16, 8);

        field = new EditText(context);
        field.setHint("type here, Enter sends to the desktop");
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.argb(150, 255, 255, 255));
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND);
        field.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        field.setOnEditorActionListener((v, actionId, event) -> {
            String text = field.getText().toString();
            if (!text.isEmpty()) {
                field.setText("");
                sendText(text);
            }
            return true;
        });
        addView(field);

        mic = new Button(context);
        mic.setText("speak");
        mic.setOnClickListener(v -> toggleRecording());
        addView(mic);

        // Collapsed by default. The bar is used a few times an hour and the
        // desktop behind it all the time, so it must not sit on top of a
        // terminal permanently.
        toggle = new Button(context);
        toggle.setText("\u2328");          // keyboard glyph
        toggle.setOnClickListener(v -> setExpanded(!expanded));
        addView(toggle);

        setExpanded(false);
    }

    private void setExpanded(boolean value) {
        expanded = value;
        field.setVisibility(value ? VISIBLE : GONE);
        mic.setVisibility(value ? VISIBLE : GONE);
        toggle.setText(value ? "\u00d7" : "\u2328");
        setBackgroundColor(value ? Color.argb(190, 20, 20, 24) : Color.TRANSPARENT);

        // ViewGroup.LayoutParams, not LayoutParams: inside a LinearLayout
        // subclass the bare name resolves to LinearLayout.LayoutParams, but our
        // own params come from the parent, which is a FrameLayout. Casting to
        // the inherited name throws ClassCastException on the first tap.
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) {
            lp.width = value ? android.view.ViewGroup.LayoutParams.MATCH_PARENT
                             : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            setLayoutParams(lp);
        }
        if (value) {
            field.requestFocus();
        }
    }

    public void setHost(String host) {
        this.host = host;
    }

    // ----------------------------------------------------------------- typing

    private void sendText(String text) {
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, VOICE_PORT), 3000);
                OutputStream out = socket.getOutputStream();
                out.write(("text\n" + text).getBytes("UTF-8"));
                out.flush();
                // The host inserts on end of stream, so the write side must be
                // closed rather than merely flushed.
                socket.shutdownOutput();
                socket.getInputStream().read();
            } catch (IOException e) {
                Log.w(TAG, "cannot send text: " + e.getMessage());
            }
        }, "send-text").start();
    }

    // --------------------------------------------------------------- dictation

    private void toggleRecording() {
        if (recording) {
            recording = false;
            mic.setText("speak");
            return;
        }
        recording = true;
        mic.setText("stop");
        new Thread(this::record, "record").start();
    }

    private void record() {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            Log.e(TAG, "microphone unavailable");
            stopUi();
            return;
        }

        AudioRecord recorder;
        try {
            // VOICE_RECOGNITION rather than MIC: it applies the noise handling
            // meant for speech and skips effects meant for music.
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4);
        } catch (SecurityException e) {
            Log.e(TAG, "no permission to record audio");
            stopUi();
            return;
        }

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "recorder did not initialise");
            recorder.release();
            stopUi();
            return;
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[minBuffer];
        recorder.startRecording();
        while (recording) {
            int n = recorder.read(buffer, 0, buffer.length);
            if (n > 0) captured.write(buffer, 0, n);
        }
        recorder.stop();
        recorder.release();

        byte[] pcm = captured.toByteArray();
        Log.i(TAG, "captured " + (pcm.length / (SAMPLE_RATE * 2.0)) + "s of audio");
        showStatus("recognising\u2026");
        sendAudio(pcm);
        stopUi();
    }

    private void sendAudio(byte[] pcm) {
        if (pcm.length == 0) return;
        showStatus("recognising\u2026");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, VOICE_PORT), 3000);
            OutputStream out = socket.getOutputStream();
            out.write(("pcm " + SAMPLE_RATE + " 1\n").getBytes());
            out.write(pcm);
            out.flush();
            socket.shutdownOutput();

            // Recognition takes seconds, and silence for seconds is
            // indistinguishable from a broken feature.
            socket.setSoTimeout(60000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String text = in.readLine();
            Log.i(TAG, "recognised: " + text);
            showStatus(text == null || text.isEmpty() ? "nothing recognised" : text);
        } catch (IOException e) {
            Log.w(TAG, "cannot send audio: " + e.getMessage());
            showStatus("recognition failed");
        }
    }

    /**
     * Shows what came back where the user is already looking.
     *
     * The text is put in the hint rather than in the field: the host has
     * already inserted it at the cursor, and text sitting in the field invites
     * pressing Enter, which would insert it a second time.
     */
    private void showStatus(String text) {
        post(() -> field.setHint(text));
    }

    private void stopUi() {
        recording = false;
        post(() -> mic.setText("speak"));
    }
}
