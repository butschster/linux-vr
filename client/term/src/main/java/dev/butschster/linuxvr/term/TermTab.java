package dev.butschster.linuxvr.term;

import org.json.JSONObject;

/**
 * One tab: one socket, one pty, one shell of its own.
 *
 * <p>Nothing is shared between tabs. The agent already serves a session per connection,
 * so a tab costs exactly what a second window costs — which is why tabs and windows can
 * both exist without the host knowing the difference.
 */
public class TermTab {

    public final TermView view;
    public final HostSession session;

    private JSONObject context;
    private String status = "connecting…";

    public TermTab(TermView view, HostSession session) {
        this.view = view;
        this.session = session;
    }

    public void setContext(JSONObject context) {
        this.context = context;
    }

    public JSONObject context() {
        return context;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String status() {
        return status;
    }

    /**
     * What the tab is called: the directory, and the program if one is running.
     *
     * <p>The directory alone is not enough — two shells in the same project are the
     * normal case, and "which one is Claude Code" is the question being asked.
     */
    public String title(int index) {
        if (context == null) return "shell " + (index + 1);
        String cwd = context.optString("cwd_label", "");
        int slash = cwd.lastIndexOf('/');
        String leaf = slash >= 0 && slash < cwd.length() - 1 ? cwd.substring(slash + 1) : cwd;
        if (leaf.isEmpty()) leaf = "/";
        String tool = context.isNull("tool") ? null : context.optString("tool_name", null);
        return tool == null ? leaf : leaf + " · " + tool;
    }

    /** Where a new tab should start: beside this one, not back at the agent's default. */
    public String cwd() {
        return context == null ? null : context.optString("cwd", null);
    }
}
