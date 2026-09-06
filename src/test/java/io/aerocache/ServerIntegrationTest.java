package io.aerocache;

import io.aerocache.server.AeroCacheServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerIntegrationTest {

    private AeroCacheServer server;
    private int port = 16379;

    @BeforeEach
    void setUp() throws Exception {
        server = new AeroCacheServer("127.0.0.1", port, 1024, 8 * 1024 * 1024, 64 * 1024);
        server.startAsync();
        Thread.sleep(100); // Allow event loop to bind
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String sendAndReceive(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] buf = new byte[1024];
            int read = in.read(buf);
            assertTrue(read > 0, "No response received from AeroCache server");
            return new String(buf, 0, read, StandardCharsets.UTF_8);
        }
    }

    @Test
    void testPingCommand() throws Exception {
        String resp = sendAndReceive("*1\r\n$4\r\nPING\r\n");
        assertEquals("+PONG\r\n", resp);
    }

    @Test
    void testInlinePingCommand() throws Exception {
        String resp = sendAndReceive("PING\r\n");
        assertEquals("+PONG\r\n", resp);
    }

    @Test
    void testSetAndGetCommands() throws Exception {
        String setResp = sendAndReceive("*3\r\n$3\r\nSET\r\n$9\r\nuser:1001\r\n$12\r\nalice_trader\r\n");
        assertEquals("+OK\r\n", setResp);

        String getResp = sendAndReceive("*2\r\n$3\r\nGET\r\n$9\r\nuser:1001\r\n");
        assertEquals("$12\r\nalice_trader\r\n", getResp);

        String missingResp = sendAndReceive("*2\r\n$3\r\nGET\r\n$12\r\nnon_existent\r\n");
        assertEquals("$-1\r\n", missingResp);
    }

    @Test
    void testExistsAndDel() throws Exception {
        sendAndReceive("*3\r\n$3\r\nSET\r\n$5\r\norder\r\n$4\r\nBUY1\r\n");

        String existsResp = sendAndReceive("*2\r\n$6\r\nEXISTS\r\n$5\r\norder\r\n");
        assertEquals(":1\r\n", existsResp);

        String delResp = sendAndReceive("*2\r\n$3\r\nDEL\r\n$5\r\norder\r\n");
        assertEquals(":1\r\n", delResp);

        String existsAfterDel = sendAndReceive("*2\r\n$6\r\nEXISTS\r\n$5\r\norder\r\n");
        assertEquals(":0\r\n", existsAfterDel);
    }

    @Test
    void testDbSizeAndFlushDb() throws Exception {
        sendAndReceive("*3\r\n$3\r\nSET\r\n$2\r\nk1\r\n$2\r\nv1\r\n");
        sendAndReceive("*3\r\n$3\r\nSET\r\n$2\r\nk2\r\n$2\r\nv2\r\n");

        String dbsizeResp = sendAndReceive("*1\r\n$6\r\nDBSIZE\r\n");
        assertEquals(":2\r\n", dbsizeResp);

        String flushResp = sendAndReceive("*1\r\n$7\r\nFLUSHDB\r\n");
        assertEquals("+OK\r\n", flushResp);

        String dbsizeAfterFlush = sendAndReceive("*1\r\n$6\r\nDBSIZE\r\n");
        assertEquals(":0\r\n", dbsizeAfterFlush);
    }
}
