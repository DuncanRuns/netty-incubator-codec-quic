/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.codec.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.MessageSizeEstimator;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static io.netty.incubator.codec.quic.Quiche.allocateNativeOrder;

/**
 * Abstract base class for QUIC codecs.
 */
abstract class QuicheQuicCodec extends ChannelDuplexHandler {
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(QuicheQuicCodec.class);

    private final Map<ByteBuffer, QuicheQuicChannel> connectionIdToChannel = new HashMap<>();
    private final Set<QuicheQuicChannel> channels = new HashSet<>();
    private final Queue<QuicheQuicChannel> needsFireChannelReadComplete = new ArrayDeque<>();
    private final int maxTokenLength;
    private final FlushStrategy flushStrategy;

    private MessageSizeEstimator.Handle estimatorHandle;
    private QuicHeaderParser headerParser;
    private QuicHeaderParser.QuicHeaderProcessor parserCallback;
    private int pendingBytes;
    private int pendingPackets;
    private boolean inChannelReadComplete;

    protected final QuicheConfig config;
    protected final int localConnIdLength;
    // This buffer is used to copy InetSocketAddress to sockaddr_storage and so pass it down the JNI layer.
    protected ByteBuf senderSockaddrMemory;
    protected ByteBuf recipientSockaddrMemory;

    QuicheQuicCodec(QuicheConfig config, int localConnIdLength, int maxTokenLength, FlushStrategy flushStrategy) {
        this.config = config;
        this.localConnIdLength = localConnIdLength;
        this.maxTokenLength = maxTokenLength;
        this.flushStrategy = flushStrategy;
    }

    protected final QuicheQuicChannel getChannel(ByteBuffer key) {
        return connectionIdToChannel.get(key);
    }

    protected final void addMapping(ByteBuffer key, QuicheQuicChannel channel) {
        connectionIdToChannel.put(key, channel);
    }

    protected final void removeMapping(ByteBuffer key) {
        connectionIdToChannel.remove(key);
    }

    protected final void removeChannel(QuicheQuicChannel channel) {
        boolean removed = channels.remove(channel);
        assert removed;
        for (ByteBuffer id : channel.sourceConnectionIds()) {
            connectionIdToChannel.remove(id);
        }
    }

    protected final void addChannel(QuicheQuicChannel channel) {
        boolean added = channels.add(channel);
        assert added;
        for (ByteBuffer id : channel.sourceConnectionIds()) {
            connectionIdToChannel.put(id, channel);
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        senderSockaddrMemory = allocateNativeOrder(Quiche.SIZEOF_SOCKADDR_STORAGE);
        recipientSockaddrMemory = allocateNativeOrder(Quiche.SIZEOF_SOCKADDR_STORAGE);
        headerParser = new QuicHeaderParser(maxTokenLength, localConnIdLength);
        parserCallback = new QuicCodecHeaderProcessor(ctx);
        estimatorHandle = ctx.channel().config().getMessageSizeEstimator().newHandle();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        try {
            // Use a copy of the array as closing the channel may cause an unwritable event that could also
            // remove channels.
            for (QuicheQuicChannel ch : channels.toArray(new QuicheQuicChannel[0])) {
                ch.forceClose();
            }
            channels.clear();
            connectionIdToChannel.clear();

            needsFireChannelReadComplete.clear();
            if (pendingPackets > 0) {
                flushNow(ctx);
            }
        } finally {
            config.free();
            if (senderSockaddrMemory != null) {
                senderSockaddrMemory.release();
            }
            if (recipientSockaddrMemory != null) {
                recipientSockaddrMemory.release();
            }
            if (headerParser != null) {
                headerParser.close();
                headerParser = null;
            }
        }
    }

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        DatagramPacket packet = (DatagramPacket) msg;
        try {
            ByteBuf buffer = ((DatagramPacket) msg).content();
            if (!buffer.isDirect()) {
                // We need a direct buffer as otherwise we can not access the memoryAddress.
                // Let's do a copy to direct memory.
                ByteBuf direct = ctx.alloc().directBuffer(buffer.readableBytes());
                try {
                    direct.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
                    handleQuicPacket(packet.sender(), packet.recipient(), direct);
                } finally {
                    direct.release();
                }
            } else {
                handleQuicPacket(packet.sender(), packet.recipient(), buffer);
            }
        } finally {
            packet.release();
        }
    }

    private void handleQuicPacket(InetSocketAddress sender, InetSocketAddress recipient, ByteBuf buffer) {
        try {
            headerParser.parse(sender, recipient, buffer, parserCallback);
        } catch (Exception e) {
            LOGGER.debug("Error while processing QUIC packet", e);
        }
    }

