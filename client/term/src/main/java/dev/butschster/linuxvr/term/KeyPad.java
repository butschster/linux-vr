package dev.butschster.linuxvr.term;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

/**
 * The keys that never move.
 *
 * <p>This is not an accelerator strip — it is the keyboard. The Quest's own on-screen
 * keyboard has no Escape and no Control, so without these keys a terminal cannot be
 * used at all, and FR-17 says they must always be one press away.
 *
 * <p>Which means they must obey keyboard rules: a key never moves, never disappears and
 * never changes meaning. It is built once, here, in a grid of fixed slots, and the
 * context that rebuilds the rest of the bar four times a second never touches it. The
 * previous version placed these keys last in a list whose earlier groups changed length,
 * so typing `claude` moved Enter and ^C to a different row — the exact failure the Touch
 * Bar is remembered for, committed against the keys that matter most.
 *
 * <p>Everything here is universally meaningful. Nothing tool-specific is allowed in, or
 * slots would have to empty out and the grid would reshuffle again.
 */
public class KeyPad extends LinearLayout {

    /** What a key does when pressed: send these bytes to the pty. */
    public interface Send {
        void send(String bytes);
    }

    private static final String ESC = "\u001b";

    private final Send send;
    private final Buttons buttons;

    public KeyPad(Context context, Buttons buttons, Send send) {
        super(context);
        this.send = send;
        this.buttons = buttons;
        setOrientation(VERTICAL);

        // Four columns. Escape and the two ways to kill something sit on the top
        // row; Enter takes the full width at the bottom, where two of its four
        // error directions are the window edge and a miss costs nothing.
        addView(row(
                key("Esc", ESC, Buttons.WARN, "cancel, everywhere"),
                key("^C", "\u0003", Buttons.WARN, "interrupt"),
                key("^D", "\u0004", Buttons.WARN, "end of input — closes a shell"),
                key("^L", "\u000c", Buttons.KEY, "clear the screen")));

        addView(row(
                key("Tab", "\t", Buttons.KEY, "complete"),
                key("\u232b", "\u007f", Buttons.KEY, "backspace"),
                key("^W", "\u0017", Buttons.KEY, "delete the word before the cursor"),
                key("^U", "\u0015", Buttons.KEY, "delete to the start of the line")));

        // Arrows in reading order rather than as a cross: a cross needs a column
        // of its own and this grid has no room to spare.
        addView(row(
                repeating("←", ESC + "[D", Buttons.KEY, "left"),
                repeating("↓", ESC + "[B", Buttons.KEY, "down"),
                repeating("↑", ESC + "[A", Buttons.KEY, "up — previous command"),
                repeating("→", ESC + "[C", Buttons.KEY, "right")));

        addView(row(key("Enter", "\r", Buttons.ENTER, "run it")));
    }

    private View key(String label, String bytes, int style, String hint) {
        return buttons.key(label, style, hint, v -> send.send(bytes));
    }

    /** Arrows and paging: one press per line makes a list unusable. */
    private View repeating(String label, String bytes, int style, String hint) {
        View view = buttons.key(label, style, hint, v -> send.send(bytes));
        buttons.repeatOnHold(view, () -> send.send(bytes));
        return view;
    }

    private LinearLayout row(View... cells) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        for (View cell : cells) {
            LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(buttons.gap() / 2, buttons.gap() / 2,
                    buttons.gap() / 2, buttons.gap() / 2);
            row.addView(cell, params);
        }
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        setLayoutParams(params);
        return row;
    }
}
