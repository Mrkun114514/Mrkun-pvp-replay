package com.pvpreplay.fabric;

import com.pvpreplay.capture.PacketCapture;
import com.pvpreplay.core.ConfigLoader;
import com.pvpreplay.core.ReplayConfig;
import com.pvpreplay.core.ReplayLogger;
import com.pvpreplay.core.ReplayManager;
import com.pvpreplay.core.ReplayMeta;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import io.netty.channel.Channel;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class PvpReplayFabric implements ModInitializer {

    private ReplayConfig config;
    private ReplayManager mgr;
    private final ReplayLogger log = new FabricLogger();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pvp-replay-sweeper");
        t.setDaemon(true);
        return t;
    });

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

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onLeave(handler));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> startSweeper());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> mgr.closeAll());
    }

    private void startSweeper() {
        sweeper.scheduleAtFixedRate(() -> {
            try { mgr.enforceLimits(); } catch (Throwable t) { log.error("限额清理异常", t); }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void onJoin(ServerPlayNetworkHandler handler) {
        Object player = playerOf(handler);
        if (player == null || !mgr.isRecording()) return;
        String dim = dimKey(player);
        if (!shouldRecord(dim)) return;

        ReplayMeta meta = buildMeta(player);
        final String key;
        if (config.getPerspective() == ReplayConfig.Perspective.EACH) {
            key = "p_" + playerUuid(player);
            mgr.startSession(key, meta);
        } else {
            key = "dim_" + sanitize(dim);
            if (mgr.hasSession(key)) {
                return; // 该维度已有镜头，无需重复
            }
            mgr.startSession(key, meta);
        }

        Channel ch = channelOf(handler);
        long injectNanos = System.nanoTime();
        LongSupplier clock = () -> (System.nanoTime() - injectNanos) / 1_000_000L;
        PacketCapture.inject(ch, key, mgr, log, clock);
    }

    private void onLeave(ServerPlayNetworkHandler handler) {
        Object player = playerOf(handler);
        if (player == null) return;
        String dim = dimKey(player);
        final String key = (config.getPerspective() == ReplayConfig.Perspective.EACH)
                ? "p_" + playerUuid(player)
                : "dim_" + sanitize(dim);
        PacketCapture.remove(channelOf(handler));
        mgr.endSession(key);
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
        try { return field(h, "player"); }
        catch (Exception e) { return null; }
    }

    private static Channel channelOf(ServerPlayNetworkHandler h) {
        try {
            Object conn = invoke(h, "getConnection");
            if (conn == null) conn = field(h, "connection");
            if (conn == null) return null;
            Object ch = invoke(conn, "channel");
            return (Channel) ch;
        } catch (Exception e) { return null; }
    }

    private static String dimKey(Object player) {
        try {
            Object level = invoke(player, "level");
            if (level == null) level = invoke(player, "getWorld");
            if (level == null) level = field(player, "level");
            Object dim = invoke(level, "dimension");
            Object loc = invoke(dim, "location");        // Mojang ResourceKey#location
            if (loc == null) loc = invoke(dim, "getValue"); // Yarn ResourceKey#getValue
            if (loc == null) loc = field(dim, "location");
            return loc != null ? loc.toString() : "minecraft:overworld";
        } catch (Exception e) { return "minecraft:overworld"; }
    }

    private static String playerName(Object p) {
        try { Object comp = invoke(p, "getName"); return String.valueOf(invoke(comp, "getString")); }
        catch (Exception e) { return "player"; }
    }

    private static String playerUuid(Object p) {
        try {
            Object u = invoke(p, "getUUID");       // Mojang
            if (u == null) u = invoke(p, "getUuid"); // Yarn
            return u != null ? u.toString() : "";
        } catch (Exception e) { return ""; }
    }

    private static int playerId(Object p) {
        try { return (int) invoke(p, "getId"); } catch (Exception e) { return -1; }
    }

    private static Object invoke(Object o, String name) throws Exception {
        if (o == null) return null;
        Method m = o.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(o);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
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
