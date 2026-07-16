package com.pvpreplay.leaf;

import com.pvpreplay.core.ReplayLogger;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class LeafLogger implements ReplayLogger {
    private final JavaPlugin plugin;

    public LeafLogger(JavaPlugin plugin) { this.plugin = plugin; }

    @Override public void info(String msg) { plugin.getLogger().info(msg); }
    @Override public void warn(String msg) { plugin.getLogger().warning(msg); }
    @Override public void error(String msg, Throwable t) { plugin.getLogger().log(Level.SEVERE, msg, t); }
}
