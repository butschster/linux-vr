package dev.butschster.linuxvr.term;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The link between a terminal window and the bar window.
 *
 * <p>The bar lives in its own task so the shell can place and resize it independently —
 * that is the whole reason it is not docked under the terminal. Two tasks of the same app
 * still share one process, so this is a plain static registry rather than anything that
 * has to cross a process boundary.
 *
 * <p>With several terminals open the bar follows the one last brought to the front, which
 * is the only definition of "the one you are working in" available to a 2D app: Horizon OS
 * does not tell an app which of its windows the user is looking at, only which resumed
 * last.
 */
public final class Terminals {

    /** What the bar can do to a terminal. Implemented by the terminal window. */
    public interface Target {
        void send(String text, boolean enter);

        void showKeyboard();

        void fontStep(int delta);

        void scroll(int rows);

        /** Where this terminal is and what is running in it, or null before the first report. */
        JSONObject context();

        String status();
    }

    /** Told when the active terminal changes, or when its context does. */
    public interface Watcher {
        void onTerminalChanged(Target target);
    }

    private static final CopyOnWriteArrayList<Watcher> watchers = new CopyOnWriteArrayList<>();
    private static Target active;

    private Terminals() {
    }

    public static void setActive(Target target) {
        active = target;
        notifyWatchers();
    }

    public static void clearIf(Target target) {
        if (active == target) {
            active = null;
            notifyWatchers();
        }
    }

    public static Target active() {
        return active;
    }

    public static void notifyWatchers() {
        for (Watcher watcher : watchers) watcher.onTerminalChanged(active);
    }

    public static void watch(Watcher watcher) {
        watchers.addIfAbsent(watcher);
        watcher.onTerminalChanged(active);
    }

    public static void unwatch(Watcher watcher) {
        watchers.remove(watcher);
    }
}
