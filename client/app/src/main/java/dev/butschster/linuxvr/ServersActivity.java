package dev.butschster.linuxvr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The connection manager: where you pick a machine to work on.
 *
 * <p>This is the app's front door, and it replaces a file pushed over adb. That file was
 * the honest shape of a personal experiment — one host, changed by rebuilding the workflow
 * around it. A desktop you connect to is a different thing: the machine on the desk is
 * found by itself, a machine elsewhere is typed in once, and both are remembered.
 */
public class ServersActivity extends Activity {

    private Ui ui;
    private LinearLayout list;
    private TextView status;
    private final Discovery discovery = new Discovery();
    private List<Server> found = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(20));

        TextView title = ui.mono("linux-vr", Ui.TEXT, 24);
        root.addView(title);

        status = ui.text("looking for servers…", Ui.MUTED, 15);
        status.setPadding(0, ui.dp(4), 0, ui.dp(14));
        root.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(addRow());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        render();
        status.setText("looking for servers…");
        discovery.sweep(servers -> {
            found = servers;
            status.setText(servers.isEmpty()
                    ? "nothing answered on this network — add a server by address"
                    : servers.size() + " found on this network");
            render();
        });
    }

    // -------------------------------------------------------------------- list

    private void render() {
        list.removeAllViews();

        List<Server> saved = Server.saved(this);
        for (Server server : found) {
            adoptSavedName(server, saved);
            list.addView(card(server));
        }
        for (Server server : saved) {
            if (!isIn(found, server)) list.addView(card(server));
        }
        if (found.isEmpty() && saved.isEmpty()) {
            TextView empty = ui.mono("Run the server on your Linux machine:\n\n"
                    + "    linux-vr-server\n\n"
                    + "and it will appear here.", Ui.MUTED, 16);
            empty.setPadding(0, ui.dp(20), 0, 0);
            list.addView(empty);
        }
    }

    private static void adoptSavedName(Server server, List<Server> saved) {
        for (Server known : saved) {
            if (known.sameAs(server)) {
                server.name = known.name;
                return;
            }
        }
    }

    private static boolean isIn(List<Server> servers, Server candidate) {
        for (Server server : servers) {
            if (server.sameAs(candidate)) return true;
        }
        return false;
    }

    private View card(Server server) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(18), ui.dp(14), ui.dp(14), ui.dp(14));
        card.setBackground(ui.rounded(Ui.CARD, 10));
        card.setClickable(true);
        card.setOnClickListener(v -> connect(server));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        card.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(heading);
        heading.addView(ui.mono(server.name, Ui.TEXT, 19));

        if (server.discovered) {
            TextView badge = ui.text("on this network", Ui.TEXT, 13);
            badge.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(3));
            badge.setBackground(ui.rounded(Ui.LIVE, 10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(ui.dp(12));
            heading.addView(badge, params);
        }

        labels.addView(ui.mono(server.detail(), Ui.MUTED, 15));

        card.addView(ui.button("connect", Ui.COMMAND, "see what this machine offers",
                v -> connect(server)));

        if (!server.discovered) {
            View forget = ui.button("×", Ui.WARN, "forget this server", v -> {
                Server.forget(this, server);
                render();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(ui.dp(8));
            card.addView(forget, params);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ui.dp(10);
        card.setLayoutParams(params);
        return card;
    }

    // ---------------------------------------------------------------- adding

    private View addRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(14), 0, 0);

        EditText host = field("address or hostname", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        row.addView(host, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText port = field("port", InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(Server.DEFAULT_PORT));
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(
                ui.dp(110), ViewGroup.LayoutParams.WRAP_CONTENT);
        portParams.setMarginStart(ui.dp(10));
        row.addView(port, portParams);

        View add = ui.button("add", Ui.COMMAND, "remember this server", v -> {
            String address = host.getText().toString().trim();
            if (address.isEmpty()) return;
            int number = Server.DEFAULT_PORT;
            try {
                number = Integer.parseInt(port.getText().toString().trim());
            } catch (NumberFormatException ignored) {
            }
            Server.remember(this, new Server(address, address, number));
            host.setText("");
            render();
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.setMarginStart(ui.dp(10));
        row.addView(add, addParams);

        return row;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.MUTED);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        field.setTypeface(Typeface.MONOSPACE);
        field.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        field.setBackground(ui.rounded(Ui.CARD, 8));
        return field;
    }

    // -------------------------------------------------------------- connecting

    private void connect(Server server) {
        Server.remember(this, server);
        Intent intent = new Intent(this, SessionActivity.class);
        intent.putExtra(SessionActivity.EXTRA_HOST, server.host);
        intent.putExtra(SessionActivity.EXTRA_PORT, server.port);
        intent.putExtra(SessionActivity.EXTRA_NAME, server.name);
        // Its own task, so the session sits beside this window rather than
        // replacing it: two machines can be open at once.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        startActivity(intent);
    }
}
