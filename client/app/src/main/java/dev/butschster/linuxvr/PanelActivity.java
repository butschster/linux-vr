package dev.butschster.linuxvr;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

/**
 * One window showing one screen of one host.
 *
 * <p>It used to do more: read the host from a file pushed over adb, ask that host how many
 * monitors it had, and open the rest of the windows itself. All three now belong to the
 * connection manager, and the difference is not tidiness — a window that opens other
 * windows cannot be reopened on its own, and a resize that recreated it opened a second
 * full set.
 */
public class PanelActivity extends Activity {

    private static final String TAG = "linux-vr";

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_MONITOR = "monitor";
    public static final String EXTRA_LABEL = "label";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A desktop that dims after a minute of reading is useless.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String host = getIntent().getStringExtra(EXTRA_HOST);
        int monitor = getIntent().getIntExtra(EXTRA_MONITOR, 0);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        if (host == null) host = "";
        // The shell shows this in the window's own furniture, which is the only
        // thing distinguishing four otherwise identical desktop windows.
        if (label != null && !label.isEmpty()) setTitle(label);

        Log.i(TAG, "window for monitor " + monitor + " on " + host);

        StreamView view = new StreamView(this);
        view.configure(host, monitor);
        setContentView(view);
    }
}
