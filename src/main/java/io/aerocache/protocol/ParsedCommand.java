package io.aerocache.protocol;

/**
 * Mutable, zero-allocation container holding parsed Redis command arguments.
 * References raw native memory addresses directly inside direct network buffers.
 */
public final class ParsedCommand {

    public static final int MAX_ARGS = 16;

    public RespCommand command = RespCommand.UNKNOWN;
    public int argCount = 0;
    public final long[] argAddresses = new long[MAX_ARGS];
    public final int[] argLengths = new int[MAX_ARGS];
    public long ttlMillis = 0L;

    public void reset() {
        this.command = RespCommand.UNKNOWN;
        this.argCount = 0;
        this.ttlMillis = 0L;
    }

    public void addArg(long address, int length) {
        if (argCount < MAX_ARGS) {
            argAddresses[argCount] = address;
            argLengths[argCount] = length;
            argCount++;
        }
    }

    public long getKeyAddress() {
        return (argCount > 1) ? argAddresses[1] : 0L;
    }

    public int getKeyLength() {
        return (argCount > 1) ? argLengths[1] : 0;
    }

    public long getValueAddress() {
        return (argCount > 2) ? argAddresses[2] : 0L;
    }

    public int getValueLength() {
        return (argCount > 2) ? argLengths[2] : 0;
    }
}
