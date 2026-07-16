package com.pvpreplay.neoforge;

import com.pvpreplay.core.ReplayLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NeoForgeLogger implements ReplayLogger {
    private final Logger log = LoggerFactory.getLogger("PvpReplay");

    @Override public void info(String msg) { log.info(msg); }
    @Override public void warn(String msg) { log.warn(msg); }
    @Override public void error(String msg, Throwable t) { log.error(msg, t); }
}
