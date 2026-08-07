package dev.butschster.linuxvr.term;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The bar, in two halves, and the split is the point.
 *
 * <p>The <b>right</b> half never changes: {@link KeyPad}, built once, plus the client's own
 * controls. The <b>left</b> half is whatever the moment is about — the directories you
 * could cd into at a prompt, the program's own keys once something is running, the
 * project's skills when Claude Code is in front.
 *
 * <p>Nothing on the left is decided here. The host owns the pty, a pty has a foreground
 * process group, so the host reads from /proc what is actually running and sends the
 * buttons with it. Adding a tool is an edit to a Python table, not a new APK.
 *
 * <p>Buttons wrap rather than scroll sideways, so the window's size decides how much is
 * visible: wider shows more per row, taller shows more rows.
 */
public class ContextBar extends LinearLayout {

    public interface Host {
        /** A button was pressed: type this, and press Enter if the host said so. */
        void onAction(String send, boolean enter);

        void onDictate();

        void onStopDictating();

        void onKeyboard();

        void onFontStep(int delta);

        void onScroll(int rows);
    }

    /** Width of the fixed half. Constant, so the keys on it never move. */
    private static final int FIXED_WIDTH_DP = 360;

    private static final int BG = Color.rgb(24, 25, 31);
    private static final int BG_FIXED = Color.rgb(31, 33, 40);

    private static final String ICON_MIC = "\ue029";
    private static final String ICON_KEYBOARD = "\ue312";
    private static final String ICON_STOP = "\ue047";
    private static final String ICON_PAGE_UP = "\ue5d8";
    private static final String ICON_PAGE_DOWN = "\ue5db";

    private final Host host;
    private final Buttons buttons;

    private final TextView where;
    private final TextView what;
    private final FlowLayout dynamic;

    private final LinearLayout recordingRow;
    private final LevelMeter meter;
    private final TextView timer;
    private final TextView note;

    /** Restored when the ray leaves a button whose hint replaced it. */
    private String path = "no terminal";

