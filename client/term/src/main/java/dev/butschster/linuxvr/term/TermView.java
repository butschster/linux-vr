package dev.butschster.linuxvr.term;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.view.TerminalRenderer;

/**
 * Draws the terminal and turns everything that happens here into bytes for the host.
 *
 * <p>Termux's own {@code TerminalView} is 1500 lines, most of it text selection, mouse
 * tracking and gesture handling that a headset does not have and does not want. What is
 * reused instead is the part that is genuinely hard — the emulator and the renderer.
 *
 * <p>Font size is in device pixels rather than sp: sp follows a system-wide accessibility
 * setting, and here the size is a measurement result (docs/readability.md) that should
 * not move when an unrelated slider does.
 */
public class TermView extends View {

    private static final String TAG = "linux-vr";

    /** Padding around the grid, in px. Enough that glyphs do not touch the window edge. */
    private static final int PADDING = 8;

    private HostSession session;
    private TerminalRenderer renderer;
    private int fontSize;

    /** 0 at the live edge, negative when scrolled back into the transcript. */
    private int topRow = 0;

    private float dragAnchorY;
    private int dragAnchorRow;

    public TermView(Context context, int fontSize) {
        super(context);
        this.fontSize = fontSize;
        setBackgroundColor(Color.rgb(16, 17, 21));
        setFocusable(true);
        setFocusableInTouchMode(true);
        rebuildRenderer();
    }

    public void attach(HostSession session) {
        this.session = session;
    }

    private void rebuildRenderer() {
        renderer = new TerminalRenderer(fontSize, Typeface.MONOSPACE);
    }

    public int getFontSize() {
        return fontSize;
    }

    /** Changing the font changes how many columns fit, so the host is told the new size. */
    public void setFontSize(int size) {
        fontSize = Math.max(10, Math.min(64, size));
        rebuildRenderer();
        applySize();
        invalidate();
    }

    public int getColumns() {
        return Math.max(4, (int) ((getWidth() - 2 * PADDING) / renderer.getFontWidth()));
    }

    public int getRows() {
        return Math.max(2, (getHeight() - 2 * PADDING) / renderer.getFontLineSpacing());
    }

    private void applySize() {
        if (session == null || getWidth() == 0 || getHeight() == 0) return;
        session.updateSize(getColumns(), getRows(),
                (int) renderer.getFontWidth(), renderer.getFontLineSpacing());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applySize();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        TerminalEmulator emulator = session == null ? null : session.getEmulator();
        if (emulator == null) return;
        canvas.save();
        canvas.translate(PADDING, PADDING);
        renderer.render(emulator, canvas, topRow, -1, -1, -1, -1);
        canvas.restore();
    }

    /** New output arrived: jump back to the live edge, the way a terminal behaves. */
    public void onOutput() {
        if (topRow != 0) topRow = 0;
        invalidate();
    }

    public void scrollRows(int rows) {
        TerminalEmulator emulator = session == null ? null : session.getEmulator();
        if (emulator == null) return;
        int limit = -emulator.getScreen().getActiveTranscriptRows();
        topRow = Math.max(limit, Math.min(0, topRow + rows));
        invalidate();
    }

    // -------------------------------------------------------------------- input

    /**
     * Dragging scrolls the transcript. There is no text selection and no mouse
     * reporting: in the headset the pointer is a ray, and a ray is good at pressing
     * things and bad at sweeping precisely across characters.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragAnchorY = event.getY();
                dragAnchorRow = topRow;
                requestFocus();
                return true;
            case MotionEvent.ACTION_MOVE:
                int moved = (int) ((event.getY() - dragAnchorY) / renderer.getFontLineSpacing());
                TerminalEmulator emulator = session == null ? null : session.getEmulator();
                if (emulator != null) {
                    int limit = -emulator.getScreen().getActiveTranscriptRows();
                    topRow = Math.max(limit, Math.min(0, dragAnchorRow - moved));
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }

    /** The scroll wheel of the controller, where the shell sends one. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            scrollRows((int) (-event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 3));
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    public void showKeyboard() {
        requestFocus();
        postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            // SHOW_IMPLICIT is advisory and Horizon OS ignores it; SHOW_FORCED is
            // deprecated and is the only request it honours. Same finding as the
            // panel client, same workaround.
            if (!imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        }, 100);
    }

    public void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo attrs) {
        // TYPE_NULL is what a terminal wants: no autocorrect, no suggestions, no
        // composing state to reconcile. Keyboards that ignore it still deliver
        // characters through commitText, which is handled below.
        attrs.inputType = InputType.TYPE_NULL;
        attrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;

        return new BaseInputConnection(this, true) {
            @Override
            public boolean finishComposingText() {
                super.finishComposingText();
                flush();
                return true;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                super.commitText(text, newCursorPosition);
                flush();
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int leftLength, int rightLength) {
                for (int i = 0; i < leftLength; i++) send("\u007f");
                return super.deleteSurroundingText(leftLength, rightLength);
            }

            private void flush() {
                Editable content = getEditable();
                if (content == null || content.length() == 0) return;
                send(content.toString());
                content.clear();
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (session == null || session.getEmulator() == null) return super.onKeyDown(keyCode, event);
        TerminalEmulator emulator = session.getEmulator();

        int mods = 0;
        if (event.isCtrlPressed()) mods |= KeyHandler.KEYMOD_CTRL;
        if (event.isAltPressed()) mods |= KeyHandler.KEYMOD_ALT;
        if (event.isShiftPressed()) mods |= KeyHandler.KEYMOD_SHIFT;

        String code = KeyHandler.getCode(keyCode, mods, emulator.isCursorKeysApplicationMode(),
                emulator.isKeypadApplicationMode());
        if (code != null) {
            send(code);
            return true;
        }

        int codePoint = event.getUnicodeChar(event.isShiftPressed() ? KeyEvent.META_SHIFT_ON : 0);
        if (codePoint == 0) return super.onKeyDown(keyCode, event);

        if (event.isCtrlPressed()) {
            // Ctrl+letter is the control character, which is what every shell and
            // every editor is actually bound to.
            int lower = Character.toLowerCase(codePoint);
            if (lower >= 'a' && lower <= 'z') codePoint = lower - 'a' + 1;
            else if (lower == ' ' || lower == '@') codePoint = 0;
        }
        session.writeCodePoint(event.isAltPressed(), codePoint);
        return true;
    }

    /** Send text as typed; anything the bar produces goes through here too. */
    public void send(String text) {
        if (session == null || text.isEmpty()) return;
        session.write(text);
        if (topRow != 0) {
            topRow = 0;
            invalidate();
        }
    }

    public void sendLine(String text) {
        send(text + "\r");
    }

    public void logState() {
        TerminalEmulator emulator = session == null ? null : session.getEmulator();
        Log.i(TAG, "grid " + getColumns() + "x" + getRows()
                + " font " + fontSize + "px cell " + renderer.getFontWidth()
                + "x" + renderer.getFontLineSpacing()
                + (emulator == null ? " (no emulator)" : ""));
    }
}
