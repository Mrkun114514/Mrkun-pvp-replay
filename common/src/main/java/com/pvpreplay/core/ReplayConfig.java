package com.pvpreplay.core;

import java.util.Locale;

/**
 * Holds all tunable replay behaviour. Loaded/saved as a commentable .properties
 * file by {@link ConfigLoader}. No Minecraft dependency — safe to use on any JVM.
 */
public class ReplayConfig {

    /** Recording scope. */
    public enum Mode {
        /** 天坑 / 职业战争 风格：大地图，所有人同场，记录全部在线玩家所在维度。 */
        ARENA,
        /** 1v1 风格：小地图，单维度竞技场，仅记录指定维度。 */
        DUEL
    }

    /** Who the camera is. */
    public enum Perspective {
        /** 每个玩家各自一份回放（轻量、永远连贯）。 */
        EACH,
        /** 同一维度只录一份回放（取首位加入的玩家作为镜头）。适合 Arena。 */
        SHARED
    }

    private boolean enabled = true;
    private Mode mode = Mode.ARENA;
    private Perspective perspective = Perspective.EACH;
    private String duelDimension = "minecraft:overworld";
    private boolean arenaOnlyOccupied = true;
    private double maxDiskGb = 3.0;
    private int maxDays = 7;
    private int maxDurationMin = 0; // 0 = 直到触发上限
    private long flushIntervalMs = 1000; // 仅用于后台统计/轮询节拍
    private String outputDir = "replays";

    // 以下两项由平台在运行时填充（与具体 MC 版本绑定）
    private int protocol = 0;
    private String mcVersion = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }

    public Mode getMode() { return mode; }
    public void setMode(Mode v) { mode = v; }

    public Perspective getPerspective() { return perspective; }
    public void setPerspective(Perspective v) { perspective = v; }

    public String getDuelDimension() { return duelDimension; }
    public void setDuelDimension(String v) { duelDimension = v; }

    public boolean isArenaOnlyOccupied() { return arenaOnlyOccupied; }
    public void setArenaOnlyOccupied(boolean v) { arenaOnlyOccupied = v; }

    public double getMaxDiskGb() { return maxDiskGb; }
    public void setMaxDiskGb(double v) { maxDiskGb = v; }

    public int getMaxDays() { return maxDays; }
    public void setMaxDays(int v) { maxDays = v; }

    public int getMaxDurationMin() { return maxDurationMin; }
    public void setMaxDurationMin(int v) { maxDurationMin = v; }

    public long getFlushIntervalMs() { return flushIntervalMs; }
    public void setFlushIntervalMs(long v) { flushIntervalMs = v; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String v) { outputDir = v; }

    public int getProtocol() { return protocol; }
    public void setProtocol(int v) { protocol = v; }

    public String getMcVersion() { return mcVersion; }
    public void setMcVersion(String v) { mcVersion = v; }

    public static Mode parseMode(String s) {
        try { return Mode.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return Mode.ARENA; }
    }

    public static Perspective parsePerspective(String s) {
        try { return Perspective.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return Perspective.EACH; }
    }
}
