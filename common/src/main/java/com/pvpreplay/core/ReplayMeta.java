package com.pvpreplay.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata describing a single replay session. Mirrors the ReplayMod
 * {@code meta_data.json} schema so the produced {@code .mcpr} can be opened by
 * the ReplayMod client directly.
 */
public class ReplayMeta {

    public static class PlayerInfo {
        public final String name;
        public final String uuid; // dashed UUID string

        public PlayerInfo(String name, String uuid) {
            this.name = name;
            this.uuid = uuid;
        }
    }

    private String generator = "PvpReplay/1.0";
    private int protocol;
    private String mcVersion = "";
    private String serverName = "PvpReplay";
    private long date; // epoch millis when the session started
    private long duration; // millis, filled on close
    private final List<PlayerInfo> players = new ArrayList<>();
    private int selfId = -1; // recording player's entity id (-1 for SHARED)

    public String getGenerator() { return generator; }
    public void setGenerator(String v) { generator = v; }

    public int getProtocol() { return protocol; }
    public void setProtocol(int v) { protocol = v; }

    public String getMcVersion() { return mcVersion; }
    public void setMcVersion(String v) { mcVersion = v; }

    public String getServerName() { return serverName; }
    public void setServerName(String v) { serverName = v; }

    public long getDate() { return date; }
    public void setDate(long v) { date = v; }

    public long getDuration() { return duration; }
    public void setDuration(long v) { duration = v; }

    public List<PlayerInfo> getPlayers() { return players; }

    public int getSelfId() { return selfId; }
    public void setSelfId(int v) { selfId = v; }
}
