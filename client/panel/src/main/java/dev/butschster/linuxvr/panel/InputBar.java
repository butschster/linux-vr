package dev.butschster.linuxvr.panel;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Gravity;
import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

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

    // Five minutes is both a guard against a forgotten recording and a quality
    // limit: a long clip costs more to transcribe and Whisper splits it into
    // windows anyway, which is where context between sentences gets lost.
    private static final long MAX_RECORDING_MS = 5 * 60 * 1000;

    private final EditText field;
    private final Button mic;
    private final Button toggle;
    private final Button enter;
    private final LevelMeter meter;
    private final TextView timer;
    private volatile long recordingStartedAt = 0;
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
        field.setOnClickListener(v -> showKeyboard());
        addView(field);

        // Takes the field's place while recording: the field is useless then,
        // and the meter needs the width to be readable.
        meter = new LevelMeter(context);
        meter.setLayoutParams(new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        meter.setVisibility(GONE);
        addView(meter);

        timer = new TextView(context);
        timer.setTextColor(Color.rgb(200, 200, 210));
        timer.setPadding(12, 0, 12, 0);
        timer.setVisibility(GONE);
        addView(timer);

        // Three independent controls. Typing and speaking are different modes
        // of work: folding them into one would mean opening a keyboard every
        // time you want to say a sentence.
        toggle = new Button(context);
        toggle.setText("\u2328");
        toggle.setOnClickListener(v -> {
            if (expanded) {
                showKeyboard();     // already open: just raise the keyboard
            } else {
                setExpanded(true);
            }
        });
        addView(toggle);

        mic = new Button(context);
        mic.setText("speak");
        mic.setOnClickListener(v -> toggleRecording());
        addView(mic);

        // Dictation inserts text but cannot submit it. Without this a terminal
        // is unusable from the headset: you can write a command but not run it.
        // Editing keys. Text already on the desktop cannot be pulled into this
        // field — Wayland gives no way to read another window's contents — so
        // the only way to change it is to edit it in place, watching the result
        // in the stream. Without these there was nothing to edit it with.
        addKey("\u2190", "left");
        addKey("\u2192", "right");
        addKey("\u232b", "backspace");
        addKey("\u21e4", "home");
        addKey("\u21e5", "end");

        enter = new Button(context);
        enter.setText("\u23ce");
        enter.setOnClickListener(v -> sendKey("enter"));
        addView(enter);

        setExpanded(false);
    }

    private void addKey(String label, String key) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setPadding(8, 0, 8, 0);
        b.setOnClickListener(v -> sendKey(key));
        addView(b);
    }

    private void sendKey(String name) {
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, VOICE_PORT), 3000);
                OutputStream out = socket.getOutputStream();
                out.write(("key " + name + "\n").getBytes("UTF-8"));
                out.flush();
                socket.shutdownOutput();
                socket.getInputStream().read();
            } catch (IOException e) {
                Log.w(TAG, "cannot send key: " + e.getMessage());
            }
        }, "send-key").start();
    }

    private void setExpanded(boolean value) {
        expanded = value;
        // Only the field hides. The buttons stay: dictation and Enter are
        // wanted without a keyboard on screen.
        field.setVisibility(value ? VISIBLE : GONE);
        setBackgroundColor(value ? Color.argb(190, 20, 20, 24)
                                 : Color.argb(90, 20, 20, 24));

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

    /**
     * In its own window the field is always out, but the keyboard button stays.
     * Focusing a field does not raise the system keyboard on its own, and
     * without a visible way to ask for it the window looks like it does nothing.
     */
    public void setAlwaysOpen(boolean value) {
        if (value) {
            setExpanded(true);
            toggle.setText("\u2328");
        }
    }

    /**
     * Horizon OS raises its keyboard on an explicit request, not merely because
     * a field has focus. Typing happens here and the text is then sent to
     * wherever the focus is on the desktop — a terminal cannot summon this
     * keyboard by being pointed at.
     */
    private void showKeyboard() {
        field.postDelayed(() -> {
            InputMethodManager imm =
                    (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 100);
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
        meter.reset();
        meter.setVisibility(VISIBLE);
        timer.setVisibility(VISIBLE);
        timer.setText("0:00");
        field.setVisibility(GONE);
        recordingStartedAt = System.currentTimeMillis();
        tick();
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
            if (n > 0) {
                captured.write(buffer, 0, n);
                meter.push(rms(buffer, n));
            }
            if (System.currentTimeMillis() - recordingStartedAt > MAX_RECORDING_MS) {
                Log.i(TAG, "recording hit the five minute limit, stopping");
                recording = false;
            }
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
            if (text == null || text.isEmpty()) {
                showStatus("nothing recognised");
            } else {
                // Feedback only. The host has already inserted it where the
                // focus is, which is also where it gets corrected if a word
                // came out wrong — a review step here would double the time
                // every dictation takes.
                showStatus(text);
            }
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

    /** Updates the elapsed time while recording, once a second. */
    private void tick() {
        if (!recording) return;
        long elapsed = System.currentTimeMillis() - recordingStartedAt;
        timer.setText(String.format(Locale.US, "%d:%02d",
                elapsed / 60000, (elapsed / 1000) % 60));
        postDelayed(this::tick, 500);
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

    private void stopUi() {
        recording = false;
        post(() -> {
            mic.setText("speak");
            meter.setVisibility(GONE);
            timer.setVisibility(GONE);
            if (expanded) field.setVisibility(VISIBLE);
        });
    }
}
