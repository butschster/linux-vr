package dev.butschster.linuxvr.term;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * The terminal window: the grid on top, the bar that follows it underneath.
 *
 * <p>The bar is docked rather than floating. Over the streamed desktop it had to be its
 * own window, because the pixels underneath belonged to Ubuntu and anything on top of
 * them covered work. Here the window is ours, so the bar takes space instead of taking
 * away the view.
 */
public class TermActivity extends Activity implements HostSession.Listener, ContextBar.Host {

    private static final String TAG = "linux-vr";
    private static final int TERM_PORT = 9103;

    /**
     * Default cell height in px. From docs/readability.md: comfort begins near 0.39° per
     * glyph, which on a panel of this size lands around 20 px — and unlike the streamed
     * desktop, nothing downscales it afterwards.
     */
    private static final int DEFAULT_FONT_PX = 20;

    private TermView term;
    private ContextBar bar;
    private HostSession session;
    private Dictation dictation;
    private String host;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        host = readHost();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 17, 21));

        term = new TermView(this, DEFAULT_FONT_PX);
        root.addView(term, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bar = new ContextBar(this, this);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        if (host == null) {
            bar.setStatus("no host.txt — see README");
            Toast.makeText(this, "push host.txt with the host address", Toast.LENGTH_LONG).show();
            return;
        }

        session = new HostSession(host, TERM_PORT, this);
        term.attach(session);
        dictation = new Dictation(host, new DictationListener());

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        term.requestFocus();
        bar.setStatus("connecting to " + host + "…");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) session.close();
    }

    /**
     * The host address, from the app's own files directory.
     *
     * <p>Push it with {@code adb push}, not {@code adb shell echo >}: the latter creates
     * the file mode 660 owned by {@code shell} and the app cannot read it.
     */
    private String readHost() {
        File file = new File(getExternalFilesDir(null), "host.txt");
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line = in.readLine();
            return line == null || line.trim().isEmpty() ? null : line.trim();
        } catch (Exception e) {
            Log.w(TAG, "no host.txt at " + file);
            return null;
        }
    }

    // -------------------------------------------------------- HostSession.Listener

    @Override
    public void onTextChanged() {
        term.onOutput();
    }

    @Override
    public void onContext(JSONObject context) {
        bar.setContext(context);
    }

    @Override
    public void onStatus(String message, boolean connected) {
        bar.setStatus(message);
        if (connected) term.logState();
    }

    // ------------------------------------------------------------ ContextBar.Host

    @Override
    public void onAction(String send, boolean enter) {
        // Typing without running is the default for anything you would want to add
        // words to — a skill invocation, a command with a path still to come.
        if (enter) term.sendLine(send);
        else term.send(send);
        term.requestFocus();
    }

    @Override
    public void onKeyboard() {
        term.showKeyboard();
    }

    @Override
    public void onFontStep(int delta) {
        term.setFontSize(term.getFontSize() + delta);
        bar.setStatus(term.getFontSize() + " px  ·  " + term.getColumns() + "×" + term.getRows());
    }

    @Override
    public void onScroll(int rows) {
        term.scrollRows(rows);
    }

    @Override
    public void onDictate() {
        if (dictation == null || dictation.isRecording()) return;
        bar.showRecording();
        dictation.start();
    }

    @Override
    public void onStopDictating() {
        if (dictation != null) dictation.stop();
    }

    private class DictationListener implements Dictation.Listener {
        @Override
        public void onLevel(float level) {
            bar.pushLevel(level);
        }

        @Override
        public void onElapsed(long millis) {
            bar.setTimer(String.format(Locale.US, "%d:%02d",
                    millis / 60000, (millis / 1000) % 60));
        }

        @Override
        public void onRecognising() {
            bar.showRecognising();
        }

        @Override
        public void onResult(String text, String problem) {
            bar.hideRecording();
            if (text != null) {
                // Straight into the pty, not submitted: a misheard word is fixed on
                // the line it landed on, and pressing Enter stays a decision.
                term.send(text);
                term.requestFocus();
            } else {
                bar.setStatus(problem == null ? "nothing recognised" : problem);
            }
        }
    }

}
