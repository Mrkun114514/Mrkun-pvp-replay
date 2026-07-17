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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public class PvpReplayLeaf extends JavaPlugin implements Listener {

    private ReplayConfig config;
    private ReplayManager mgr;
    private ReplayLogger log;

    // Camera player UUID -> active SHARED session key (C2); updated on world change (M3).
    private final java.util.Map<String,String> cameraKeyByUuid = new java.util.concurrent.ConcurrentHashMap<>();

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

    // C0: inject the capture handler at login (same channel as play). Buffers
    // outbound packets until onJoin() calls beginSession.
    @EventHandler
    public void onLogin(PlayerLoginEvent e) {
        if (!mgr.isRecording()) return;
        Channel ch = channelOf(e.getPlayer());
        if (ch == null) return;
        PacketCapture.inject(ch, mgr, log);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        if (!mgr.isRecording()) return;
        Channel ch = channelOf(player);
        String dim = dimKey(player);
        if (!shouldRecord(dim)) {
            PacketCapture.discard(ch); // DUEL 非目标维度：丢弃 login 缓冲，不录制
            return;
        }

        ReplayMeta meta = buildMeta(player);
        final String key;
        if (config.getPerspective() == ReplayConfig.Perspective.EACH) {
            key = "p_" + player.getUniqueId().toString();
        } else {
            String cameraUuid = player.getUniqueId().toString();
            key = "dim_" + sanitize(dim);
            if (cameraKeyByUuid.putIfAbsent(cameraUuid, key) != null) {
                PacketCapture.discard(ch); // 非镜头 SHARED：停止缓冲、不录制
                return;
            }
        }

        // Anchor the session timeline to the connection moment (captured at login),
        // then flush the buffered login-phase packets and switch to live capture.
        long t0 = PacketCapture.getConnectionStartNanos(ch);
        mgr.startSession(key, meta, t0 >= 0 ? t0 : System.nanoTime());
        PacketCapture.beginSession(ch, key, mgr, log);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        String cameraUuid = player.getUniqueId().toString();
        if (config.getPerspective() == ReplayConfig.Perspective.EACH) {
            String key = "p_" + cameraUuid;
            PacketCapture.remove(channelOf(player));
            mgr.endSession(key);
        } else {
            String key = cameraKeyByUuid.remove(cameraUuid);
            if (key == null) {
                // C0: 非镜头 SHARED 玩家也在 login 阶段注入了缓冲，离开时清理以免泄漏
                PacketCapture.remove(channelOf(player));
                return;
            }
            PacketCapture.remove(channelOf(player));
            mgr.endSession(key);
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player player = e.getPlayer();
        String newDim = dimKey(player); // player.getWorld() is the NEW world after the change
        handleDimensionChange(player, newDim);
    }

    private void handleDimensionChange(Player player, String newDim) {
        if (!mgr.isRecording()) return;
        String uuid = player.getUniqueId().toString();
        boolean each = config.getPerspective() == ReplayConfig.Perspective.EACH;
        String oldKey = each ? "p_" + uuid : cameraKeyByUuid.get(uuid);
        if (oldKey == null) return; // SHARED：非镜头玩家
        Channel ch = channelOf(player);
        PacketCapture.remove(ch);
        mgr.endSession(oldKey);
        if (!each) cameraKeyByUuid.remove(uuid);
        if (!shouldRecord(newDim)) return;
        ReplayMeta meta = buildMeta(player);
        String newKey = each ? "p_" + uuid : "dim_" + sanitize(newDim);
        if (!each) cameraKeyByUuid.put(uuid, newKey);
        // 维度切换产生独立的 .mcpr，时间轴从切换时刻起算（而非连接时刻）。
        long t0 = System.nanoTime();
        mgr.startSession(newKey, meta, t0);
        if (ch != null) {
            PacketCapture.inject(ch, mgr, log);
            PacketCapture.beginSession(ch, newKey, mgr, log);
        }
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
