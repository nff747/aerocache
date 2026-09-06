package io.aerocache.server;

import io.aerocache.collection.OffHeapHashMap;
import io.aerocache.memory.OffHeapArena;
import io.aerocache.protocol.ParsedCommand;
import io.aerocache.protocol.RespCommand;
import io.aerocache.protocol.ZeroAllocRespParser;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-threaded Java NIO non-blocking Event Loop server for AeroCache.
 * Provides microsecond-latency Redis-compatible request execution with zero JVM heap allocation.
 */
public final class AeroCacheServer implements Runnable, Closeable {

    private final String host;
    private final int port;
    private final int bufferCapacity;
    private final OffHeapArena arena;
    private final OffHeapHashMap map;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    private long totalOperations = 0;
    private int activeConnections = 0;

    public AeroCacheServer(String host, int port, int initialCapacity, long slabSize, int bufferCapacity) {
        this.host = host;
        this.port = port;
        this.bufferCapacity = bufferCapacity;
        this.arena = new OffHeapArena(slabSize);
        this.map = new OffHeapHashMap(initialCapacity, arena);
    }

    public synchronized void startAsync() throws IOException {
        if (running.get()) {
            return;
        }

        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false);
        this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        this.serverChannel.bind(new InetSocketAddress(host, port), 1024);
        this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running.set(true);
        this.workerThread = new Thread(this, "aerocache-event-loop");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    public synchronized void start() throws IOException {
        startAsync();
        try {
            workerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                // Microsecond-latency polling: 1ms timeout or immediate return
                int selected = selector.select(1);
                if (selected == 0) {
                    continue;
                }

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iter = keys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (Throwable t) {
                // Prevent event loop from terminating on unexpected socket errors
                t.printStackTrace();
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel sc = ssc.accept();
        if (sc == null) {
            return;
        }

        sc.configureBlocking(false);
        sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
        sc.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        sc.setOption(StandardSocketOptions.SO_RCVBUF, bufferCapacity);
        sc.setOption(StandardSocketOptions.SO_SNDBUF, bufferCapacity);

        ConnectionContext ctx = new ConnectionContext(sc, bufferCapacity);
        SelectionKey clientKey = sc.register(selector, SelectionKey.OP_READ, ctx);
        ctx.key = clientKey;
        activeConnections++;
    }

    private void handleRead(SelectionKey key) {
        ConnectionContext ctx = (ConnectionContext) key.attachment();
        SocketChannel sc = (SocketChannel) key.channel();

        try {
            int bytesRead = sc.read(ctx.rxBuffer);
            if (bytesRead == -1) {
                closeClient(ctx);
                return;
            }
            if (bytesRead == 0) {
                return;
            }

            // Flip buffer into read mode for parser
            ctx.rxBuffer.flip();

            ParsedCommand cmd = ctx.parsedCommand;
            while (ZeroAllocRespParser.parse(ctx.rxBuffer, ctx.rxBaseAddress, cmd)) {
                dispatchCommand(ctx, cmd);
                totalOperations++;
            }

            // Compact remaining unparsed frame bytes
            ctx.rxBuffer.compact();

            // Flush response buffer directly back to client
            ctx.flushDirect();

        } catch (IOException e) {
            closeClient(ctx);
        }
    }

    private void handleWrite(SelectionKey key) {
        ConnectionContext ctx = (ConnectionContext) key.attachment();
        ctx.flushDirect();
    }

    private void dispatchCommand(ConnectionContext ctx, ParsedCommand cmd) {
        switch (cmd.command) {
            case PING:
                ctx.writePong();
                break;

            case SET:
                if (cmd.argCount < 3) {
                    ctx.writeError("wrong number of arguments for 'set' command");
                } else {
                    map.put(cmd.getKeyAddress(), cmd.getKeyLength(), cmd.getValueAddress(), cmd.getValueLength(), cmd.ttlMillis);
                    ctx.writeOk();
                }
                break;

            case GET:
                if (cmd.argCount < 2) {
                    ctx.writeError("wrong number of arguments for 'get' command");
                } else {
                    if (map.get(cmd.getKeyAddress(), cmd.getKeyLength(), ctx.entryResult)) {
                        ctx.writeBulkString(ctx.entryResult.valAddress, ctx.entryResult.valLength);
                    } else {
                        ctx.writeNullBulk();
                    }
                }
                break;

            case DEL:
                if (cmd.argCount < 2) {
                    ctx.writeError("wrong number of arguments for 'del' command");
                } else {
                    boolean deleted = map.delete(cmd.getKeyAddress(), cmd.getKeyLength());
                    ctx.writeInteger(deleted ? 1 : 0);
                }
                break;

            case EXISTS:
                if (cmd.argCount < 2) {
                    ctx.writeError("wrong number of arguments for 'exists' command");
                } else {
                    boolean exists = map.exists(cmd.getKeyAddress(), cmd.getKeyLength());
                    ctx.writeInteger(exists ? 1 : 0);
                }
                break;

            case DBSIZE:
                ctx.writeInteger(map.size());
                break;

            case FLUSHDB:
                map.clear();
                ctx.writeOk();
                break;

            case INFO:
                String info = "# Server\r\nredis_version:7.0.0-aerocache\r\n"
                        + "# Memory\r\nused_memory_offheap:" + arena.getTotalAllocatedBytes() + "\r\n"
                        + "# Stats\r\ntotal_operations:" + totalOperations + "\r\n"
                        + "active_connections:" + activeConnections + "\r\n";
                ctx.writeBulkStringAscii(info);
                break;

            case COMMAND:
                ctx.writeEmptyArray();
                break;

            case QUIT:
                ctx.writeOk();
                ctx.flushDirect();
                closeClient(ctx);
                break;

            default:
                ctx.writeError("unknown command");
                break;
        }
    }

    private void closeClient(ConnectionContext ctx) {
        ctx.close();
        activeConnections = Math.max(0, activeConnections - 1);
    }

    public OffHeapHashMap getMap() {
        return map;
    }

    public long getTotalOperations() {
        return totalOperations;
    }

    public int getActiveConnections() {
        return activeConnections;
    }

    public int getPort() {
        return (serverChannel != null && serverChannel.isOpen())
                ? ((InetSocketAddress) serverChannel.socket().getLocalSocketAddress()).getPort()
                : port;
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (selector != null) {
            selector.wakeup();
            try {
                selector.close();
            } catch (IOException ignored) {}
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {}
        }
        map.close();
    }
}
