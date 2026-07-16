package com.pvpreplay.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Platform-agnostic session registry. The loader injects capture handlers that
 * feed raw clientbound packet bytes here; this class owns the lifecycle of each
 * {@link ReplayWriter} and enforces global limits.
 *
 * <p>Memory stays flat regardless of replay length: each writer streams to disk.
 */
public class ReplayManager {

    private final ReplayConfig config;
    private final ReplayLogger log;
    private final Path outDir;
    private final StorageManager storage;
    private final Map<String, Session> active = new ConcurrentHashMap<>();

    private volatile boolean recording = true;

    public ReplayManager(ReplayConfig config, ReplayLogger log, Path baseDir) {
        this.config = config;
        this.log = log;
        this.outDir = baseDir.resolve(config.getOutputDir());
        this.storage = new StorageManager(outDir, log);
    }

    public Path getOutputDir() { return outDir; }

    public boolean isRecording() { return recording && config.isEnabled(); }

    public void setRecording(boolean v) { recording = v; }

    /** @return true if a session was newly created. */
    public boolean startSession(String key, ReplayMeta meta) {
        if (!isRecording()) return false;
        Session s = active.get(key);
        if (s != null) return false;
        try {
            ReplayWriter w = new ReplayWriter(outDir, key, meta);
            w.start();
            long startWall = System.currentTimeMillis();
            long startNanos = System.nanoTime();
            active.put(key, new Session(w, meta, startWall, startNanos));
            log.info("开始回放会话: " + key);
            return true;
        } catch (IOException e) {
            log.error("无法开始回放会话 " + key, e);
            return false;
        }
    }

    public boolean hasSession(String key) { return active.containsKey(key); }

    public void writePacket(String key, long absTsMs, byte[] encoded) {
        Session s = active.get(key);
        if (s == null) return;
        try {
            s.writer.writePacket(absTsMs, encoded);
            if (config.getMaxDurationMin() > 0) {
                long elapsedMin = (System.nanoTime() - s.startNanos) / 60_000_000_000L;
                if (elapsedMin >= config.getMaxDurationMin()) {
                    log.info("回放达到时长上限，结束: " + key);
                    endSession(key);
                }
            }
        } catch (IOException e) {
            log.error("写入回放包失败 " + key, e);
        }
    }

    public void endSession(String key) {
        Session s = active.remove(key);
        if (s == null) return;
        try {
            s.writer.close();
            log.info("回放会话结束: " + key + " (" + s.writer.getFrameCount() + " 帧)");
        } catch (IOException e) {
            log.error("关闭回放会话失败 " + key, e);
        }
        storage.enforce(config.getMaxDiskGb(), config.getMaxDays());
    }

    /** Run the disk-quota / age-limit sweep on demand (e.g. on a timer). */
    public void enforceLimits() {
        storage.enforce(config.getMaxDiskGb(), config.getMaxDays());
    }

    /** End every active session (e.g. on server shutdown). */
    public void closeAll() {
        for (String key : new java.util.ArrayList<>(active.keySet())) endSession(key);
    }

    private static final class Session {
        final ReplayWriter writer;
        final ReplayMeta meta;
        final long startWall;
        final long startNanos;

        Session(ReplayWriter writer, ReplayMeta meta, long startWall, long startNanos) {
            this.writer = writer;
            this.meta = meta;
            this.startWall = startWall;
            this.startNanos = startNanos;
        }
    }
}
