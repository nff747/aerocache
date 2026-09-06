package io.aerocache.protocol;

import io.aerocache.memory.UnsafeAccess;

import java.nio.ByteBuffer;

/**
 * Ultra-fast, zero-allocation streaming parser for Redis RESP protocol and inline commands.
 * Directly indexes raw native memory pointers inside DirectByteBuffers.
 */
public final class ZeroAllocRespParser {

    private ZeroAllocRespParser() {}

    /**
     * Attempts to parse a complete Redis command from the direct buffer.
     * If successful, advances buffer.position() to the end of the command and returns true.
     * If incomplete (need more socket data), leaves buffer.position() unchanged and returns false.
     */
    public static boolean parse(ByteBuffer buffer, long baseAddress, ParsedCommand out) {
        int initialPos = buffer.position();
        int limit = buffer.limit();
        if (initialPos >= limit) {
            return false;
        }

        byte firstByte = buffer.get(initialPos);

        if (firstByte == '*') {
            return parseRespArray(buffer, baseAddress, initialPos, limit, out);
        } else {
            return parseInlineCommand(buffer, baseAddress, initialPos, limit, out);
        }
    }

    private static boolean parseRespArray(ByteBuffer buffer, long baseAddress, int initialPos, int limit, ParsedCommand out) {
        int pos = initialPos + 1;

        // Parse array count
        int lineEnd = findCrLf(buffer, pos, limit);
        if (lineEnd == -1) {
            return false;
        }

        int argCount = parsePositiveInt(buffer, pos, lineEnd);
        pos = lineEnd + 2;

        out.reset();

        for (int i = 0; i < argCount; i++) {
            if (pos >= limit || buffer.get(pos) != '$') {
                return false;
            }
            pos++;

            lineEnd = findCrLf(buffer, pos, limit);
            if (lineEnd == -1) {
                return false;
            }

            int bulkLen = parsePositiveInt(buffer, pos, lineEnd);
            pos = lineEnd + 2;

            if (pos + bulkLen + 2 > limit) {
                // Incomplete bulk string data
                return false;
            }

            long argAddr = baseAddress + pos;
            out.addArg(argAddr, bulkLen);

            pos += bulkLen + 2; // skip bulk payload and trailing \r\n
        }

        // Successfully parsed full RESP array; advance buffer
        buffer.position(pos);
        identifyCommand(out);
        return true;
    }

    private static boolean parseInlineCommand(ByteBuffer buffer, long baseAddress, int initialPos, int limit, ParsedCommand out) {
        int lineEnd = findCrLf(buffer, initialPos, limit);
        if (lineEnd == -1) {
            return false;
        }

        out.reset();
        int tokenStart = -1;

        for (int i = initialPos; i < lineEnd; i++) {
            byte b = buffer.get(i);
            if (b == ' ' || b == '\t') {
                if (tokenStart != -1) {
                    out.addArg(baseAddress + tokenStart, i - tokenStart);
                    tokenStart = -1;
                }
            } else {
                if (tokenStart == -1) {
                    tokenStart = i;
                }
            }
        }

        if (tokenStart != -1) {
            out.addArg(baseAddress + tokenStart, lineEnd - tokenStart);
        }

        buffer.position(lineEnd + 2);
        identifyCommand(out);
        return true;
    }

    private static void identifyCommand(ParsedCommand out) {
        if (out.argCount == 0) {
            out.command = RespCommand.UNKNOWN;
            return;
        }

        long cmdAddr = out.argAddresses[0];
        int cmdLen = out.argLengths[0];

        if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "PING")) {
            out.command = RespCommand.PING;
        } else if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "SET")) {
            out.command = RespCommand.SET;
            parseSetTtl(out);
        } else if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "GET")) {
            out.command = RespCommand.GET;
        } else if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "DEL")) {
            out.command = RespCommand.DEL;
        } else if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "EXISTS")) {
            out.command = RespCommand.EXISTS;
        } else if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "DBSIZE")) {
            out.command = RespCommand.DBSIZE;
        } else if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "FLUSHDB")) {
            out.command = RespCommand.FLUSHDB;
        } else if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "INFO")) {
            out.command = RespCommand.INFO;
        } else if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "COMMAND")) {
            out.command = RespCommand.COMMAND;
        } else if (equalsAsciiIgnoreCase(cmdAddr, cmdLen, "QUIT")) {
            out.command = RespCommand.QUIT;
        } else {
            out.command = RespCommand.UNKNOWN;
        }
    }

    private static void parseSetTtl(ParsedCommand out) {
        if (out.argCount >= 5) {
            long flagAddr = out.argAddresses[3];
            int flagLen = out.argLengths[3];
            long valAddr = out.argAddresses[4];
            int valLen = out.argLengths[4];

            if (equalsAsciiIgnoreCase(flagAddr, flagLen, "EX")) {
                long seconds = parseLongFromNative(valAddr, valLen);
                out.ttlMillis = seconds * 1000L;
            } else if (equalsAsciiIgnoreCase(flagAddr, flagLen, "PX")) {
                out.ttlMillis = parseLongFromNative(valAddr, valLen);
            }
        }
    }

    private static int findCrLf(ByteBuffer buffer, int start, int limit) {
        for (int i = start; i < limit - 1; i++) {
            if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int parsePositiveInt(ByteBuffer buffer, int start, int end) {
        int result = 0;
        for (int i = start; i < end; i++) {
            byte b = buffer.get(i);
            if (b >= '0' && b <= '9') {
                result = result * 10 + (b - '0');
            }
        }
        return result;
    }

    private static long parseLongFromNative(long address, int length) {
        long result = 0;
        for (int i = 0; i < length; i++) {
            byte b = UnsafeAccess.getByte(address + i);
            if (b >= '0' && b <= '9') {
                result = result * 10L + (b - '0');
            }
        }
        return result;
    }

    public static boolean equalsAsciiIgnoreCase(long address, int length, String expected) {
        if (length != expected.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            byte b = UnsafeAccess.getByte(address + i);
            char c = expected.charAt(i);
            if (b != c) {
                int upperB = (b >= 'a' && b <= 'z') ? (b - 32) : b;
                int upperC = (c >= 'a' && c <= 'z') ? (c - 32) : c;
                if (upperB != upperC) {
                    return false;
                }
            }
        }
        return true;
    }
}
