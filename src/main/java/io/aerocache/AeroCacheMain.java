package io.aerocache;

import io.aerocache.server.AeroCacheServer;

import java.io.IOException;

/**
 * Main command-line entrypoint for AeroCache server.
 */
public final class AeroCacheMain {

    public static void main(String[] args) throws IOException {
        int port = 6379;
        int capacity = 65536;
        long slabBytes = 64L * 1024L * 1024L; // 64 MB
        int bufferBytes = 64 * 1024;           // 64 KB per connection

        for (int i = 0; i < args.length; i++) {
            if (("-p".equals(args[i]) || "--port".equals(args[i])) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (("-c".equals(args[i]) || "--capacity".equals(args[i])) && i + 1 < args.length) {
                capacity = Integer.parseInt(args[++i]);
            } else if (("-s".equals(args[i]) || "--slab".equals(args[i])) && i + 1 < args.length) {
                slabBytes = Long.parseLong(args[++i]) * 1024L * 1024L;
            }
        }

        System.out.println("=================================================================");
        System.out.println("     _                    ____           _          ");
        System.out.println("    / \\   ___ _ __ ___   / ___|__ _  ___| |__   ___ ");
        System.out.println("   / _ \\ / _ \\ '__/ _ \\ | |   / _` |/ __| '_ \\ / _ \\");
        System.out.println("  / ___ \\  __/ | | (_) || |__| (_| | (__| | | |  __/");
        System.out.println(" /_/   \\_\\___|_|  \\___/  \\____\\__,_|\\___|_| |_|\\___|");
        System.out.println("=================================================================");
        System.out.println("  AeroCache // High-Frequency Trading Off-Heap Caching Engine");
        System.out.println("  Version          : 1.0.0 (Zero-Allocation Core)");
        System.out.println("  Listening Port   : " + port + " (Redis-compatible RESP2)");
        System.out.println("  Initial Slots    : " + capacity);
        System.out.println("  Slab Size        : " + (slabBytes / (1024 * 1024)) + " MB per chunk");
        System.out.println("  Buffer Size      : " + (bufferBytes / 1024) + " KB direct");
        System.out.println("  JVM Heap Target  : 0 bytes/sec steady-state allocation");
        System.out.println("-----------------------------------------------------------------");

        AeroCacheServer server = new AeroCacheServer("0.0.0.0", port, capacity, slabBytes, bufferBytes);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[AeroCache] Shutting down off-heap memory arena and socket channels...");
            server.close();
            System.out.println("[AeroCache] Clean shutdown complete. All unmanaged off-heap memory freed.");
        }));

        System.out.println("[AeroCache] Server online and waiting for client connections.\n");
        server.start();
    }
}