    public ContextBar(Context context, Host host) {
        super(context);
        this.host = host;
        Typeface icons = Typeface.createFromAsset(context.getAssets(), "MaterialIcons-Regular.ttf");
        this.buttons = new Buttons(context, icons);

        setOrientation(HORIZONTAL);
        setBackgroundColor(BG);

        // ---------------------------------------------------------- left: dynamic

        LinearLayout left = new LinearLayout(context);
        left.setOrientation(VERTICAL);
        left.setPadding(buttons.dp(10), buttons.dp(8), buttons.dp(6), buttons.dp(8));
        addView(left, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        LinearLayout status = new LinearLayout(context);
        status.setOrientation(HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Text only — nothing here is pressable. A status line that can be clicked
        // is a row of accidental targets above the row you were aiming at.
        where = label(path, Buttons.TEXT, 16);
        where.setTypeface(Typeface.MONOSPACE);
        where.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        where.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        status.addView(where, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        what = label("", Buttons.MUTED, 15);
        what.setPadding(buttons.dp(10), 0, 0, 0);
        status.addView(what);

        dynamic = new FlowLayout(context, buttons.gap());
        dynamic.setPadding(0, buttons.dp(6), 0, 0);
        ScrollView dynamicScroll = new ScrollView(context);
        dynamicScroll.addView(dynamic, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        left.addView(dynamicScroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // Dictation replaces the status line rather than adding a row: a row that
        // appears shifts every button under it, at the moment you reach for one.
        recordingRow = new LinearLayout(context);
        recordingRow.setOrientation(HORIZONTAL);
        recordingRow.setGravity(Gravity.CENTER_VERTICAL);
        recordingRow.setVisibility(GONE);
        left.addView(recordingRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        recordingRow.addView(buttons.icon(ICON_STOP, Buttons.WARN, "stop", v -> host.onStopDictating()));
        meter = new LevelMeter(context);
        recordingRow.addView(meter, new LayoutParams(0, buttons.dp(38), 1f));
        timer = label("0:00", Buttons.TEXT, 16);
        timer.setPadding(buttons.dp(12), 0, buttons.dp(6), 0);
        recordingRow.addView(timer);
        note = label("", Buttons.MUTED, 15);
        recordingRow.addView(note);

        // ---------------------------------------------------------- right: fixed

        View divider = new View(context);
        divider.setBackgroundColor(Color.rgb(52, 54, 64));
        addView(divider, new LayoutParams(buttons.dp(1), LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(context);
        right.setOrientation(VERTICAL);
        right.setBackgroundColor(BG_FIXED);
        right.setPadding(buttons.dp(6), buttons.dp(8), buttons.dp(6), buttons.dp(8));
        addView(right, new LayoutParams(buttons.dp(FIXED_WIDTH_DP), LayoutParams.MATCH_PARENT));

        KeyPad pad = new KeyPad(context, buttons, text -> host.onAction(text, false));
        right.addView(pad, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(HORIZONTAL);
        controls.setPadding(0, buttons.dp(10), 0, 0);
        right.addView(controls, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addControl(controls, buttons.icon(ICON_MIC, Buttons.VOICE, "dictate", v -> host.onDictate()));
        addControl(controls, buttons.icon(ICON_KEYBOARD, Buttons.COMMAND, "on-screen keyboard",
                v -> host.onKeyboard()));
        addControl(controls, buttons.key("A\u2212", Buttons.KEY, "smaller text", v -> host.onFontStep(-2)));
        addControl(controls, buttons.key("A+", Buttons.KEY, "larger text", v -> host.onFontStep(2)));
        View pageUp = buttons.icon(ICON_PAGE_UP, Buttons.KEY, "scroll back", v -> host.onScroll(-10));
        buttons.repeatOnHold(pageUp, () -> host.onScroll(-10));
        addControl(controls, pageUp);
        View pageDown = buttons.icon(ICON_PAGE_DOWN, Buttons.KEY, "scroll forward", v -> host.onScroll(10));
        buttons.repeatOnHold(pageDown, () -> host.onScroll(10));
        addControl(controls, pageDown);

        hint(pad);
        hint(controls);
    }

    private void addControl(LinearLayout row, View view) {
        LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        int half = buttons.gap() / 2;
        params.setMargins(half, half, half, half);
        row.addView(view, params);
    }

    // ------------------------------------------------------------------ status

    public void setStatus(String message) {
        path = message;
        where.setText(message);
        what.setText("");
    }

    /** Nothing to drive: no terminal window is in front. */
    public void clearContext() {
        setStatus("no terminal window");
        dynamic.removeAllViews();
    }

    /** Rebuild the left half from what the host reported. The right half is untouched. */
    public void setContext(JSONObject ctx) {
        String tool = ctx.isNull("tool") ? null : ctx.optString("tool");
        String toolName = ctx.optString("tool_name", "");
        JSONObject git = ctx.optJSONObject("git");

        path = ctx.optString("cwd_label", "?");
        where.setText(path);

        StringBuilder right = new StringBuilder();
        if (git != null) {
            right.append("⎇ ").append(git.optString("branch"));
            int dirty = git.optInt("dirty");
            if (dirty > 0) right.append(String.format(Locale.US, " ·%d", dirty));
        }
        if (tool != null) {
            if (right.length() > 0) right.append("   ");
            right.append("▶ ").append(toolName);
        }
        what.setText(right.toString());

        dynamic.removeAllViews();

        JSONArray groups = ctx.optJSONArray("groups");
        if (groups == null) return;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            JSONArray actions = group.optJSONArray("actions");
            if (actions == null || actions.length() == 0) continue;

            // Each group starts on its own row. A divider would be worse: wrapping
            // puts it wherever the row happened to break, which is nowhere useful.
            TextView heading = label(group.optString("name"), Buttons.MUTED, 14);
            heading.setPadding(0, buttons.dp(8), buttons.dp(10), 0);
            heading.setTag(FlowLayout.BREAK);
            dynamic.addView(heading);

            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.optJSONObject(j);
                if (action != null) dynamic.addView(actionButton(action));
            }
        }
        hint(dynamic);
    }

    // -------------------------------------------------------------- dictation

    public void showRecording() {
        recordingRow.setVisibility(VISIBLE);
        meter.setVisibility(VISIBLE);
        timer.setVisibility(VISIBLE);
        meter.reset();
        timer.setText("0:00");
        note.setText("");
    }

    public void showRecognising() {
        meter.setVisibility(GONE);
        timer.setVisibility(GONE);
        note.setText("recognising…");
    }

    public void hideRecording() {
        recordingRow.setVisibility(GONE);
    }

    public void pushLevel(float level) {
        meter.push(level);
    }

    public void setTimer(String text) {
        timer.setText(text);
    }

    public void setNote(String text) {
        note.setText(text);
    }

    // -------------------------------------------------------------- building

    private View actionButton(JSONObject action) {
        String text = action.optString("label");
        String send = action.optString("send");
        boolean enter = action.optBoolean("enter");
        String hint = action.isNull("hint") ? null : action.optString("hint");

        // A button that runs on press and one that only types look identical
        // otherwise, and the difference is /clear wiping a conversation.
        return buttons.key(enter ? text + " ⏎" : text, styleFor(action.optString("style", "key")),
                hint, v -> host.onAction(send, enter));
    }

    private static int styleFor(String style) {
        switch (style) {
            case "up":
            case "dir":
            case "git":
            case "fav":
                return Buttons.DESTINATION;
            case "cmd":
            case "skill":
                return Buttons.COMMAND;
            case "warn":
                return Buttons.WARN;
            default:
                return Buttons.KEY;
        }
    }

    /**
     * Hints on hover, not on long press.
     *
     * <p>Long press used to show them, and it had two faults: a long press cancels the
     * click, so holding slightly too long silently did nothing, and the hint replaced the
     * path permanently — the host only pushes context when the directory or the foreground
     * program changes, so it stayed gone until the next `cd`.
     */
    private void hint(View view) {
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hint(group.getChildAt(i));
            return;
        }
        CharSequence description = view.getContentDescription();
        if (description == null || description.length() == 0) return;
        view.setOnHoverListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_HOVER_ENTER:
                    where.setText(description);
                    break;
                case android.view.MotionEvent.ACTION_HOVER_EXIT:
                    where.setText(path);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private TextView label(String text, int colour, int sizeDp) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
}
