package dev.butschster.linuxvr;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks a server what it has.
 *
 * <p>The discovery datagram only says "here I am" — it has one packet to work with.
 * Everything a window needs to open is here instead: which screens exist, how big they
 * are, which port each is served on, and which parts of the server actually work.
 *
 * <p>That last part matters more than it sounds. A host without {@code ffmpeg}, or without
 * a password-less sudo rule for it, looks exactly like a broken client: windows open and
 * stay black. Asking first turns that into a sentence on screen.
 */
public class Control {

    private static final String TAG = "linux-vr";
    private static final int TIMEOUT_MS = 4000;

    /** One screen on the host, as the client needs it. */
    public static class Screen {
        public int index;
        public String connector = "";
        public int width, height;
        public int port;
        public boolean streaming;

        public String label() {
            return connector.isEmpty() ? "screen " + index : connector;
        }

        public String detail() {
            return width + "×" + height + (streaming ? "   live" : "");
        }
    }

    /** Whether a part of the server can do its job, and why not when it cannot. */
    public static class Feature {
        public boolean available;
        public String detail = "";
    }

    public static class Info {
        public String name = "";
        public String os = "";
        public String user = "";
        public int desktopWidth, desktopHeight;
        public final List<Screen> screens = new ArrayList<>();
        public Feature capture = new Feature();
        public Feature input = new Feature();
        public Feature voice = new Feature();
    }

    public interface Listener {
        /** Both called on the main thread. */
        void onInfo(Info info);

        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    public void fetch(Server server, Listener listener) {
        new Thread(() -> {
            try {
                Info info = request(server);
                main.post(() -> listener.onInfo(info));
            } catch (Exception e) {
                Log.w(TAG, "cannot reach " + server.address() + ": " + e.getMessage());
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> listener.onError(message));
            }
        }, "control").start();
    }

    private Info request(Server server) throws Exception {
        URL url = new URL("http://" + server.host + ":" + server.port + "/v1/info");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new Exception("server answered " + status);
            return parse(new JSONObject(read(connection.getInputStream())));
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = stream.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static Info parse(JSONObject json) {
        Info info = new Info();
        info.name = json.optString("name", "");
        info.os = json.optString("os", "");
        info.user = json.optString("user", "");

        JSONObject desktop = json.optJSONObject("desktop");
        if (desktop != null) {
            info.desktopWidth = desktop.optInt("width");
            info.desktopHeight = desktop.optInt("height");
        }

        JSONArray screens = json.optJSONArray("screens");
        for (int i = 0; screens != null && i < screens.length(); i++) {
            JSONObject entry = screens.optJSONObject(i);
            if (entry == null) continue;
            Screen screen = new Screen();
            screen.index = entry.optInt("index", i);
            screen.connector = entry.optString("connector", "");
            screen.width = entry.optInt("width");
            screen.height = entry.optInt("height");
            screen.port = entry.optInt("port");
            screen.streaming = entry.optBoolean("streaming");
            info.screens.add(screen);
        }

        JSONObject services = json.optJSONObject("services");
        info.capture = feature(services, "capture");
        info.input = feature(services, "input");
        info.voice = feature(services, "voice");
        return info;
    }

    private static Feature feature(JSONObject services, String name) {
        Feature feature = new Feature();
        JSONObject entry = services == null ? null : services.optJSONObject(name);
        if (entry != null) {
            feature.available = entry.optBoolean("available");
            feature.detail = entry.optString("detail", "");
        }
        return feature;
    }
}
