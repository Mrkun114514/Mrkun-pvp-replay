package com.pvpreplay.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Enforces the disk-quota and age-limit policy for finished replays.
 *
 * <ul>
 *   <li>{@code maxDiskGb > 0} → total size of {@code .mcpr} files must stay
 *       under the cap; oldest files are deleted first.</li>
 *   <li>{@code maxDays > 0} → any replay older than N days is deleted.</li>
 * </ul>
 *
 * Runs only occasionally (platform schedules it), so it adds negligible load.
 */
public class StorageManager {

    private final Path outDir;
    private final ReplayLogger log;

    public StorageManager(Path outDir, ReplayLogger log) {
        this.outDir = outDir;
        this.log = log;
    }

    public long totalBytes() throws IOException {
        long sum = 0;
        for (Path p : listReplays()) sum += Files.size(p);
        return sum;
    }

    public int enforce(double maxDiskGb, int maxDays) {
        int removed = 0;
        try {
            List<Path> all = listReplays();
            if (all.isEmpty()) return 0;

            // 1) age limit
            if (maxDays > 0) {
                long cutoff = System.currentTimeMillis() - (long) maxDays * 86_400_000L;
                for (Path p : all) {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        if (Files.deleteIfExists(p)) {
                            removed++;
                            log.info("删除过期回放(> " + maxDays + "天): " + p.getFileName());
                        }
                    }
                }
            }

            // 2) disk quota — delete oldest until under the cap
            if (maxDiskGb > 0) {
                long cap = (long) (maxDiskGb * 1_000_000_000L);
                List<Path> remaining = listReplays();
                remaining.sort((a, b) -> {
                    try { return Long.compare(Files.getLastModifiedTime(a).toMillis(), Files.getLastModifiedTime(b).toMillis()); }
                    catch (IOException e) { return 0; }
                });
                long used = 0;
                for (Path p : remaining) used += Files.size(p);
                for (Path p : remaining) {
                    if (used <= cap) break;
                    long size = Files.size(p);
                    if (Files.deleteIfExists(p)) {
                        used -= size;
                        removed++;
                        log.info("删除最旧回放以释放空间: " + p.getFileName());
                    }
                }
            }
        } catch (IOException e) {
            log.error("回放清理失败", e);
        }
        return removed;
    }

    private List<Path> listReplays() throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(outDir)) return result;
        try (var stream = Files.list(outDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mcpr"))
                  .forEach(result::add);
        }
        return result;
    }
}
