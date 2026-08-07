package dev.butschster.linuxvr.term;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * A terminal window: a strip of tabs, and the active one's grid under it.
 *
 * <p>Tabs rather than more windows because the two are not the same thing. A window is
 * placed in the room and costs a place to put it; a tab costs a press. Both exist —
 * several windows still work, each with its own tabs — but stacking four shells for one
 * project belongs in one window.
 *
 * <p>The bar is not here. It is {@link BarActivity}, in a window of its own, so the shell
 * can size it independently; it follows whichever tab of whichever window is active.
 */
public class TermActivity extends Activity implements Terminals.Target {

    private static final String TAG = "linux-vr";
    private static final int TERM_PORT = 9103;

    /**
     * Default cell height in px. From docs/readability.md: comfort begins near 0.39° per
     * glyph, which on a panel of this size lands around 20 px — and unlike the streamed
     * desktop, nothing downscales it afterwards.
     */
    private static final int DEFAULT_FONT_PX = 20;

    /** Only the first terminal window opens the bar; after that there is one already. */
    private static boolean barOpened;

    private final List<TermTab> tabs = new ArrayList<>();
    private int active = -1;

    private String host;
    private Buttons buttons;
    private LinearLayout tabStrip;
    private FrameLayout content;
    private int fontSize = DEFAULT_FONT_PX;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        host = readHost();
        Typeface icons = Typeface.createFromAsset(getAssets(), "MaterialIcons-Regular.ttf");
        buttons = new Buttons(this, icons);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 17, 21));

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setBackgroundColor(Color.rgb(24, 25, 31));
        strip.setPadding(buttons.dp(6), buttons.dp(6), buttons.dp(6), buttons.dp(6));
        root.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabStrip);
        strip.addView(tabScroll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // New and close sit at the far right, away from the tabs themselves: a
        // ray that misses a tab must not close one.
        View add = buttons.key("+", Buttons.COMMAND, "new tab here", v -> addTab(currentCwd()));
        strip.addView(add, sideParams());
        View close = buttons.key("×", Buttons.WARN, "close this tab", v -> closeTab(active));
        strip.addView(close, sideParams());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (host == null) {
            Toast.makeText(this, "push host.txt with the host address", Toast.LENGTH_LONG).show();
            return;
        }

        addTab(null);

        if (!barOpened) {
            barOpened = true;
            Intent bar = new Intent(this, BarActivity.class);
            // LAUNCH_ADJACENT is the one placement Meta documents for a 2D app:
            // "the panel activity will be launched next to the actively running
            // activity from your app". NEW_DOCUMENT would also give it a window of
            // its own, but where that window lands is documented nowhere, and this
            // project does not get to guess about this platform.
            bar.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(bar);
        }
    }

    private LinearLayout.LayoutParams sideParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(buttons.gap() / 2, 0, buttons.gap() / 2, 0);
        return params;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Whichever terminal was resumed last is the one the bar drives. Horizon OS
        // does not tell an app which of its windows is being looked at, and this is
        // the closest fact available.
        Terminals.setActive(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Terminals.clearIf(this);
        for (TermTab tab : tabs) tab.session.close();
    }

    // -------------------------------------------------------------------- tabs

    private String currentCwd() {
        TermTab tab = activeTab();
        return tab == null ? null : tab.cwd();
    }

    private void addTab(String cwd) {
        if (host == null) return;

        TermView view = new TermView(this, fontSize);
        TermTab[] holder = new TermTab[1];
        HostSession session = new HostSession(host, TERM_PORT, new HostSession.Listener() {
            @Override
            public void onTextChanged() {
                view.onOutput();
            }

            @Override
            public void onContext(JSONObject context) {
                holder[0].setContext(context);
                refreshTitles();
                publish(holder[0]);
            }

            @Override
            public void onStatus(String message, boolean connected) {
                holder[0].setStatus(message);
                if (connected) view.logState();
                publish(holder[0]);
            }
        });
        view.attach(session);
        TermTab tab = new TermTab(view, session);
        holder[0] = tab;

        tabs.add(tab);
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        select(tabs.size() - 1);
        refreshTitles();

        // The agent starts every shell in its own --cwd. A tab opened from another
        // one should land beside it, and typing the cd is how a person would do it:
        // it is visible, it is in the history, and it needs no new protocol.
        if (cwd != null && !cwd.isEmpty()) {
            view.postDelayed(() -> view.sendLine("cd " + shellQuote(cwd)), 700);
        }
    }

    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        TermTab tab = tabs.remove(index);
        tab.session.close();
        content.removeView(tab.view);
        if (tabs.isEmpty()) {
            finish();
            return;
        }
        select(Math.min(index, tabs.size() - 1));
        refreshTitles();
    }

    private void select(int index) {
        if (index < 0 || index >= tabs.size()) return;
        active = index;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).view.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        TermTab tab = tabs.get(index);
        tab.view.requestFocus();
        refreshTitles();
        publish(tab);
    }

    private TermTab activeTab() {
        return active >= 0 && active < tabs.size() ? tabs.get(active) : null;
    }

    /** Only the active tab's context reaches the bar. */
    private void publish(TermTab tab) {
        if (tab == activeTab() && Terminals.active() == this) Terminals.notifyWatchers();
    }

    private void refreshTitles() {
        tabStrip.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            TermTab tab = tabs.get(i);
            TextView button = buttons.key(tab.title(i),
                    i == active ? Buttons.COMMAND : Buttons.KEY,
                    tab.status(), v -> select(index));
            button.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(buttons.gap() / 2, 0, buttons.gap() / 2, 0);
            tabStrip.addView(button, params);
        }
    }

    /**
     * The host address, from the app's own files directory.
     *
     * <p>Push it with {@code adb push}, not {@code adb shell echo >}: the latter creates
     * the file mode 660 owned by {@code shell} and the app cannot read it.
     */
    private String readHost() {
        File file = new File(getExternalFilesDir(null), "host.txt");
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line = in.readLine();
            return line == null || line.trim().isEmpty() ? null : line.trim();
        } catch (Exception e) {
            Log.w(TAG, "no host.txt at " + file);
            return null;
        }
    }

    // ------------------------------------------------------------ Terminals.Target

    @Override
    public void send(String text, boolean enter) {
        TermTab tab = activeTab();
        if (tab == null) return;
        // Typing without running is the default for anything you would want to add
        // words to — a skill invocation, a command with a path still to come.
        if (enter) tab.view.sendLine(text);
        else tab.view.send(text);
    }

    @Override
    public void showKeyboard() {
        TermTab tab = activeTab();
        if (tab != null) tab.view.showKeyboard();
    }

    @Override
    public void fontStep(int delta) {
        fontSize = Math.max(10, Math.min(64, fontSize + delta));
        for (TermTab tab : tabs) tab.view.setFontSize(fontSize);
        TermTab tab = activeTab();
        if (tab != null) {
            tab.setStatus(fontSize + " px  ·  " + tab.view.getColumns() + "×" + tab.view.getRows());
            publish(tab);
        }
    }

    @Override
    public void scroll(int rows) {
        TermTab tab = activeTab();
        if (tab != null) tab.view.scrollRows(rows);
    }

    @Override
    public JSONObject context() {
        TermTab tab = activeTab();
        return tab == null ? null : tab.context();
    }

    @Override
    public String status() {
        TermTab tab = activeTab();
        return tab == null ? "no tab" : tab.status();
    }
}
