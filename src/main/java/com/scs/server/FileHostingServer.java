package com.scs.server;

import com.scs.core.Config;
import com.scs.core.SCS;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages the file hosting server for ExampleMod.
 */
public class FileHostingServer {

    private static HttpServer httpServer;
    private static ExecutorService executor;
    private static volatile int currentPort = -1;
    public static final Path FILE_DIRECTORY = Path.of("SCS/shared-files");
    private static final String ZIP_CONTENT_TYPE = "application/zip";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * Starts the file hosting server on a separate thread.
     */
    public static void start() throws IOException {
        if (httpServer != null) {
            return;
        }

        Config.reload();
        int port = Config.fileServerPort;
        // Create the shared-files directory if it does not exist
        if (!Files.exists(FILE_DIRECTORY)) {
            Files.createDirectories(FILE_DIRECTORY);
        }

        // Create and configure the HTTP server
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        currentPort = port;

        httpServer.createContext("/", exchange -> {
            try {
                String requestPath = exchange.getRequestURI().getPath();
                SCS.LOGGER.info("Received request: " + requestPath);

                Path filePath = FILE_DIRECTORY.resolve(requestPath.substring(1)).normalize();

                if (!filePath.startsWith(FILE_DIRECTORY)) {
                    SCS.LOGGER.warn("Unauthorized access attempt: " + filePath);
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }

                if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                    SCS.LOGGER.warn("File not found: " + filePath);
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }

                String contentType = requestPath.endsWith(".zip") ? ZIP_CONTENT_TYPE : DEFAULT_CONTENT_TYPE;
                exchange.getResponseHeaders().add("Content-Type", contentType);

                long fileSize = Files.size(filePath);
                exchange.sendResponseHeaders(200, fileSize);

                try (var os = exchange.getResponseBody();
                     var is = Files.newInputStream(filePath)) {
                    is.transferTo(os);
                }

                SCS.LOGGER.info("Successfully served file: " + filePath);

            } catch (Exception e) {
                SCS.LOGGER.error("Error processing request", e);
                try {
                    exchange.sendResponseHeaders(500, -1); // Internal Server Error
                } catch (IOException ioException) {
                    SCS.LOGGER.error("Failed to send error response", ioException);
                }
            } finally {
                exchange.close();
            }
        });

        executor = Executors.newCachedThreadPool(); // Enable concurrent downloads
        httpServer.setExecutor(executor);
        // Start the server on a separate thread
        new Thread(() -> {
            httpServer.start();
            SCS.LOGGER.info("File hosting server started on port " + currentPort);
        }).start();
    }

    /**
     * Stops the file hosting server.
     */
    public static void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            SCS.LOGGER.info("File hosting server stopped.");
            httpServer = null;
            currentPort = -1;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    public static synchronized void restartIfPortChanged() throws IOException {
        int desiredPort = Config.fileServerPort;
        if (httpServer == null) {
            start();
            return;
        }

        if (desiredPort != currentPort) {
            SCS.LOGGER.info("File server port changed ({} -> {}). Restarting.", currentPort, desiredPort);
            stop();
            start();
        }
    }
}


