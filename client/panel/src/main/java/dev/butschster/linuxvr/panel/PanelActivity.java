package dev.butschster.linuxvr.panel;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class PanelActivity extends Activity {

    private static final String TAG = "linux-vr";
    private static final String HOST_FILE = "host.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A desktop that dims after a minute of reading is useless.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        StreamView view = new StreamView(this);
        view.setHost(readHost());
        setContentView(view);
    }

    /**
     * The host address comes from a file rather than a rebuild:
     *
     *   adb shell "echo 192.168.1.10 > \
     *       /sdcard/Android/data/dev.butschster.linuxvr.panel/files/host.txt"
     */
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
}
