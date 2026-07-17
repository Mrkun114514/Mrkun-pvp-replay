package com.pvpreplay.neoforge;

import com.pvpreplay.capture.PacketCapture;
import com.pvpreplay.core.ConfigLoader;
import com.pvpreplay.core.ReplayConfig;
import com.pvpreplay.core.ReplayLogger;
import com.pvpreplay.core.ReplayManager;
import com.pvpreplay.core.ReplayMeta;
import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod(PvpReplayNeoForge.MODID)
public class PvpReplayNeoForge {

    public static final String MODID = "pvp_replay";
    private ReplayConfig config;
    private ReplayManager mgr;
    private final ReplayLogger log = new NeoForgeLogger();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pvp-replay-sweeper");
        t.setDaemon(true);
        return t;
    });

    // Camera player UUID -> active SHARED session key (C2); updated on dimension change (M3).
    private final java.util.Map<String,String> cameraKeyByUuid = new java.util.concurrent.ConcurrentHashMap<>();

    public PvpReplayNeoForge() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("pvp-replay");
        try { Files.createDirectories(configDir); } catch (IOException ignored) {}
        Path configFile = configDir.resolve("pvp-replay.properties");
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

        Path gameDir = FMLPaths.GAMEDIR.get();
        mgr = new ReplayManager(config, log, gameDir);
        log.info("PvpReplay 已加载。模式=" + config.getMode() + " 视角=" + config.getPerspective()
                + " 上限=" + config.getMaxDiskGb() + "GB/" + config.getMaxDays() + "天");

        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || !mgr.isRecording()) return;
        Channel ch = channelOf(player);
        String dim = dimKey(player);
        if (!shouldRecord(dim)) {
            PacketCapture.discard(ch); // DUEL 非目标维度：丢弃缓冲，不录制
            return;
        }

        ReplayMeta meta = buildMeta(player);
        final String key;
        if (config.getPerspective() == ReplayConfig.Perspective.EACH) {
            key = "p_" + player.getUUID().toString();
        } else {
            String cameraUuid = player.getUUID().toString();
            key = "dim_" + sanitize(dim);
            if (cameraKeyByUuid.putIfAbsent(cameraUuid, key) != null) {
                PacketCapture.discard(ch); // 非镜头 SHARED：停止缓冲、不录制
                return;
            }
        }

        // NeoForge 没有早于 PlayerLoggedInEvent 的干净 login/connection 事件
        // （参见 REVIEW 的 C0 章节），故 inject 与 beginSession 都在此完成。
        // 这意味着 login 阶段（Respawn / 初始 chunks / 玩家实体 id）的包无法被
        // buffer —— C0 在 NeoForge 上只能部分修复。t0 退化为本事件时刻。
        long t0 = PacketCapture.getConnectionStartNanos(ch);
        mgr.startSession(key, meta, t0 >= 0 ? t0 : System.nanoTime());
        if (ch != null) {
            PacketCapture.inject(ch, mgr, log);
            PacketCapture.beginSession(ch, key, mgr, log);
        }
    }

    @SubscribeEvent
    public void onLeave(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        String cameraUuid = player.getUUID().toString();
        if (config.getPerspective() == ReplayConfig.Perspective.EACH) {
            String key = "p_" + cameraUuid;
            PacketCapture.remove(channelOf(player));
            mgr.endSession(key);
        } else {
            String key = cameraKeyByUuid.remove(cameraUuid);
            if (key == null) {
                // C0: 非镜头 SHARED 玩家也会注入缓冲，离开时清理以免泄漏
                PacketCapture.remove(channelOf(player));
                return;
            }
            PacketCapture.remove(channelOf(player));
            mgr.endSession(key);
        }
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        String newDim = e.getTo().location().toString();
        handleDimensionChange(player, newDim);
    }

    private void handleDimensionChange(ServerPlayer player, String newDim) {
        if (!mgr.isRecording()) return;
        String uuid = player.getUUID().toString();
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

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent e) {
        sweeper.scheduleAtFixedRate(() -> {
            try { mgr.enforceLimits(); } catch (Throwable t) { log.error("限额清理异常", t); }
        }, 60, 60, TimeUnit.SECONDS);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent e) {
        mgr.closeAll();
    }

    private boolean shouldRecord(String dim) {
        if (config.getMode() == ReplayConfig.Mode.DUEL) {
            return dim.equals(config.getDuelDimension());
        }
        return true;
    }

    private ReplayMeta buildMeta(ServerPlayer player) {
        ReplayMeta meta = new ReplayMeta();
        meta.setProtocol(config.getProtocol());
        meta.setMcVersion(config.getMcVersion());
        meta.setDate(System.currentTimeMillis());
        meta.getPlayers().add(new ReplayMeta.PlayerInfo(player.getName().getString(), player.getUUID().toString()));
        meta.setSelfId(config.getPerspective() == ReplayConfig.Perspective.EACH ? player.getId() : -1);
        return meta;
    }

    // ---- version-robust accessors ----

    private static Channel channelOf(ServerPlayer p) {
        try {
            Object listener = field(p, "connection");
            Object conn = field(listener, "connection");
            Method m = conn.getClass().getMethod("channel");
            return (Channel) m.invoke(conn);
        } catch (Exception e) { return null; }
    }

    private static Object field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static String dimKey(ServerPlayer p) {
        try { return p.level().dimension().location().toString(); }
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
