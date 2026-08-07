package dev.butschster.linuxvr.term;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

/**
 * How a button in the bar is built, sized and coloured.
 *
 * <p>The sizes here are aimed at a controller ray, not a fingertip. A ray at arm's length
 * carries roughly a degree of combined error at the moment the trigger is pulled — mostly
 * the torque of the pull itself, plus tremor and tracking noise — so a target has to be
 * about twice that across, and the space between targets has to be genuinely dead. The
 * first version used phone-sized buttons with 6dp gaps and missed on both counts.
 *
 * <p>These are derived numbers, not measured ones. {@code docs/readability.md} pins the
 * glyph at 0.39°, but how many dp that is depends on where the shell puts the window, and
 * nothing in this project has measured that yet. Treat them as a starting point to be
 * checked in the headset.
 */
public class Buttons {

    public static final int KEY = 0;
    public static final int DESTINATION = 1;
    public static final int COMMAND = 2;
    public static final int WARN = 3;
    public static final int ENTER = 4;
    public static final int VOICE = 5;

    // Four roles, not eight decorative shades. The previous palette had two greys
    // sixteen units apart, which through pancake lenses is one grey.
    private static final int[] FILL = {
            Color.rgb(58, 60, 70),      // KEY — a literal keystroke
            Color.rgb(52, 58, 74),      // DESTINATION — somewhere to go
            Color.rgb(40, 82, 128),     // COMMAND — a line that runs
            Color.rgb(122, 58, 30),     // WARN — destructive
            Color.rgb(38, 86, 62),      // ENTER
            Color.rgb(52, 118, 84),     // VOICE
    };

    public static final int TEXT = Color.rgb(224, 226, 232);
    public static final int MUTED = Color.rgb(150, 155, 168);

    /** Auto-repeat, for arrows and paging. */
    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 90;

    /** A trigger pull can bounce; a doubled Enter is not undoable. */
    private static final long DEBOUNCE_MS = 250;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Typeface icons;

    private int textDp = 17;
    private int padH = 14;
    private int padV = 12;
    private int minWidth = 64;
    private int minHeight = 54;
    private int gap = 12;

    public Buttons(Context context, Typeface icons) {
        this.context = context;
        this.icons = icons;
    }

    public int gap() {
        return dp(gap);
    }

    // ------------------------------------------------------------------ making

    public TextView key(String label, int style, String hint, View.OnClickListener onClick) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp);
        view.setTypeface(Typeface.MONOSPACE);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        view.setMinWidth(dp(minWidth));
        view.setMinHeight(dp(minHeight));
        view.setBackground(background(FILL[style]));
        view.setClickable(true);
        view.setFocusable(false);
        debounced(view, onClick);
        if (hint != null && !hint.isEmpty()) view.setContentDescription(hint);
        return view;
    }

    public TextView icon(String glyph, int style, String hint, View.OnClickListener onClick) {
        TextView view = key(glyph, style, hint, onClick);
        view.setTypeface(icons);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp + 3);
        return view;
    }

    /**
     * Pressed and hovered states. With no touch and no mouse, the highlight under the ray
     * is the only thing that says where a press would land before it lands there.
     */
    private StateListDrawable background(int fill) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, solid(lighten(fill, 0.35f)));
        states.addState(new int[]{android.R.attr.state_hovered}, solid(lighten(fill, 0.18f)));
        states.addState(new int[]{}, solid(fill));
        return states;
    }

    private GradientDrawable solid(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(dp(8));
        return shape;
    }

    private static int lighten(int colour, float amount) {
        int r = (int) (Color.red(colour) + (255 - Color.red(colour)) * amount);
        int g = (int) (Color.green(colour) + (255 - Color.green(colour)) * amount);
        int b = (int) (Color.blue(colour) + (255 - Color.blue(colour)) * amount);
        return Color.rgb(r, g, b);
    }

    // ---------------------------------------------------------------- behaviour

    private void debounced(View view, View.OnClickListener onClick) {
        final long[] last = {0};
        view.setOnClickListener(v -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - last[0] < DEBOUNCE_MS) return;
            last[0] = now;
            onClick.onClick(v);
        });
    }

    /** Hold to repeat. The first press is the click; the repeat starts after a delay. */
    public void repeatOnHold(View view, Runnable action) {
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            action.run();
            handler.postDelayed(tick[0], REPEAT_INTERVAL_MS);
        };
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.postDelayed(tick[0], REPEAT_DELAY_MS);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(tick[0]);
                    break;
                default:
                    break;
            }
            return false;   // the click still happens
        });
    }

    public int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
