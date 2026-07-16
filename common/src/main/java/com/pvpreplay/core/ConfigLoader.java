package com.pvpreplay.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads {@link ReplayConfig} from a commentable .properties file and writes it
 * back with a human-readable header. Defaults are applied for any missing key
 * so the file is self-documenting on first run.
 */
public final class ConfigLoader {

    private static final String HEADER = String.join("\n",
            "# PvpReplay 配置文件",
            "# mode: ARENA = 天坑/职业战争 风格（大地图，所有人同场，记录所有在线玩家维度）",
            "#       DUEL  = 1v1 风格（小地图，单维度竞技场，仅录 duel.dimension）",
            "# perspective: EACH  = 每个玩家各自一份回放（轻量、永远连贯）",
            "#              SHARED = 同维度只录一份（取首位加入玩家为镜头，适合 Arena）",
            "# max-disk-gb: 回放占用磁盘上限(GB)，超过则删除最旧回放（3 = 默认）",
            "# max-days:    回放最大保留天数，超过则删除（0 = 不限制天数）",
            "# max-duration-min: 单场回放最长分钟数（0 = 直到触发上限）",
            "",
            "");

    private ConfigLoader() {}

    public static ReplayConfig load(Path file) throws IOException {
        ReplayConfig cfg = new ReplayConfig();
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        cfg.setEnabled(bool(p, "replay.enabled", cfg.isEnabled()));
        cfg.setMode(ReplayConfig.parseMode(str(p, "replay.mode", cfg.getMode().name())));
        cfg.setPerspective(ReplayConfig.parsePerspective(str(p, "replay.perspective", cfg.getPerspective().name())));
        cfg.setDuelDimension(str(p, "replay.duel.dimension", cfg.getDuelDimension()));
        cfg.setArenaOnlyOccupied(bool(p, "replay.arena.only-occupied", cfg.isArenaOnlyOccupied()));
        cfg.setMaxDiskGb(dbl(p, "replay.max-disk-gb", cfg.getMaxDiskGb()));
        cfg.setMaxDays(intv(p, "replay.max-days", cfg.getMaxDays()));
        cfg.setMaxDurationMin(intv(p, "replay.max-duration-min", cfg.getMaxDurationMin()));
        cfg.setFlushIntervalMs(longv(p, "replay.flush-interval-ms", cfg.getFlushIntervalMs()));
        cfg.setOutputDir(str(p, "replay.output-dir", cfg.getOutputDir()));
        return cfg;
    }

    public static void save(Path file, ReplayConfig cfg) throws IOException {
        Properties p = new Properties();
        p.setProperty("replay.enabled", String.valueOf(cfg.isEnabled()));
        p.setProperty("replay.mode", cfg.getMode().name());
        p.setProperty("replay.perspective", cfg.getPerspective().name());
        p.setProperty("replay.duel.dimension", cfg.getDuelDimension());
        p.setProperty("replay.arena.only-occupied", String.valueOf(cfg.isArenaOnlyOccupied()));
        p.setProperty("replay.max-disk-gb", String.valueOf(cfg.getMaxDiskGb()));
        p.setProperty("replay.max-days", String.valueOf(cfg.getMaxDays()));
        p.setProperty("replay.max-duration-min", String.valueOf(cfg.getMaxDurationMin()));
        p.setProperty("replay.flush-interval-ms", String.valueOf(cfg.getFlushIntervalMs()));
        p.setProperty("replay.output-dir", cfg.getOutputDir());
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, HEADER);
        }
    }

    private static String str(Properties p, String k, String d) {
        String v = p.getProperty(k);
        return v == null ? d : v;
    }
    private static boolean bool(Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        return v == null ? d : Boolean.parseBoolean(v.trim());
    }
    private static double dbl(Properties p, String k, double d) {
        String v = p.getProperty(k);
        if (v == null) return d;
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return d; }
    }
    private static int intv(Properties p, String k, int d) {
        String v = p.getProperty(k);
        if (v == null) return d;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return d; }
    }
    private static long longv(Properties p, String k, long d) {
        String v = p.getProperty(k);
        if (v == null) return d;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return d; }
    }
}
