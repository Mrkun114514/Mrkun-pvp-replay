package com.pvpreplay.leaf;

import com.pvpreplay.capture.PacketCapture;
import com.pvpreplay.core.ConfigLoader;
import com.pvpreplay.core.ReplayConfig;
import com.pvpreplay.core.ReplayLogger;
import com.pvpreplay.core.ReplayManager;
import com.pvpreplay.core.ReplayMeta;
import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;

public class PvpReplayLeaf extends JavaPlugin implements Listener {

    private ReplayConfig config;
    private ReplayManager mgr;
    private ReplayLogger log;

    @Override
    public void onEnable() {
        Path configDir = getDataFolder().toPath();
        try { Files.createDirectories(configDir); } catch (IOException ignored) {}
        Path configFile = configDir.resolve("pvp-replay.properties");
        log = new LeafLogger(this);
        try {
            if (!Files.exists(configFile)) {
                config = new ReplayConfig();
                config.setProtocol(detectProtocol());
                config.setMcVersion(detectMcVersion());
                ConfigLoader.save(configFile, config);
            } else {
                config = ConfigLoader.load(configFile);
                config.setProtocol(detectProtocol());
                config.setMcVersion(detectMcVersion());
            }
        } catch (IOException e) {
            log.error("读取配置文件失败，使用默认配置", e);
            config = new ReplayConfig();
            config.setProtocol(detectProtocol());
            config.setMcVersion(detectMcVersion());
        }

        mgr = new ReplayManager(config, log, getDataFolder().toPath());
        log.info("PvpReplay 已加载。模式=" + config.getMode() + " 视角=" + config.getPerspective()
                + " 上限=" + config.getMaxDiskGb() + "GB/" + config.getMaxDays() + "天");

        Bukkit.getPluginManager().registerEvents(this, this);

        // 周期性限额清理（异步，避免阻塞主线程）
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try { mgr.enforceLimits(); } catch (Throwable t) { log.error("限额清理异常", t); }
        }, 1200L, 1200L); // 60s
    }

    @Override
    public void onDisable() {
        if (mgr != null) mgr.closeAll();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        if (!mgr.isRecording()) return;
        String dim = dimKey(player);
        if (!shouldRecord(dim)) return;

        ReplayMeta meta = buildMeta(player);
        final String key;
        if (config.getPerspective() == ReplayConfig.Perspective.EACH) {
            key = "p_" + player.getUniqueId();
            mgr.startSession(key, meta);
        } else {
            key = "dim_" + sanitize(dim);
            if (mgr.hasSession(key)) return;
            mgr.startSession(key, meta);
        }

        Channel ch = channelOf(player);
        long injectNanos = System.nanoTime();
        LongSupplier clock = () -> (System.nanoTime() - injectNanos) / 1_000_000L;
        PacketCapture.inject(ch, key, mgr, log, clock);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        String dim = dimKey(player);
        final String key = (config.getPerspective() == ReplayConfig.Perspective.EACH)
                ? "p_" + player.getUniqueId()
                : "dim_" + sanitize(dim);
        PacketCapture.remove(channelOf(player));
        mgr.endSession(key);
    }

    private boolean shouldRecord(String dim) {
        if (config.getMode() == ReplayConfig.Mode.DUEL) {
            return dim.equals(config.getDuelDimension());
        }
        return true;
    }

    private ReplayMeta buildMeta(Player player) {
        ReplayMeta meta = new ReplayMeta();
        meta.setProtocol(config.getProtocol());
        meta.setMcVersion(config.getMcVersion());
        meta.setDate(System.currentTimeMillis());
        meta.getPlayers().add(new ReplayMeta.PlayerInfo(player.getName(), player.getUniqueId().toString()));
        meta.setSelfId(config.getPerspective() == ReplayConfig.Perspective.EACH ? player.getEntityId() : -1);
        return meta;
    }

    // ---- accessors ----

    private static Channel channelOf(Player player) {
        try {
            Object nms = player.getClass().getMethod("getHandle").invoke(player);
            Object listener = field(nms, "connection");
            Object conn = field(listener, "connection");
            return (Channel) conn.getClass().getMethod("channel").invoke(conn);
        } catch (Exception e) { return null; }
    }

    private static Object field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static String dimKey(Player player) {
        // Paper API exposes the world's registry key (== dimension key).
        try { return player.getWorld().getKey().toString(); }
        catch (Exception e) { return "minecraft:overworld"; }
    }

    private static String sanitize(String s) { return s.replace(':', '_'); }

    private static int detectProtocol() {
        try {
            Class<?> sc = Class.forName("net.minecraft.SharedConstants");
            Object ver = sc.getMethod("getCurrentVersion").invoke(null);
            return (int) ver.getClass().getMethod("getProtocolVersion").invoke(ver);
        } catch (Exception e) { return 0; }
    }

    private static String detectMcVersion() {
        try {
            Class<?> sc = Class.forName("net.minecraft.SharedConstants");
            Object ver = sc.getMethod("getCurrentVersion").invoke(null);
            return (String) ver.getClass().getMethod("getName").invoke(ver);
        } catch (Exception e) { return ""; }
    }
}
