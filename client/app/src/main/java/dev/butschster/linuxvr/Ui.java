package dev.butschster.linuxvr;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * How anything clickable in this app is sized and coloured.
 *
 * <p>Sized for a controller ray rather than a fingertip: at arm's length the ray carries
 * roughly a degree of error at the moment the trigger is pulled, so targets need room and
 * the space between them has to be genuinely dead.
 *
 * <p>But not as much room as the arithmetic asks for. A derivation from that error budget
 * wanted ~100dp cells; in the headset that read as oversized, and the person wearing it is
 * the measurement. These numbers are the middle, carried over from the terminal client
 * where they were settled. Adjust them here and nowhere else.
 */
public class Ui {

    public static final int BG = Color.rgb(16, 17, 21);
    public static final int CARD = Color.rgb(30, 32, 39);
    public static final int TEXT = Color.rgb(228, 230, 236);
    public static final int MUTED = Color.rgb(138, 143, 158);

    public static final int COMMAND = Color.rgb(38, 74, 120);
    public static final int NEUTRAL = Color.rgb(48, 51, 62);
    public static final int WARN = Color.rgb(120, 54, 48);
    public static final int LIVE = Color.rgb(34, 62, 48);

    private final Context context;

    public Ui(Context context) {
        this.context = context;
    }

    public int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** A button. The hint is what the shell shows on hover, so it is free to be long. */
    public TextView button(String label, int colour, String hint, View.OnClickListener onClick) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(Typeface.MONOSPACE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(10), dp(16), dp(10));
        view.setMinWidth(dp(46));
        view.setMinHeight(dp(40));
        view.setBackground(background(colour));
        view.setClickable(true);
        if (hint != null) view.setTooltipText(hint);
        view.setOnClickListener(onClick);
        return view;
    }

    public TextView text(String value, int colour, int sizeDp) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        return view;
    }

    public TextView mono(String value, int colour, int sizeDp) {
        TextView view = text(value, colour, sizeDp);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    public GradientDrawable rounded(int colour, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    /**
     * Pressed and hovered states. With no touch and no mouse, the highlight under the ray
     * is the only thing that says where a press would land before it lands there.
     */
    public StateListDrawable background(int colour) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(lighten(colour, 0.24f), 8));
        states.addState(new int[]{android.R.attr.state_hovered}, rounded(lighten(colour, 0.14f), 8));
        states.addState(new int[]{}, rounded(colour, 8));
        return states;
    }

    public static int lighten(int colour, float amount) {
        return Color.rgb(
                (int) (Color.red(colour) + (255 - Color.red(colour)) * amount),
                (int) (Color.green(colour) + (255 - Color.green(colour)) * amount),
                (int) (Color.blue(colour) + (255 - Color.blue(colour)) * amount));
    }
}
