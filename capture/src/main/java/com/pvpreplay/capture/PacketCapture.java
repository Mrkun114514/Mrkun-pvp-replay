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
import java.util.function.LongSupplier;

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
 */
public final class PacketCapture {

    public static final String HANDLER_NAME = "pvp_replay_capture";

    private PacketCapture() {}

    /**
     * Attach a capture handler to a player's connection.
     *
     * @param channel  the player's netty channel
     * @param key      session key (player uuid, or dimension key for SHARED)
     * @param mgr      the replay manager
     * @param log      logger
     * @param clock    returns milliseconds since the session started
     */
    public static void inject(Channel channel, String key, ReplayManager mgr,
                              ReplayLogger log, LongSupplier clock) {
        if (channel == null || !channel.isActive()) return;
        ChannelPipeline pipe = channel.pipeline();
        if (pipe.get(HANDLER_NAME) != null) return;          // already injected
        ChannelHandler encoder = pipe.get("encoder");
        if (encoder == null) {
            log.warn("未在管线中找到 'encoder' 处理器，跳过抓包: " + key);
            return;
        }
        try {
            Method encodeMethod = findEncode(encoder);
            pipe.addBefore("encoder", HANDLER_NAME,
                    new CaptureHandler(key, mgr, log, clock, encoder, encodeMethod));
        } catch (Exception e) {
            log.error("注入抓包处理器失败: " + key, e);
        }
    }

    public static void remove(Channel channel) {
        if (channel == null || !channel.isActive()) return;
        ChannelPipeline pipe = channel.pipeline();
        if (pipe.get(HANDLER_NAME) != null) {
            pipe.remove(HANDLER_NAME);
        }
    }

    private static Method findEncode(ChannelHandler encoder) throws NoSuchMethodException {
        // MessageToByteEncoder.encode(ChannelHandlerContext, I msg, ByteBuf out)
        // erases to (ChannelHandlerContext, Object, ByteBuf).
        Method m = encoder.getClass().getDeclaredMethod("encode",
                ChannelHandlerContext.class, Object.class, ByteBuf.class);
        m.setAccessible(true);
        return m;
    }

    private static final class CaptureHandler extends ChannelDuplexHandler {
        private final String key;
        private final ReplayManager mgr;
        private final ReplayLogger log;
        private final LongSupplier clock;
        private final ChannelHandler encoder;
        private final Method encodeMethod;

        CaptureHandler(String key, ReplayManager mgr, ReplayLogger log,
                       LongSupplier clock, ChannelHandler encoder, Method encodeMethod) {
            this.key = key;
            this.mgr = mgr;
            this.log = log;
            this.clock = clock;
            this.encoder = encoder;
            this.encodeMethod = encodeMethod;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (mgr.isRecording()) {
                try {
                    byte[] encoded = encodePacket(ctx, encoder, encodeMethod, msg);
                    if (encoded != null && encoded.length > 0) {
                        mgr.writePacket(key, clock.getAsLong(), encoded);
                    }
                } catch (Exception ignored) {
                    // msg was not a Packet (or encode failed) — just forward it.
                }
            }
            ctx.write(msg, promise);
        }

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
