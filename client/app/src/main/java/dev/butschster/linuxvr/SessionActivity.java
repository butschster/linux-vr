package dev.butschster.linuxvr;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One connected machine: what it offers, and a button per window.
 *
 * <p>Opening every screen automatically was the obvious alternative and is wrong on a desk
 * with three monitors — five windows land at once, on top of each other, and the first
 * thing you do is close four. Here the session stays open as the place you come back to
 * when you want another screen or the controls.
 *
 * <p>It is also where a half-working host explains itself. Capture needs ffmpeg, a DRM
 * device and a password-less sudo rule; input needs /dev/uinput. Any of those missing
 * looks identical from inside a window — black picture, dead pointer — so the server
 * reports what works and it is shown here rather than discovered by guesswork.
 */
public class SessionActivity extends Activity {

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_NAME = "name";

    private Ui ui;
    private LinearLayout body;
    private TextView status;
    private Server server;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);

        String host = getIntent().getStringExtra(EXTRA_HOST);
        int port = getIntent().getIntExtra(EXTRA_PORT, Server.DEFAULT_PORT);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        server = new Server(name, host == null ? "" : host, port);
        setTitle(server.name);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(20));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(heading);

        TextView title = ui.mono(server.name, Ui.TEXT, 24);
        heading.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heading.addView(ui.button("refresh", Ui.NEUTRAL, "ask the server again", v -> load()));

        status = ui.mono(server.address(), Ui.MUTED, 15);
        status.setPadding(0, ui.dp(4), 0, ui.dp(16));
        root.addView(status);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        load();
    }

    private void load() {
        body.removeAllViews();
        status.setText(server.address() + "   asking…");
        new Control().fetch(server, new Control.Listener() {
            @Override
            public void onInfo(Control.Info info) {
                render(info);
            }

            @Override
            public void onError(String message) {
                status.setText(server.address() + "   not answering");
                body.removeAllViews();
                TextView explain = ui.mono(message + "\n\n"
                        + "Is linux-vr-server running on that machine?\n"
                        + "Check with:  linux-vr-server doctor", Ui.MUTED, 16);
                body.addView(explain);
            }
        });
    }

    private void render(Control.Info info) {
        body.removeAllViews();
        StringBuilder line = new StringBuilder(server.address());
        if (!info.os.isEmpty()) line.append("   ").append(info.os);
        if (info.desktopWidth > 0) {
            line.append("   desktop ").append(info.desktopWidth).append("×")
                    .append(info.desktopHeight);
        }
        status.setText(line);

        if (!info.capture.available) {
            body.addView(warning("No video from this host: " + info.capture.detail));
        }
        if (!info.input.available) {
            body.addView(warning("The pointer will not work: " + info.input.detail));
        }

        body.addView(section("screens"));
        if (info.screens.isEmpty()) {
            body.addView(ui.mono("The server reports no monitors.", Ui.MUTED, 16));
        }
        for (Control.Screen screen : info.screens) {
            body.addView(screenCard(screen));
        }

        body.addView(section("controls"));
        body.addView(row("keyboard, dictation and keys",
                info.voice.available ? "" : info.voice.detail,
                ui.button("open", Ui.COMMAND, "a window with the keyboard and dictation",
                        v -> openControls())));
    }

    private View screenCard(Control.Screen screen) {
        return row(screen.label(), screen.detail(),
                ui.button("open", Ui.COMMAND, "a window showing this screen",
                        v -> openScreen(screen)));
    }

    private View row(String title, String detail, View action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(18), ui.dp(14), ui.dp(14), ui.dp(14));
        card.setBackground(ui.rounded(Ui.CARD, 10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        card.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(ui.mono(title, Ui.TEXT, 19));
        if (detail != null && !detail.isEmpty()) {
            labels.addView(ui.mono(detail, Ui.MUTED, 15));
        }

        card.addView(action);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ui.dp(10);
        card.setLayoutParams(params);
        return card;
    }

    private View section(String name) {
        TextView heading = ui.text(name, Ui.MUTED, 14);
        heading.setPadding(ui.dp(2), ui.dp(10), 0, ui.dp(8));
        return heading;
    }

    private View warning(String message) {
        TextView view = ui.mono(message, Ui.TEXT, 15);
        view.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        view.setBackground(ui.rounded(Ui.WARN, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ui.dp(10);
        view.setLayoutParams(params);
        return view;
    }

    // ----------------------------------------------------------------- opening

    private void openScreen(Control.Screen screen) {
        Intent intent = new Intent(this, PanelActivity.class);
        intent.putExtra(PanelActivity.EXTRA_HOST, server.host);
        intent.putExtra(PanelActivity.EXTRA_MONITOR, screen.index);
        intent.putExtra(PanelActivity.EXTRA_LABEL, server.name + " · " + screen.label());
        open(intent);
    }

    private void openControls() {
        Intent intent = new Intent(this, ControlActivity.class);
        intent.putExtra(PanelActivity.EXTRA_HOST, server.host);
        open(intent);
    }

    /**
     * NEW_DOCUMENT plus MULTIPLE_TASK is what makes the shell open a separate window
     * instead of raising an existing one. Without both, opening a second screen replaces
     * the first — which is the opposite of the point.
     */
    private void open(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        startActivity(intent);
    }
}
