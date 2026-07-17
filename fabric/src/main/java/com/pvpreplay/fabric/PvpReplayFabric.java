package com.pvpreplay.fabric;

import com.pvpreplay.capture.PacketCapture;
import com.pvpreplay.core.ConfigLoader;
import com.pvpreplay.core.ReplayConfig;
import com.pvpreplay.core.ReplayLogger;
import com.pvpreplay.core.ReplayManager;
import com.pvpreplay.core.ReplayMeta;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import io.netty.channel.Channel;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PvpReplayFabric implements ModInitializer {

    private ReplayConfig config;
    private ReplayManager mgr;
    private final ReplayLogger log = new FabricLogger();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pvp-replay-sweeper");
        t.setDaemon(true);
        return t;
    });

    // Camera player UUID -> active SHARED session key (C2) and the per-tick dimension
    // poll map (M3). Fully-qualified types keep this module free of extra imports.
    private final java.util.Map<String,String> cameraKeyByUuid = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String,String> lastDimByUuid = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("pvp-replay");
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

        Path gameDir = FabricLoader.getInstance().getGameDir();
        mgr = new ReplayManager(config, log, gameDir);
        log.info("PvpReplay 已加载。模式=" + config.getMode() + " 视角=" + config.getPerspective()
                + " 上限=" + config.getMaxDiskGb() + "GB/" + config.getMaxDays() + "天");

        // C0: inject the capture handler as early as the LOGIN state (ServerLoginConnectionEvents.INIT),
        // so the Login / Respawn / initial-chunk packets are buffered before the play session starts.
        // (Fabric API 0.102 has INIT, not LOGIN_SUCCESS.)
        ServerLoginConnectionEvents.INIT.register((loginHandler, server) -> onLogin(loginHandler));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onLeave(handler));
        ServerTickEvents.END_SERVER_TICK.register(server -> pollDimensions(server));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> startSweeper());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> mgr.closeAll());
    }

    private void startSweeper() {
        sweeper.scheduleAtFixedRate(() -> {
            try { mgr.enforceLimits(); } catch (Throwable t) { log.error("限额清理异常", t); }
        }, 60, 60, TimeUnit.SECONDS);
    }

    // C0: inject the capture handler at login success (same channel as the play
    // phase). The handler buffers outbound packets until onJoin() calls beginSession.
    // The channel is fetched reflectively (loginHandler.connection -> channel()).
    private void onLogin(ServerLoginNetworkHandler loginHandler) {
        if (!mgr.isRecording()) return;
        Object conn = tryField(loginHandler, "connection"); // ClientConnection (Yarn)
        if (conn == null) conn = tryInvoke(loginHandler, "getConnection");
        Channel channel = null;
        if (conn != null) {
            Object ch = tryInvoke(conn, "channel");
            if (ch instanceof Channel) channel = (Channel) ch;
        }
        if (channel == null) return;
        PacketCapture.inject(channel, mgr, log);
    }

    private void onJoin(ServerPlayNetworkHandler handler) {
        Object player = playerOf(handler);
        if (player == null || !mgr.isRecording()) return;
        Channel ch = channelOf(handler);
        String dim = dimKey(player);
        if (!shouldRecord(dim)) {
            PacketCapture.discard(ch); // DUEL 非目标维度：丢弃 login 缓冲，不录制
            return;
        }

        ReplayMeta meta = buildMeta(player);
        final String key;
        if (config.getPerspective() == ReplayConfig.Perspective.EACH) {
            key = "p_" + playerUuid(player);
        } else {
            String cameraUuid = playerUuid(player);
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

    private void onLeave(ServerPlayNetworkHandler handler) {
        Object player = playerOf(handler);
        if (player == null) return;
        String cameraUuid = playerUuid(player);
        lastDimByUuid.remove(cameraUuid);
        if (config.getPerspective() == ReplayConfig.Perspective.EACH) {
            String key = "p_" + cameraUuid;
            PacketCapture.remove(channelOf(handler));
            mgr.endSession(key);
        } else {
            String key = cameraKeyByUuid.remove(cameraUuid);
            if (key == null) {
                // C0: 非镜头 SHARED 玩家也在 login 阶段注入了缓冲，离开时清理以免泄漏
                PacketCapture.remove(channelOf(handler));
                return;
            }
            PacketCapture.remove(channelOf(handler));
            mgr.endSession(key);
        }
    }

    // Fabric has no dimension-change event, so poll each server tick and react when a
    // player's current world differs from the last observed one (M3).
    private void pollDimensions(MinecraftServer server) {
        if (!mgr.isRecording()) return;
        for (ServerPlayerEntity player : onlinePlayers(server)) {
            String uuid = playerUuid(player);
            if (uuid.isEmpty()) continue;
            String curDim = dimKey(player);
            String prev = lastDimByUuid.put(uuid, curDim);
            if (prev == null || prev.equals(curDim)) continue;
            handleDimensionChange(player, curDim);
        }
    }

    private void handleDimensionChange(ServerPlayerEntity player, String newDim) {
        String uuid = playerUuid(player);
        boolean each = config.getPerspective() == ReplayConfig.Perspective.EACH;
        String oldKey = each ? "p_" + uuid : cameraKeyByUuid.get(uuid);
        if (oldKey == null) return; // SHARED：非镜头玩家，无需处理
        Channel ch = channelOfPlayer(player);
        if (ch != null) PacketCapture.remove(ch);
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

    // Fabric (Yarn) exposes the online players via MinecraftServer#getPlayerManager().getPlayerList();
    // Mojang mappings use getPlayerList().getPlayers(). Reflect to stay mapping-agnostic.
    @SuppressWarnings("unchecked")
    private static java.util.List<ServerPlayerEntity> onlinePlayers(MinecraftServer server) {
        try {
            Object pm = tryInvoke(server, "getPlayerManager");   // Yarn
            if (pm == null) pm = tryInvoke(server, "getPlayerList"); // Mojang
            if (pm == null) return java.util.Collections.emptyList();
            Object list = tryInvoke(pm, "getPlayerList");        // PlayerManager / PlayerList
            if (list == null) list = tryInvoke(pm, "getPlayers");
            if (list instanceof java.util.List) {
                return (java.util.List<ServerPlayerEntity>) list;
            }
        } catch (Exception ignored) {}
        return java.util.Collections.emptyList();
    }

    private boolean shouldRecord(String dim) {
        if (config.getMode() == ReplayConfig.Mode.DUEL) {
            return dim.equals(config.getDuelDimension());
        }
        return true; // ARENA：仅记录有人的维度（天然满足）
    }

    private ReplayMeta buildMeta(Object player) {
        ReplayMeta meta = new ReplayMeta();
        meta.setProtocol(config.getProtocol());
        meta.setMcVersion(config.getMcVersion());
        meta.setDate(System.currentTimeMillis());
        meta.getPlayers().add(new ReplayMeta.PlayerInfo(playerName(player), playerUuid(player)));
        meta.setSelfId(config.getPerspective() == ReplayConfig.Perspective.EACH ? playerId(player) : -1);
        return meta;
    }

    // ---- mapping-agnostic (Yarn / Mojang) reflection accessors ----
    // Fabric ships Yarn mappings (net.minecraft.server.network.ServerPlayerEntity),
    // NeoForge ships Mojang mappings (net.minecraft.server.level.ServerPlayer).
    // Using reflection on Object keeps this module compatible with either.

    private static Object playerOf(ServerPlayNetworkHandler h) {
        try { return tryField(h, "player"); }
        catch (Exception e) { return null; }
    }

    private static Channel channelOf(Object h) {
        try {
            Object conn = tryInvoke(h, "getConnection");
            if (conn == null) conn = tryField(h, "connection");
            if (conn == null) return null;
            Object ch = tryInvoke(conn, "channel");
            return (Channel) ch;
        } catch (Exception e) { return null; }
    }

    private static Channel channelOfPlayer(Object player) {
        Object handler = tryInvoke(player, "networkHandler"); // Yarn: ServerPlayerEntity#getNetworkHandler
        if (handler == null) handler = tryInvoke(player, "connection"); // Mojang
        return handler != null ? channelOf(handler) : null;
    }

    private static String dimKey(Object player) {
        try {
            Object level = tryInvoke(player, "getWorld");        // Yarn
            if (level == null) level = tryInvoke(player, "level"); // Mojang
            if (level == null) level = tryField(player, "world");
            if (level == null) return "minecraft:overworld";
            Object dim = tryInvoke(level, "getDimensionKey");    // Yarn
            if (dim == null) dim = tryInvoke(level, "dimension"); // Mojang
            if (dim == null) return "minecraft:overworld";
            Object loc = tryInvoke(dim, "getValue");            // Yarn ResourceKey#getValue
            if (loc == null) loc = tryInvoke(dim, "location"); // Mojang ResourceKey#location
            if (loc == null) loc = tryField(dim, "location");
            return loc != null ? loc.toString() : "minecraft:overworld";
        } catch (Exception e) { return "minecraft:overworld"; }
    }

    private static String playerName(Object p) {
        try {
            Object comp = tryInvoke(p, "getName");
            if (comp == null) return "player";
            Object s = tryInvoke(comp, "getString");
            return s != null ? String.valueOf(s) : "player";
        } catch (Exception e) { return "player"; }
    }

    private static String playerUuid(Object p) {
        try {
            Object u = tryInvoke(p, "getUUID");       // Mojang
            if (u == null) u = tryInvoke(p, "getUuid"); // Yarn
            return u != null ? u.toString() : "";
        } catch (Exception e) { return ""; }
    }

    private static int playerId(Object p) {
        try { return (int) tryInvoke(p, "getId"); } catch (Exception e) { return -1; }
    }

    private static Object tryInvoke(Object o, String name) {
        if (o == null) return null;
        try { Method m = o.getClass().getMethod(name); m.setAccessible(true); return m.invoke(o); }
        catch (Exception e) { return null; }
    }

    private static Object tryField(Object o, String name) {
        if (o == null) return null;
        try { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
        catch (Exception e) { return null; }
    }

    private static String sanitize(String s) { return s == null ? "unknown" : s.replace(':', '_'); }

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
