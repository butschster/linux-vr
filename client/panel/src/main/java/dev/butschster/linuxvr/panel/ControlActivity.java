package dev.butschster.linuxvr.panel;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * Keyboard, dictation and keys, in a window of their own.
 *
 * They used to overlay the desktop, which meant permanently covering a strip of
 * a screen whose readability was measured to the degree, for controls used a few
 * times an hour. A separate window costs nothing: the shell already places,
 * resizes and stacks windows, so the user puts this one wherever it does not get
 * in the way — which is not something an overlay can offer.
 */
public class ControlActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        InputBar bar = new InputBar(this);
        bar.setHost(PanelActivity.readHostFrom(this));
        bar.setAlwaysOpen(true);
        setContentView(bar);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }
}
