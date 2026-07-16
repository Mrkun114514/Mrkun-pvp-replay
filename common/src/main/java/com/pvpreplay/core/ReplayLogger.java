package com.pvpreplay.core;

/**
 * Minimal logging abstraction so the common module has no dependency on any
 * specific loader's logging framework. Each platform wires its own impl.
 */
public interface ReplayLogger {
    void info(String msg);

    void warn(String msg);

    void error(String msg, Throwable t);

    /** No-op implementation used for tests / headless runs. */
    ReplayLogger NOP = new ReplayLogger() {
        @Override public void info(String msg) { System.out.println("[PvpReplay] " + msg); }
        @Override public void warn(String msg) { System.out.println("[PvpReplay][WARN] " + msg); }
        @Override public void error(String msg, Throwable t) { System.err.println("[PvpReplay][ERR] " + msg); if (t != null) t.printStackTrace(); }
    };
}
