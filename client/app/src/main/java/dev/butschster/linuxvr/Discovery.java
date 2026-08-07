package dev.butschster.linuxvr;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Finds servers on this network by asking.
 *
 * <p>A UDP broadcast rather than mDNS, deliberately. mDNS would mean avahi running on
 * every host and NsdManager here — two moving parts and two failure modes for a question
 * with one line in it. A server elsewhere on the internet is not discoverable by any of
 * these means anyway; that case is served by typing an address, which is what it needs.
 *
 * <p>The probe goes to 255.255.255.255 and to each interface's own broadcast address. The
 * first is blocked or ignored on some networks; the second is what usually works, and
 * sending both costs two datagrams. The reply comes back unicast, so no multicast lock is
 * needed — which matters on Horizon OS, where system services cannot be assumed present.
 */
public class Discovery {

    private static final String TAG = "linux-vr";
    private static final String PROBE = "LINUXVR-DISCOVER 1";

    /** Long enough for a server that is busy, short enough not to feel broken. */
    private static final int LISTEN_MS = 1200;

    public interface Listener {
        /** Called on the main thread, once per sweep, with everything that answered. */
        void onFound(List<Server> servers);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;

    public void sweep(Listener listener) {
        if (running) return;
        running = true;
        new Thread(() -> {
            List<Server> found = probe();
            running = false;
            main.post(() -> listener.onFound(found));
        }, "discovery").start();
    }

    private List<Server> probe() {
        List<Server> found = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(200);

            byte[] payload = PROBE.getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : broadcastAddresses()) {
                try {
                    socket.send(new DatagramPacket(payload, payload.length, target,
                            Server.DEFAULT_PORT));
                } catch (Exception e) {
                    Log.w(TAG, "probe to " + target + " failed: " + e.getMessage());
                }
            }

            byte[] buffer = new byte[2048];
            long until = System.currentTimeMillis() + LISTEN_MS;
            while (System.currentTimeMillis() < until) {
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (Exception timeout) {
                    continue;
                }
                Server server = parse(reply);
                if (server != null && !contains(found, server)) found.add(server);
            }
        } catch (Exception e) {
            Log.w(TAG, "discovery failed: " + e.getMessage());
        }
        return found;
    }

    private static boolean contains(List<Server> servers, Server candidate) {
        for (Server server : servers) {
            if (server.sameAs(candidate)) return true;
        }
        return false;
    }

    private Server parse(DatagramPacket packet) {
        try {
            String text = new String(packet.getData(), 0, packet.getLength(),
                    StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(text);
            if (!"linux-vr".equals(json.optString("proto"))) return null;
            // The terminal server answers the same probe on its own port. Ignoring
            // anything that is not a desktop keeps this list honest: connecting to
            // a terminal server here would find no monitors and explain nothing.
            if (!"desktop".equals(json.optString("service"))) return null;

            // The address comes from the packet, not from the payload: a server does
            // not reliably know which of its addresses reached us.
            Server server = new Server(
                    json.optString("name", packet.getAddress().getHostAddress()),
                    packet.getAddress().getHostAddress(),
                    json.optInt("port", Server.DEFAULT_PORT));
            server.user = json.optString("user", "");
            server.os = json.optString("os", "");
            server.monitors = json.optInt("monitors", 0);
            server.discovered = true;
            return server;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (network.isLoopback() || !network.isUp()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast != null) targets.add(broadcast);
                }
            }
        } catch (Exception ignored) {
        }
        return targets;
    }
}
