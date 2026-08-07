package dev.butschster.linuxvr;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
 * The remote keyboard: typing, dictation and the editing keys, in one window.
 *
 * It has two states and never both at once. Idle it is a row of keys; recording
 * it is a level meter, a timer and a stop button. Showing everything together
 * was the first attempt and it collapsed: the keys took the width and squeezed
 * the meter to nothing.
 *
 * No custom alphabet keyboard is drawn — a focused text field is all it takes
 * for Horizon OS to raise its own, which handles hands, controllers and layouts
 * better than anything written here would.
 *
 * Editing keys exist because text already on the desktop cannot be pulled into
 * the field: Wayland gives no way to read another window's contents. So editing
 * happens in place, watched through the stream.
 */
public class InputBar extends LinearLayout {

    private static final String TAG = "linux-vr";
    private static final int VOICE_PORT = 9102;
    private static final int SAMPLE_RATE = 16000;   // what Whisper wants anyway

    // Five minutes guards against a forgotten recording, and is also a quality
    // bound: Whisper splits a long clip into windows, losing context across them.
    private static final long MAX_RECORDING_MS = 5 * 60 * 1000;

    private static final int COLOR_KEY = Color.rgb(58, 60, 70);
    private static final int COLOR_ACCENT = Color.rgb(64, 130, 200);
    private static final int COLOR_VOICE = Color.rgb(70, 160, 110);
    private static final int COLOR_STOP = Color.rgb(190, 70, 70);
    // Keys that interrupt something get their own colour: hitting one by
    // accident costs more than hitting an arrow.
    private static final int COLOR_WARN = Color.rgb(180, 120, 50);

    // Material Icons, the standard flat set, as a font rather than a pile of
    // images: one 350 KB file covers every icon and scales without going soft.
    // Codepoints instead of ligatures — a ligature silently renders as the word
    // itself if the font fails to load, and a button reading "keyboard_return"
    // is worse than an obvious blank.
    private static final String ICON_MIC = "\ue029";
    private static final String ICON_KEYBOARD = "\ue312";
    // subdirectory_arrow_left, the classic down-then-left return arrow.
    // keyboard_return was tried first and draws as an undo arrow, which reads
    // as "back" — the opposite of what the key does.
    private static final String ICON_ENTER = "\ue5da";
    private static final String ICON_UP = "\ue5d8";
    private static final String ICON_DOWN = "\ue5db";
    private static final String ICON_LEFT = "\ue5c4";
    private static final String ICON_RIGHT = "\ue5c8";
    private static final String ICON_BACKSPACE = "\ue14a";
    private static final String ICON_STOP = "\ue047";

    private Typeface iconFont;

    private final LinearLayout keys;
    private final LinearLayout recordingRow;
    private final EditText field;
    private final LevelMeter meter;
    private final TextView timer;
    private final ProgressBar spinner;
    private final TextView status;

    private String host = "";
    private boolean keyboardOpen = false;
    private volatile boolean recording = false;
    private volatile long recordingStartedAt = 0;

