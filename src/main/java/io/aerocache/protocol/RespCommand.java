package io.aerocache.protocol;

/**
 * Standard Redis RESP protocol commands supported by AeroCache.
 */
public enum RespCommand {
    PING,
    SET,
    GET,
    DEL,
    EXISTS,
    DBSIZE,
    FLUSHDB,
    INFO,
    COMMAND,
    QUIT,
    UNKNOWN
}