    /**
     * Handle a QUIC packet and return {@code true} if we need to call {@link ChannelHandlerContext#flush()}.
     *
     * @param ctx the {@link ChannelHandlerContext}.
     * @param sender the {@link InetSocketAddress} of the sender of the QUIC packet
     * @param recipient the {@link InetSocketAddress} of the recipient of the QUIC packet
     * @param type the type of the packet.
     * @param version the QUIC version
     * @param scid the source connection id.
     * @param dcid the destination connection id
     * @param token the token
     * @return {@code true} if we need to call {@link ChannelHandlerContext#flush()} before there is no new events
     *                      for this handler in the current eventloop run.
     * @throws Exception  thrown if there is an error during processing.
     */
    protected abstract QuicheQuicChannel quicPacketRead(ChannelHandlerContext ctx, InetSocketAddress sender,
                                                        InetSocketAddress recipient, QuicPacketType type, int version,
                                                        ByteBuf scid, ByteBuf dcid, ByteBuf token) throws Exception;

    @Override
    public final void channelReadComplete(ChannelHandlerContext ctx) {
        inChannelReadComplete = true;
        try {
            for (;;) {
                QuicheQuicChannel channel = needsFireChannelReadComplete.poll();
                if (channel == null) {
                    break;
                }
                channel.recvComplete();
                if (channel.freeIfClosed()) {
                    removeChannel(channel);
                }
            }
        } finally {
            inChannelReadComplete = false;
            if (pendingPackets > 0) {
                flushNow(ctx);
            }
        }
    }

    @Override
    public final void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) {
            List<QuicheQuicChannel> closed = null;
            for (QuicheQuicChannel channel : channels) {
                // TODO: Be a bit smarter about this.
                channel.writable();
                if (channel.freeIfClosed()) {
                    if (closed == null) {
                        closed = new ArrayList<>();
                    }
                    closed.add(channel);
                }
            }
            if (closed != null) {
                for (QuicheQuicChannel ch: closed) {
                    removeChannel(ch);
                }
            }
        } else {
            // As we batch flushes we need to ensure we at least try to flush a batch once the channel becomes
            // unwritable. Otherwise we may end up with buffering too much writes and so waste memory.
            ctx.flush();
        }

        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public final void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)  {
        pendingPackets ++;
        int size = estimatorHandle.size(msg);
        if (size > 0) {
            pendingBytes += size;
        }
        try {
            ctx.write(msg, promise);
        } finally {
            flushIfNeeded(ctx);
        }
    }

    @Override
    public final void flush(ChannelHandlerContext ctx) {
        // If we are in the channelReadComplete(...) method we might be able to delay the flush(...) until we finish
        // processing all channels.
        if (inChannelReadComplete) {
            flushIfNeeded(ctx);
        } else if (pendingPackets > 0) {
            flushNow(ctx);
        }
    }

    private void flushIfNeeded(ChannelHandlerContext ctx) {
        // Check if we should force a flush() and so ensure the packets are delivered in a timely
        // manner and also make room in the outboundbuffer again that belongs to the underlying channel.
        if (flushStrategy.shouldFlushNow(pendingPackets, pendingBytes)) {
            flushNow(ctx);
        }
    }

    private void flushNow(ChannelHandlerContext ctx) {
        pendingBytes = 0;
        pendingPackets = 0;
        ctx.flush();
    }

    private final class QuicCodecHeaderProcessor implements QuicHeaderParser.QuicHeaderProcessor {

        private final ChannelHandlerContext ctx;

        QuicCodecHeaderProcessor(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void process(InetSocketAddress sender, InetSocketAddress recipient, ByteBuf buffer, QuicPacketType type,
                            int version, ByteBuf scid, ByteBuf dcid, ByteBuf token) throws Exception {
            QuicheQuicChannel channel = quicPacketRead(ctx, sender, recipient,
                    type, version, scid,
                    dcid, token);
            if (channel != null) {
                // Add to queue first, we might be able to safe some flushes and consolidate them
                // in channelReadComplete(...) this way.
                if (channel.markInFireChannelReadCompleteQueue()) {
                    needsFireChannelReadComplete.add(channel);
                }
                channel.recv(sender, recipient, buffer);
                for (ByteBuffer retiredSourceConnectionId : channel.retiredSourceConnectionId()) {
                    removeMapping(retiredSourceConnectionId);
                }
                for (ByteBuffer newSourceConnectionId :
                        channel.newSourceConnectionIds()) {
                    addMapping(newSourceConnectionId, channel);
                }
            }
        }
    }

}
