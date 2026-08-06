package dev.butschster.linuxvr.term;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The bar that follows what is running.
 *
 * <p>At a shell prompt it is a way to get somewhere: the directory above, the directories
 * inside, and the projects opened most often at the desk. Once a program is in front it is
 * that program's keys instead — Escape and mode switching for Claude Code, {@code :w} and
 * {@code :q} for vim, {@code q} and paging for a pager.
 *
 * <p>None of those lists are decided here. The host owns the pty, a pty has a foreground
 * process group, so the host reads from /proc what is actually running and sends the
 * buttons with it. This class draws what it is given. That split is deliberate: adding a
 * tool is an edit to a Python table, not a new APK, and a bar that guessed wrong would be
 * worse than a fixed one — keys that move at the moment you reach for them.
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

    private static final int BG = Color.rgb(24, 25, 31);
    private static final int TEXT = Color.rgb(224, 226, 232);
    private static final int MUTED = Color.rgb(140, 145, 158);

    private static final int C_KEY = Color.rgb(58, 60, 70);
    private static final int C_DIR = Color.rgb(52, 58, 74);
    private static final int C_GIT = Color.rgb(46, 84, 66);
    private static final int C_FAV = Color.rgb(74, 60, 96);
    private static final int C_UP = Color.rgb(64, 130, 200);
    private static final int C_CMD = Color.rgb(40, 82, 128);
    private static final int C_SKILL = Color.rgb(42, 96, 96);
    private static final int C_WARN = Color.rgb(160, 88, 48);
    private static final int C_VOICE = Color.rgb(70, 160, 110);
    private static final int C_STOP = Color.rgb(190, 70, 70);

    // Material Icons are a font: these are codepoints, not ligatures. Verified
    // present in the shipped file — a missing glyph draws as a blank box, and in
    // the headset that is indistinguishable from a broken button.
    private static final String ICON_MIC = "\ue029";
    private static final String ICON_KEYBOARD = "\ue312";
    private static final String ICON_STOP = "\ue047";
    private static final String ICON_UP = "\ue5d8";
    private static final String ICON_DOWN = "\ue5db";

    private final Host host;
    private final Typeface icons;

    private final TextView where;
    private final TextView what;
    private final LinearLayout primary;
    private final LinearLayout secondary;
    private final HorizontalScrollView primaryScroll;
    private final HorizontalScrollView secondaryScroll;

    private final LinearLayout recordingRow;
    private final LevelMeter meter;
    private final TextView timer;
    private final TextView note;

    public ContextBar(Context context, Host host) {
        super(context);
        this.host = host;
        this.icons = Typeface.createFromAsset(context.getAssets(), "MaterialIcons-Regular.ttf");

        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        setPadding(dp(6), dp(4), dp(6), dp(6));

        // --- status line: where we are, what is in front, and the global keys

        LinearLayout status = new LinearLayout(context);
        status.setOrientation(HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        where = label("connecting…", TEXT, 15);
        where.setTypeface(Typeface.MONOSPACE);
        status.addView(where);

        what = label("", MUTED, 14);
        what.setPadding(dp(10), 0, dp(10), 0);
        status.addView(what);

        View spacer = new View(context);
        status.addView(spacer, new LayoutParams(0, 1, 1f));

        status.addView(iconButton(ICON_UP, C_KEY, v -> host.onScroll(-10)));
        status.addView(iconButton(ICON_DOWN, C_KEY, v -> host.onScroll(10)));
        status.addView(textButton("A\u2212", C_KEY, v -> host.onFontStep(-2)));
        status.addView(textButton("A+", C_KEY, v -> host.onFontStep(2)));
        status.addView(iconButton(ICON_KEYBOARD, C_CMD, v -> host.onKeyboard()));
        status.addView(iconButton(ICON_MIC, C_VOICE, v -> host.onDictate()));

        // --- two rows of buttons, both scrollable sideways

        primary = new LinearLayout(context);
        primary.setOrientation(HORIZONTAL);
        primaryScroll = new HorizontalScrollView(context);
        primaryScroll.setHorizontalScrollBarEnabled(false);
        primaryScroll.addView(primary);
        addView(primaryScroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        secondary = new LinearLayout(context);
        secondary.setOrientation(HORIZONTAL);
        secondaryScroll = new HorizontalScrollView(context);
        secondaryScroll.setHorizontalScrollBarEnabled(false);
        secondaryScroll.addView(secondary);
        addView(secondaryScroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // --- dictation takes the same space rather than adding any

        recordingRow = new LinearLayout(context);
        recordingRow.setOrientation(HORIZONTAL);
        recordingRow.setGravity(Gravity.CENTER_VERTICAL);
        recordingRow.setVisibility(GONE);
        addView(recordingRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        recordingRow.addView(iconButton(ICON_STOP, C_STOP, v -> host.onStopDictating()));
        meter = new LevelMeter(context);
        recordingRow.addView(meter, new LayoutParams(0, dp(34), 1f));
        timer = label("0:00", TEXT, 15);
        timer.setPadding(dp(10), 0, dp(6), 0);
        recordingRow.addView(timer);
        note = label("", MUTED, 14);
        recordingRow.addView(note);
    }

    // ------------------------------------------------------------------ status

    public void setStatus(String message) {
        where.setText(message);
    }

    /** Rebuild the buttons from what the host reported. */
    public void setContext(JSONObject ctx) {
        String cwd = ctx.optString("cwd_label", "?");
        String tool = ctx.isNull("tool") ? null : ctx.optString("tool");
        String toolName = ctx.optString("tool_name", "");
        JSONObject git = ctx.optJSONObject("git");

        where.setText(cwd);
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

        primary.removeAllViews();
        secondary.removeAllViews();

        JSONArray groups = ctx.optJSONArray("groups");
        if (groups == null) return;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            JSONArray actions = group.optJSONArray("actions");
            if (actions == null || actions.length() == 0) continue;
            // The first group is what the moment is about — the program's keys, or
            // where you can go. Everything else shares the row below it.
            LinearLayout row = i == 0 ? primary : secondary;
            if (row.getChildCount() > 0) row.addView(divider());
            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.optJSONObject(j);
                if (action != null) row.addView(actionButton(action));
            }
        }
        primaryScroll.scrollTo(0, 0);
        secondaryScroll.scrollTo(0, 0);
    }

    // -------------------------------------------------------------- dictation

    public void showRecording() {
        primaryScroll.setVisibility(GONE);
        secondaryScroll.setVisibility(GONE);
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
        primaryScroll.setVisibility(VISIBLE);
        secondaryScroll.setVisibility(VISIBLE);
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
        int colour = colourFor(action.optString("style", "key"));

        TextView button = label(text, TEXT, 15);
        button.setTypeface(Typeface.MONOSPACE);
        style(button, colour);
        button.setOnClickListener(v -> host.onAction(send, enter));
        String hint = action.isNull("hint") ? null : action.optString("hint");
        if (hint != null && !hint.isEmpty()) {
            button.setOnLongClickListener(v -> {
                setStatus(hint);
                return true;
            });
        }
        return button;
    }

    private static int colourFor(String style) {
        switch (style) {
            case "up": return C_UP;
            case "dir": return C_DIR;
            case "git": return C_GIT;
            case "fav": return C_FAV;
            case "cmd": return C_CMD;
            case "skill": return C_SKILL;
            case "warn": return C_WARN;
            default: return C_KEY;
        }
    }

    private TextView textButton(String text, int colour, OnClickListener onClick) {
        TextView button = label(text, TEXT, 15);
        style(button, colour);
        button.setOnClickListener(onClick);
        return button;
    }

    private TextView iconButton(String glyph, int colour, OnClickListener onClick) {
        TextView button = label(glyph, TEXT, 18);
        button.setTypeface(icons);
        style(button, colour);
        button.setOnClickListener(onClick);
        return button;
    }

    private void style(TextView view, int colour) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(colour);
        background.setCornerRadius(dp(6));
        view.setBackground(background);
        view.setPadding(dp(10), dp(5), dp(10), dp(5));
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        view.setLayoutParams(params);
        view.setClickable(true);
    }

    private View divider() {
        View line = new View(getContext());
        line.setBackgroundColor(Color.rgb(60, 62, 72));
        LayoutParams params = new LayoutParams(dp(1), LayoutParams.MATCH_PARENT);
        params.setMargins(dp(8), dp(6), dp(8), dp(6));
        line.setLayoutParams(params);
        return line;
    }

    private TextView label(String text, int colour, int sizeDp) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
