package dev.butschster.linuxvr.panel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One window per host monitor.
 *
 * The first instance asks the host how many monitors exist and launches one
 * more instance per extra monitor. Discovering it at runtime is the only way to
 * be right on every desk — a fixed set of launcher entries shows four to
 * someone with one screen, and misses the fourth screen of someone with five.
 */
public class PanelActivity extends Activity {

    private static final String TAG = "linux-vr";
    private static final String HOST_FILE = "host.txt";
    private static final String EXTRA_MONITOR = "monitor";
    private static final int INPUT_PORT = 9101;

    // Discovery must happen once per process, not once per onCreate. A window
    // that is recreated — a resize, a configuration change, the shell rebuilding
    // it — would otherwise decide it is the first instance again and open
    // another full set of windows, and each new set disconnects the previous
    // one's streams.
    private static final AtomicBoolean discovered = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A desktop that dims after a minute of reading is useless.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String host = readHost();
        int monitor = getIntent().getIntExtra(EXTRA_MONITOR, 0);
        boolean isFirst = !getIntent().hasExtra(EXTRA_MONITOR);

        Log.i(TAG, "window for monitor " + monitor + (isFirst ? " (first)" : ""));

        StreamView view = new StreamView(this);
        view.configure(host, monitor);

        // Nothing but the desktop here. The controls live in their own window,
        // opened alongside the monitor windows below.
        setContentView(view);

        if (isFirst && !host.isEmpty() && discovered.compareAndSet(false, true)) {
            // Off the UI thread: this talks to the host.
            new Thread(() -> openWindowsForOtherMonitors(host), "discover").start();
        }
    }

    /**
     * Asks the host for its monitor list and opens a window for each one beyond
     * the first. The first monitor is already this window.
     */
    private void openWindowsForOtherMonitors(String host) {
        List<Integer> indices = queryMonitors(host);
        if (indices.size() <= 1) {
            Log.i(TAG, "host reports " + indices.size() + " monitor(s), one window is enough");
            openControlWindow();
            return;
        }

        Log.i(TAG, "host reports " + indices.size() + " monitors, opening the rest");
        openControlWindow();
        for (int i = 1; i < indices.size(); i++) {
            final int monitor = indices.get(i);
            Intent intent = new Intent(this, PanelActivity.class);
            intent.putExtra(EXTRA_MONITOR, monitor);
            // NEW_DOCUMENT plus MULTIPLE_TASK is what makes the shell open a
            // separate window instead of raising the existing one.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            runOnUiThread(() -> {
                Log.i(TAG, "opening window for monitor " + monitor);
                startActivity(intent);
            });
            sleep(700);   // give the shell time to place each window
        }
    }

    private void openControlWindow() {
        Intent intent = new Intent(this, ControlActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        runOnUiThread(() -> {
            Log.i(TAG, "opening the control window");
            startActivity(intent);
        });
        sleep(700);
    }

    private List<Integer> queryMonitors(String host) {
        List<Integer> indices = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, INPUT_PORT), 3000);
            OutputStream out = socket.getOutputStream();
            out.write("list\n".getBytes());
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("end")) break;
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && parts[0].equals("monitor")) {
                    indices.add(Integer.parseInt(parts[1]));
                }
            }
        } catch (IOException | NumberFormatException e) {
            Log.w(TAG, "cannot ask the host for its monitors: " + e.getMessage());
        }
        return indices;
    }

    /**
     * The host address comes from a file rather than a rebuild:
     *
     *   adb push host.txt \
     *       /sdcard/Android/data/dev.butschster.linuxvr.panel/files/
     *
     * Push it, do not `adb shell echo >` it: the latter creates the file mode
     * 660 owned by shell, and the app cannot read it.
     */
    /** Shared with ControlActivity, which needs the same address. */
    static String readHostFrom(android.content.Context context) {
        java.io.File dir = context.getExternalFilesDir(null);
        if (dir == null) return "";
        java.io.File file = new java.io.File(dir, HOST_FILE);
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private String readHost() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return "";

        File file = new File(dir, HOST_FILE);
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            String host = line == null ? "" : line.trim();
            Log.i(TAG, host.isEmpty() ? "host.txt is empty" : "streaming from " + host);
            return host;
        } catch (IOException e) {
            Log.e(TAG, "no " + HOST_FILE + " in " + dir);
            return "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
