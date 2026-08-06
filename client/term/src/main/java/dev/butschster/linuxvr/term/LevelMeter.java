package dev.butschster.linuxvr.term;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * A rolling bar graph of how loud the microphone is right now.
 *
 * Between pressing record and seeing text there are several seconds of silence,
 * during which a working microphone and a dead one look identical. The meter
 * makes the difference visible without waiting for the transcript: bars move,
 * it hears you; bars flat, something is wrong before recognition is even
 * involved.
 */
public class LevelMeter extends View {

    private static final int BARS = 32;

    private final float[] levels = new float[BARS];
    private int head = 0;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LevelMeter(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Called from the recording thread with 0..1; drawing is posted to the UI. */
    public void push(float level) {
        levels[head] = Math.min(Math.max(level, 0f), 1f);
        head = (head + 1) % BARS;
        postInvalidate();
    }

    public void reset() {
        for (int i = 0; i < BARS; i++) levels[i] = 0f;
        head = 0;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float slot = w / (float) BARS;
        final float barWidth = Math.max(2f, slot * 0.6f);
        final float centre = h / 2f;

        for (int i = 0; i < BARS; i++) {
            // Oldest on the left, newest on the right, so the graph reads the
            // way a waveform is expected to.
            float level = levels[(head + i) % BARS];

            // A quiet room sits near zero and speech barely lifts it, so the
            // scale is compressed — this is a presence indicator, not a meter.
            float shown = (float) Math.sqrt(level);
            float half = Math.max(1.5f, shown * (h * 0.30f));

            paint.setColor(shown > 0.06f ? Color.rgb(120, 220, 140)
                                         : Color.argb(120, 160, 160, 170));
            float x = i * slot + (slot - barWidth) / 2f;
            canvas.drawRoundRect(x, centre - half, x + barWidth, centre + half,
                                 barWidth / 2f, barWidth / 2f, paint);
        }
    }
}
