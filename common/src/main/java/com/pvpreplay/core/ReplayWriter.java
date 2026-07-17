package com.pvpreplay.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams a ReplayMod-compatible {@code .mcpr} replay to disk with bounded
 * memory: packet bytes are written straight to a temp file, and only a single
 * small buffer lives in the heap. The final zip is assembled on close.
 *
 * <p>Frame layout inside {@code recording.tmcpr} (ReplayMod format):
 * <pre>
 *   [int32 BE timestampMs][int32 BE length][raw encoded packet: VarInt packetId + payload]...
 * </pre>
 * Each timestamp is cumulative milliseconds since the session started.
 */
public class ReplayWriter implements AutoCloseable {

    private final Path outDir;
    private final String sessionId;
    private final ReplayMeta meta;
    private Path tmpFile;
    private OutputStream out;
    private long lastTs = -1;
    private long firstAbs = 0;
    private long lastAbs = 0;
    private boolean started = false;
    private boolean closed = false;
    private long frames = 0;

    public ReplayWriter(Path outDir, String sessionId, ReplayMeta meta) {
        this.outDir = outDir;
        this.sessionId = sessionId;
        this.meta = meta;
    }

    public void start() throws IOException {
        if (started) return;
        Files.createDirectories(outDir);
        tmpFile = outDir.resolve("recording_" + sessionId + ".tmcpr.tmp");
        out = new BufferedOutputStream(Files.newOutputStream(tmpFile), 1 << 16);
        started = true;
    }

    /**
     * @param tsMs    cumulative timestamp in milliseconds since the session started
     * @param encoded full encoded clientbound packet (VarInt packetId + payload)
     */
    public void writePacket(long tsMs, byte[] encoded) throws IOException {
        if (!started || closed || encoded == null || encoded.length == 0) return;
        if (lastTs < 0) firstAbs = tsMs;
        lastAbs = tsMs;
        writeIntBE(out, (int) tsMs);      // 4-byte big-endian cumulative ms timestamp
        writeIntBE(out, encoded.length); // 4-byte big-endian length prefix
        out.write(encoded, 0, encoded.length);
        lastTs = tsMs;
        frames++;
    }

    /** Write a 4-byte big-endian integer (matches ReplayStudio util/Utils.writeInt). */
    private static void writeIntBE(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>>  8) & 0xFF);
        out.write( v        & 0xFF);
    }

    public long getFrameCount() { return frames; }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (out != null) {
            try { out.flush(); } finally { out.close(); }
        }
        if (frames == 0) {                       // nothing was captured
            if (tmpFile != null) Files.deleteIfExists(tmpFile);
            return;                             // do not produce an empty .mcpr
        }
        long duration = Math.max(0, lastAbs - firstAbs);
        meta.setDuration(duration);
        buildZip();
        if (tmpFile != null) Files.deleteIfExists(tmpFile);
    }

    private void buildZip() throws IOException {
        Path zip = uniqueName(outDir, sessionId + ".mcpr");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry metaEntry = new ZipEntry("metaData.json");
            zos.putNextEntry(metaEntry);
            zos.write(metaJson().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ZipEntry recEntry = new ZipEntry("recording.tmcpr");
            zos.putNextEntry(recEntry);
            try (InputStream in = Files.newInputStream(tmpFile)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) >= 0) zos.write(buf, 0, n);
            }
            zos.closeEntry();
        }
    }

    private static Path uniqueName(Path dir, String base) throws IOException {
        Path p = dir.resolve(base);
        if (!Files.exists(p)) return p;
        String name = base;
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        int i = 1;
        do {
            p = dir.resolve(stem + "_" + i + ext);
            i++;
        } while (Files.exists(p));
        return p;
    }

    private String metaJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"fileFormat\":\"MCPR\",");
        sb.append("\"fileFormatVersion\":14,");
        sb.append("\"singleplayer\":false,");
        sb.append("\"generator\":").append(quote(meta.getGenerator())).append(',');
        sb.append("\"protocol\":").append(meta.getProtocol()).append(',');
        sb.append("\"mcversion\":").append(quote(meta.getMcVersion())).append(',');
        sb.append("\"serverName\":").append(quote(meta.getServerName())).append(',');
        sb.append("\"date\":").append(meta.getDate()).append(',');
        sb.append("\"duration\":").append(meta.getDuration()).append(',');
        sb.append("\"players\":[");
        for (int i = 0; i < meta.getPlayers().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(meta.getPlayers().get(i).uuid));
        }
        sb.append("],");
        sb.append("\"selfId\":").append(meta.getSelfId());
        sb.append('}');
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Minecraft-style VarInt (little-endian, 7 bits per byte). */
    public static void writeVarInt(OutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }
}
