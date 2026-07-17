package com.pvpreplay.capture;

import com.pvpreplay.core.ReplayLogger;
import com.pvpreplay.core.ReplayManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

import java.lang.reflect.Method;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Unified, loader-agnostic packet capture.
 *
 * <p>Works on Fabric, NeoForge and Paper/Leaf because all three run the same
 * Netty {@code ClientConnection} pipeline. We inject a {@link ChannelDuplexHandler}
 * <em>before</em> the game's {@code "encoder"} handler. On every outbound
 * (clientbound) message we:
 * <ol>
 *   <li>re-encode the packet by reflectively invoking the encoder's
 *       {@code encode(ctx, packet, buf)} — yielding the exact bytes the client
 *       would receive (VarInt packetId + payload);</li>
 *   <li>hand those bytes to the {@link ReplayManager} streamed to disk;</li>
 *   <li>forward the original packet unchanged so the player is unaffected.</li>
 * </ol>
 *
 * <p>Reflection only relies on the stable handler <em>name</em> {@code "encoder"}
 * and the method <em>name</em> {@code "encode"} — robust across MC 1.20–1.21.x
 * and across obfuscation.
 *
 * <h2>C0 — early (login-phase) capture</h2>
 * The handler is injected at the <em>login</em> stage (see each loader's
 * {@code onLogin}). At that point the session key is not yet known and no
 * {@link ReplayManager} session exists, so outbound packets are buffered in a
 * thread-safe queue with a timestamp relative to the connection moment
 * ({@code startNanos}). When the play session begins ({@link #beginSession}),
 * the buffered packets are flushed (in timestamp order) into the session and
 * the handler switches to live capture. This guarantees the produced {@code .mcpr}
 * starts at the very first clientbound packet (Login / Respawn / initial chunks /
 * player entity id), not mid-stream.
 *
 * <p>Thread safety: {@link #write} runs on the connection's Netty event-loop
 * thread; {@link #beginSession} / {@link #discard} run on the server main thread.
 * The buffer↔live transition is guarded by {@code synchronized(this)} in both the
 * capture branch and the transition methods, so the flush is atomic and the
 * captured stream stays monotonic by timestamp with no lost packets.
 */
public final class PacketCapture {

    public static final String HANDLER_NAME = "pvp_replay_capture";

    private PacketCapture() {}

    /**
     * Attach a capture handler to a player's connection at the <em>login</em> stage.
     *
     * <p>The handler buffers outbound packets until {@link #beginSession} is called
     * from the play phase. The session key is supplied later, so it is intentionally
     * not passed here.
     *
     * @param channel  the player's netty channel
     * @param mgr      the replay manager
     * @param log      logger
     */
    public static void inject(Channel channel, ReplayManager mgr, ReplayLogger log) {
        if (channel == null || !channel.isActive()) return;
        ChannelPipeline pipe = channel.pipeline();
        if (pipe.get(HANDLER_NAME) != null) return;          // already injected
        ChannelHandler encoder = pipe.get("encoder");
        if (encoder == null) {
            log.warn("未在管线中找到 'encoder' 处理器，跳过抓包");
            return;
        }
        try {
            Method encodeMethod = findEncode(encoder);
            pipe.addBefore("encoder", HANDLER_NAME,
                    new CaptureHandler(mgr, log, encoder, encodeMethod));
        } catch (Exception e) {
            log.error("注入抓包处理器失败", e);
        }
    }

    public static void remove(Channel channel) {
        if (channel == null || !channel.isActive()) return;
        ChannelPipeline pipe = channel.pipeline();
        if (pipe.get(HANDLER_NAME) != null) {
            pipe.remove(HANDLER_NAME);
        }
    }

    /** Make the handler on {@code channel} live for session {@code key}, flushing the
     *  login-phase buffer first. No-op if no handler is present. */
    public static void beginSession(Channel channel, String key, ReplayManager mgr, ReplayLogger log) {
        CaptureHandler h = handlerOf(channel);
        if (h != null) h.beginSession(key, mgr);
    }

    /** Drop any buffered packets on {@code channel} and stop capturing. Used for
     *  SHARED non-camera players and DUEL non-target dimensions. */
    public static void discard(Channel channel) {
        CaptureHandler h = handlerOf(channel);
        if (h != null) h.discard();
    }

    /** Connection moment (t=0 anchor) of the handler on {@code channel}, or -1 if absent. */
    public static long getConnectionStartNanos(Channel channel) {
        CaptureHandler h = handlerOf(channel);
        return h != null ? h.getConnectionStartNanos() : -1L;
    }

    private static CaptureHandler handlerOf(Channel channel) {
        if (channel == null || !channel.isActive()) return null;
        ChannelPipeline pipe = channel.pipeline();
        ChannelHandler h = pipe.get(HANDLER_NAME);
        return h instanceof CaptureHandler ? (CaptureHandler) h : null;
    }

    private static Method findEncode(ChannelHandler encoder) throws NoSuchMethodException {
        // MessageToByteEncoder.encode(ChannelHandlerContext, I msg, ByteBuf out)
        // erases to (ChannelHandlerContext, Object, ByteBuf).
        Method m = encoder.getClass().getDeclaredMethod("encode",
                ChannelHandlerContext.class, Object.class, ByteBuf.class);
        m.setAccessible(true);
        return m;
    }

    /** One buffered clientbound packet with its timestamp relative to {@code startNanos}. */
    private static final class Buf {
        final long tsMs;
        final byte[] data;
        Buf(long tsMs, byte[] data) { this.tsMs = tsMs; this.data = data; }
    }

    private static final class CaptureHandler extends ChannelDuplexHandler {
        private final ReplayManager mgr;
        private final ReplayLogger log;
        private final ChannelHandler encoder;
        private final Method encodeMethod;
        private final long startNanos;

        // Guarded by synchronized(this):
        private volatile String key;          // set once in beginSession, then immutable
        private boolean live;                 // false = buffer, true = live write
        private boolean discarded;            // true = drop everything, no buffering
        private final Queue<Buf> buffer = new ConcurrentLinkedQueue<>();

        CaptureHandler(ReplayManager mgr, ReplayLogger log,
                       ChannelHandler encoder, Method encodeMethod) {
            this.mgr = mgr;
            this.log = log;
            this.encoder = encoder;
            this.encodeMethod = encodeMethod;
            this.startNanos = System.nanoTime(); // connection moment == t=0
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (mgr.isRecording()) {
                byte[] encoded = null;
                try {
                    encoded = encodePacket(ctx, encoder, encodeMethod, msg);
                } catch (Exception ignored) {
                    // msg was not a Packet (or encode failed) — just forward it.
                }
                if (encoded != null && encoded.length > 0) {
                    boolean doLive = false;
                    String liveKey = null;
                    synchronized (this) {
                        if (discarded) {
                            // drop capture; forward only
                        } else if (live) {
                            doLive = true;
                            liveKey = key;
                        } else {
                            buffer.add(new Buf((System.nanoTime() - startNanos) / 1_000_000L, encoded));
                        }
                    }
                    if (doLive) mgr.writePacket(liveKey, encoded);
                }
            }
            ctx.write(msg, promise);
        }

        /** Switch from buffering to live capture for session {@code key}, flushing the
         *  login-phase buffer first. Atomic with respect to {@link #write}. */
        void beginSession(String key, ReplayManager mgr) {
            synchronized (this) {
                this.key = key;
                Buf b;
                while ((b = buffer.poll()) != null) {
                    // Preserve the capture-time timestamp so the Login / Respawn
                    // ordering is intact in the produced replay.
                    mgr.writePacketAt(key, b.tsMs, b.data);
                }
                this.live = true;
            }
        }

        /** Discard buffered packets and stop capturing further ones. */
        void discard() {
            synchronized (this) {
                buffer.clear();
                this.discarded = true;
            }
        }

        long getConnectionStartNanos() { return startNanos; }

        private static byte[] encodePacket(ChannelHandlerContext ctx,
                                           ChannelHandler encoder, Method encodeMethod,
                                           Object packet) throws Exception {
            ByteBuf buf = Unpooled.buffer();
            try {
                encodeMethod.invoke(encoder, ctx, packet, buf);
                if (buf.readableBytes() == 0) return null;
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return data;
            } finally {
                buf.release();
            }
        }
    }
}
