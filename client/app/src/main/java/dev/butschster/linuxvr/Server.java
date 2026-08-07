package dev.butschster.linuxvr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A machine whose desktop can be opened here, either found on this network or typed in.
 *
 * <p>Both kinds are kept because they answer different needs. Discovery covers the machine
 * on the desk, which changes address whenever the router feels like it. A typed address
 * covers a server somewhere else — which no broadcast will ever reach, and which is the
 * case that makes this a client-server product rather than a companion to one desktop.
 */
public class Server {

    /** Control port: the HTTP API and the discovery probes share the number. */
    public static final int DEFAULT_PORT = 9099;

    public String name;
    public String host;
    public int port;

    public String user = "";
    public String os = "";
    public int monitors = 0;

    /** True when this one answered a probe just now, rather than being remembered. */
    public boolean discovered;

    public Server(String name, String host, int port) {
        this.name = name == null || name.isEmpty() ? host : name;
        this.host = host;
        this.port = port;
    }

    public String address() {
        return host + ":" + port;
    }

    /** One line under the name: who and what, when the server said so. */
    public String detail() {
        StringBuilder text = new StringBuilder(address());
        if (!user.isEmpty()) text.append("   ").append(user);
        if (!os.isEmpty()) text.append("   ").append(os);
        if (monitors > 0) text.append("   ").append(monitors)
                .append(monitors == 1 ? " screen" : " screens");
        return text.toString();
    }

    public boolean sameAs(Server other) {
        return other != null && host.equals(other.host) && port == other.port;
    }

    // ------------------------------------------------------------------- storage

    private static final String PREFS = "servers";
    private static final String KEY = "saved";

    /**
     * Saved servers, in the order they were added.
     *
     * <p>SharedPreferences rather than a file or a database: this is a handful of
     * addresses, and anything heavier would be a dependency bought for nothing.
     */
    public static List<Server> saved(Context context) {
        List<Server> servers = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                servers.add(new Server(entry.optString("name"), entry.optString("host"),
                        entry.optInt("port", DEFAULT_PORT)));
            }
        } catch (Exception ignored) {
        }
        return servers;
    }

    public static void save(Context context, List<Server> servers) {
        JSONArray array = new JSONArray();
        for (Server server : servers) {
            try {
                JSONObject entry = new JSONObject();
                entry.put("name", server.name);
                entry.put("host", server.host);
                entry.put("port", server.port);
                array.put(entry);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).apply();
    }

    public static void remember(Context context, Server server) {
        List<Server> servers = saved(context);
        for (Server known : servers) {
            if (known.sameAs(server)) {
                known.name = server.name;
                save(context, servers);
                return;
            }
        }
        servers.add(server);
        save(context, servers);
    }

    public static void forget(Context context, Server server) {
        List<Server> servers = saved(context);
        servers.removeIf(known -> known.sameAs(server));
        save(context, servers);
    }
}
