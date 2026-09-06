package io.aerocache.server;

import io.aerocache.collection.EntryResult;
import io.aerocache.memory.UnsafeAccess;
import io.aerocache.protocol.ParsedCommand;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Per-connection context holding dedicated off-heap direct buffers and state.
 * Eliminates garbage collection churn by recycling memory across request cycles.
 */
public final class ConnectionContext implements Closeable {

    private static final byte[] PONG_RESP = "+PONG\r\n".getBytes();
    private static final byte[] OK_RESP = "+OK\r\n".getBytes();
    private static final byte[] NULL_BULK_RESP = "$-1\r\n".getBytes();
    private static final byte[] EMPTY_ARRAY_RESP = "*0\r\n".getBytes();
    private static final byte[] CRLF = "\r\n".getBytes();

    public final SocketChannel channel;
    public SelectionKey key;

    public final ByteBuffer rxBuffer;
    public final long rxBaseAddress;

    public final ByteBuffer txBuffer;
    public final long txBaseAddress;

    public final ParsedCommand parsedCommand = new ParsedCommand();
    public final EntryResult entryResult = new EntryResult();

    public ConnectionContext(SocketChannel channel, int bufferCapacity) {
        this.channel = channel;
        this.rxBuffer = ByteBuffer.allocateDirect(bufferCapacity);
        this.rxBaseAddress = UnsafeAccess.getDirectBufferAddress(rxBuffer);

        this.txBuffer = ByteBuffer.allocateDirect(bufferCapacity);
        this.txBaseAddress = UnsafeAccess.getDirectBufferAddress(txBuffer);
    }

    public void writePong() {
        txBuffer.put(PONG_RESP);
    }

    public void writeOk() {
        txBuffer.put(OK_RESP);
    }

    public void writeNullBulk() {
        txBuffer.put(NULL_BULK_RESP);
    }

    public void writeEmptyArray() {
        txBuffer.put(EMPTY_ARRAY_RESP);
    }

    public void writeInteger(long value) {
        txBuffer.put((byte) ':');
        writeAsciiLong(value);
        txBuffer.put((byte) '\r');
        txBuffer.put((byte) '\n');
    }

    public void writeBulkString(long valAddress, int valLength) {
        txBuffer.put((byte) '$');
        writeAsciiLong(valLength);
        txBuffer.put((byte) '\r');
        txBuffer.put((byte) '\n');

        // Copy raw off-heap bytes directly into the direct transmission buffer
        int currentTxPos = txBuffer.position();
        if (txBuffer.remaining() >= valLength + 2) {
            long destAddr = txBaseAddress + currentTxPos;
            UnsafeAccess.copyMemory(valAddress, destAddr, valLength);
            txBuffer.position(currentTxPos + valLength);
            txBuffer.put((byte) '\r');
            txBuffer.put((byte) '\n');
        } else {
            // Buffer overflow fallback: flush first then write
            flushDirect();
            currentTxPos = txBuffer.position();
            long destAddr = txBaseAddress + currentTxPos;
            UnsafeAccess.copyMemory(valAddress, destAddr, valLength);
            txBuffer.position(currentTxPos + valLength);
            txBuffer.put((byte) '\r');
            txBuffer.put((byte) '\n');
        }
    }

    public void writeBulkStringAscii(String str) {
        txBuffer.put((byte) '$');
        writeAsciiLong(str.length());
        txBuffer.put((byte) '\r');
        txBuffer.put((byte) '\n');
        writeRawString(str);
        txBuffer.put((byte) '\r');
        txBuffer.put((byte) '\n');
    }

    public void writeError(String msg) {
        txBuffer.put((byte) '-');
        txBuffer.put((byte) 'E');
        txBuffer.put((byte) 'R');
        txBuffer.put((byte) 'R');
        txBuffer.put((byte) ' ');
        for (int i = 0; i < msg.length(); i++) {
            txBuffer.put((byte) msg.charAt(i));
        }
        txBuffer.put((byte) '\r');
        txBuffer.put((byte) '\n');
    }

    public void writeRawString(String str) {
        for (int i = 0; i < str.length(); i++) {
            txBuffer.put((byte) str.charAt(i));
        }
    }

    private void writeAsciiLong(long val) {
        if (val == 0) {
            txBuffer.put((byte) '0');
            return;
        }
        if (val < 0) {
            txBuffer.put((byte) '-');
            val = -val;
        }
        byte[] temp = new byte[20];
        int idx = 0;
        while (val > 0) {
            temp[idx++] = (byte) ('0' + (val % 10));
            val /= 10;
        }
        for (int i = idx - 1; i >= 0; i--) {
            txBuffer.put(temp[i]);
        }
    }

    public boolean flushDirect() {
        if (txBuffer.position() == 0) {
            return true;
        }

        txBuffer.flip();
        try {
            channel.write(txBuffer);
            if (txBuffer.hasRemaining()) {
                // Incomplete socket write; compact for next round and monitor OP_WRITE
                txBuffer.compact();
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
                return false;
            } else {
                txBuffer.clear();
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                }
                return true;
            }
        } catch (IOException e) {
            close();
            return false;
        }
    }

    @Override
    public void close() {
        try {
            if (key != null) {
                key.cancel();
            }
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException ignored) {}
    }
}