    public InputBar(Context context) {
        super(context);
        setOrientation(VERTICAL);
        // Centred, so a window taller than the content does not leave the keys
        // stuck to the top with empty rows underneath.
        setGravity(Gravity.CENTER_VERTICAL);
        try {
            iconFont = Typeface.createFromAsset(context.getAssets(), "MaterialIcons-Regular.ttf");
        } catch (RuntimeException e) {
            Log.w(TAG, "icon font missing, falling back to text");
            iconFont = null;
        }
        setBackgroundColor(Color.rgb(24, 25, 30));
        setPadding(dp(10), dp(6), dp(10), dp(6));

        field = new EditText(context);
        field.setHint("type here, Enter sends it to the desktop");
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.argb(140, 255, 255, 255));
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND);
        field.setVisibility(GONE);
        // Both flags are needed: without them the field takes focus but the
        // system never considers it a target for text, and no keyboard appears.
        field.setFocusable(true);
        field.setFocusableInTouchMode(true);
        field.setOnClickListener(v -> showKeyboard());
        field.setOnEditorActionListener((v, actionId, event) -> {
            String text = field.getText().toString();
            if (!text.isEmpty()) {
                field.setText("");
                sendCommand("text\n" + text);
            }
            return true;
        });
        addView(field, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        keys = new LinearLayout(context);
        keys.setOrientation(HORIZONTAL);
        keys.setGravity(Gravity.CENTER);
        addView(keys, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Words where a glyph would be a guess. Arrow keys are obvious as
        // arrows; "home" and "end" as symbols were not, and a control you have
        // to decode is worse than one that spells itself out.
        // Chosen for a terminal and for driving Claude Code, not for writing
        // prose: the letters come from voice or the system keyboard, so what a
        // compact panel owes you is the control keys those cannot send.
        //
        //   Voice     the primary input here
        //   Esc       interrupts Claude Code mid-answer
        //   ^C        interrupts a running command
        //   Tab       completion; Shift+Tab cycles Claude Code's modes
        //   ↑         recalls the previous command, the most used key in a shell
        addIcon(keys, ICON_MIC, COLOR_VOICE, v -> startRecording());
        addIcon(keys, ICON_KEYBOARD, COLOR_ACCENT, v -> openKeyboard());
        addIcon(keys, ICON_ENTER, COLOR_ACCENT, v -> sendKey("enter"));
        // Words, not glyphs: no icon says "escape" or "control-C" without being
        // decoded, and these are the two that interrupt something.
        addKey(keys, "Esc", COLOR_WARN, v -> sendKey("esc"));
        addKey(keys, "^C", COLOR_WARN, v -> sendKey("ctrl+c"));
        addKey(keys, "Tab", COLOR_KEY, v -> sendKey("tab"));
        addKey(keys, "⇧Tab", COLOR_KEY, v -> sendKey("shift+tab"));
        addIcon(keys, ICON_UP, COLOR_KEY, v -> sendKey("up"));
        addIcon(keys, ICON_DOWN, COLOR_KEY, v -> sendKey("down"));
        addIcon(keys, ICON_LEFT, COLOR_KEY, v -> sendKey("left"));
        addIcon(keys, ICON_RIGHT, COLOR_KEY, v -> sendKey("right"));
        addIcon(keys, ICON_BACKSPACE, COLOR_KEY, v -> sendKey("backspace"));

        recordingRow = new LinearLayout(context);
        recordingRow.setOrientation(HORIZONTAL);
        recordingRow.setGravity(Gravity.CENTER_VERTICAL);
        recordingRow.setVisibility(GONE);
        addView(recordingRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        meter = new LevelMeter(context);
        // The meter gets every pixel the row can spare. Sharing the row with
        // eight keys was what made it invisible the first time.
        recordingRow.addView(meter, new LayoutParams(0, dp(32), 1f));

        // Recognition takes seconds. Between pressing stop and seeing text
        // there was no sign anything was happening, which reads as a failure.
        spinner = new ProgressBar(context);
        spinner.setIndeterminate(true);
        spinner.setVisibility(GONE);
        recordingRow.addView(spinner, new LayoutParams(dp(28), dp(28)));

        status = new TextView(context);
        status.setTextColor(Color.rgb(210, 210, 220));
        status.setPadding(dp(10), 0, dp(10), 0);
        status.setVisibility(GONE);
        recordingRow.addView(status, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        timer = new TextView(context);
        timer.setTextColor(Color.rgb(210, 210, 220));
        timer.setPadding(dp(12), 0, dp(12), 0);
        timer.setText("0:00");
        recordingRow.addView(timer);

        addIcon(recordingRow, ICON_STOP, COLOR_STOP, v -> stopRecording());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void addIcon(LinearLayout row, String codepoint, int color, OnClickListener listener) {
        TextView key = addKey(row, codepoint, color, listener);
        if (iconFont != null) key.setTypeface(iconFont);
        key.setTextSize(20);
        key.setPadding(dp(12), dp(6), dp(12), dp(6));
    }

    private TextView addKey(LinearLayout row, String label, int color, OnClickListener listener) {
        TextView key = new TextView(getContext());
        key.setText(label);
        key.setTextColor(Color.WHITE);
        key.setTextSize(15);
        key.setGravity(Gravity.CENTER);
        key.setPadding(dp(14), dp(7), dp(14), dp(7));
        key.setOnClickListener(listener);
        key.setClickable(true);

        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(8));
        key.setBackground(background);

        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(key, lp);
        return key;
    }

    public void setHost(String host) {
        this.host = host;
    }

    /** Kept for the overlay case; in its own window the bar is always out. */
    public void setAlwaysOpen(boolean value) {
        // Nothing to do: the key row is always visible now. The field still
        // waits to be asked for, since it is only useful with a keyboard up.
    }

    // -------------------------------------------------------------- keyboard

    private void openKeyboard() {
        keyboardOpen = !keyboardOpen;
        field.setVisibility(keyboardOpen ? VISIBLE : GONE);
        if (keyboardOpen) {
            field.requestFocus();
            showKeyboard();
        }
    }

    /**
     * Horizon OS raises its keyboard on an explicit request, not merely because
     * a field holds focus. Typing happens here; the text then travels to
     * wherever the focus is on the desktop, which cannot summon this keyboard
     * by being pointed at.
     */
    private void showKeyboard() {
        field.postDelayed(() -> {
            field.requestFocus();
            InputMethodManager imm =
                    (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            // SHOW_IMPLICIT is advisory and the system is free to ignore it,
            // which is exactly what happened. SHOW_FORCED is deprecated but is
            // the only request Horizon OS reliably honours here; toggle is the
            // fallback when even that is refused.
            if (!imm.showSoftInput(field, InputMethodManager.SHOW_FORCED)) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        }, 120);
    }

    // --------------------------------------------------------------- sending

    private void sendKey(String name) {
        sendCommand("key " + name + "\n");
    }

    /** One connection per command; the host acts on end of stream. */
    private void sendCommand(String payload) {
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, VOICE_PORT), 3000);
                OutputStream out = socket.getOutputStream();
                out.write(payload.getBytes("UTF-8"));
                out.flush();
                socket.shutdownOutput();
                socket.getInputStream().read();
            } catch (IOException e) {
                Log.w(TAG, "command failed: " + e.getMessage());
            }
        }, "send").start();
    }

    // ------------------------------------------------------------- dictation

    private void startRecording() {
        if (recording) return;
        recording = true;
        recordingStartedAt = System.currentTimeMillis();

        // One state at a time: keys away, meter out.
        keys.setVisibility(GONE);
        field.setVisibility(GONE);
        recordingRow.setVisibility(VISIBLE);
        meter.reset();
        timer.setText("0:00");
        tick();

        new Thread(this::record, "record").start();
    }

    private void stopRecording() {
        recording = false;   // the recording thread notices and finishes up
        showProcessing();
    }

    /** Meter and timer away, spinner out: the wait is now on recognition. */
    private void showProcessing() {
        post(() -> {
            meter.setVisibility(GONE);
            timer.setVisibility(GONE);
            spinner.setVisibility(VISIBLE);
            status.setText("recognising…");
            status.setVisibility(VISIBLE);
        });
    }

    private void tick() {
        if (!recording) return;
        long elapsed = System.currentTimeMillis() - recordingStartedAt;
        timer.setText(String.format(Locale.US, "%d:%02d",
                elapsed / 60000, (elapsed / 1000) % 60));
        postDelayed(this::tick, 500);
    }

    private void record() {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            Log.e(TAG, "microphone unavailable");
            restoreKeys(null);
            return;
        }

        AudioRecord recorder;
        try {
            // VOICE_RECOGNITION rather than MIC: noise handling meant for
            // speech, without the effects meant for music.
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4);
        } catch (SecurityException e) {
            Log.e(TAG, "no permission to record audio");
            restoreKeys(null);
            return;
        }

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "recorder did not initialise");
            recorder.release();
            restoreKeys(null);
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
                showProcessing();
            }
        }
        recorder.stop();
        recorder.release();

        byte[] pcm = captured.toByteArray();
        Log.i(TAG, "captured " + (pcm.length / (SAMPLE_RATE * 2.0)) + "s of audio");
        restoreKeys(sendAudio(pcm));
    }

    /** Returns the transcript, or a short reason if there is none. */
    private String sendAudio(byte[] pcm) {
        if (pcm.length == 0) return null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, VOICE_PORT), 3000);
            OutputStream out = socket.getOutputStream();
            out.write(("pcm " + SAMPLE_RATE + " 1\n").getBytes());
            out.write(pcm);
            out.flush();
            socket.shutdownOutput();

            // Recognition takes seconds, and seconds of silence are
            // indistinguishable from a broken feature.
            socket.setSoTimeout(60000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String text = in.readLine();
            Log.i(TAG, "recognised: " + text);
            return text == null || text.isEmpty() ? "nothing recognised" : text;
        } catch (IOException e) {
            Log.w(TAG, "cannot send audio: " + e.getMessage());
            return "recognition failed";
        }
    }

    private void restoreKeys(String message) {
        recording = false;
        post(() -> {
            recordingRow.setVisibility(GONE);
            spinner.setVisibility(GONE);
            status.setVisibility(GONE);
            meter.setVisibility(VISIBLE);
            timer.setVisibility(VISIBLE);
            keys.setVisibility(VISIBLE);
            if (message != null) field.setHint(message);
            if (keyboardOpen) field.setVisibility(VISIBLE);
        });
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
