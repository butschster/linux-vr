package com.termux.terminal;

/**
 * Callbacks from {@link TerminalEmulator} and {@link Logger} to whoever owns the session.
 *
 * <p>Vendored from Termux and reduced. Upstream this interface talks in terms of
 * {@code TerminalSession}, the class that forks a local process through JNI; here the
 * session lives on the host at the other end of a socket, so those methods have no
 * meaning and only the ones the emulator itself calls are kept.
 */
public interface TerminalSessionClient {

    /** Null for the default, or one of {@link TerminalEmulator#TERMINAL_CURSOR_STYLE_BLOCK} and friends. */
    Integer getTerminalCursorStyle();

    void onTerminalCursorStateChange(boolean state);

    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);
}
