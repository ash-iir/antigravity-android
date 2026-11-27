package com.agy.gateway;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.agy.log.CrashRecorder;
import com.agy.log.LogRingBuffer;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight embedded HTTP Server listening strictly on 127.0.0.1:45160.
 * Zero external dependencies; exposes the authenticated Android System Gateway
 * to the local Linux/agent layer via a per-boot 0600 bearer token.
 */
public final class SystemGatewayServer {

    private static final String TAG = "SystemGateway";
    public static final int DEFAULT_PORT = 45160;
    private static final int MAX_HEADER_LINE_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final Context context;
    private int port;
    private final GatewayDispatcher dispatcher;
    private final LogRingBuffer logBuffer;
    private final ExecutorService executor = Executors.newFixedThreadPool(6);
    private volatile String authToken;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public SystemGatewayServer(Context context, LogRingBuffer logBuffer) {
        this(context, logBuffer, DEFAULT_PORT);
    }

    public SystemGatewayServer(Context context, LogRingBuffer logBuffer, int port) {
        this.context = context;
        this.port = port;
        this.logBuffer = logBuffer;
        this.dispatcher = new GatewayDispatcher(context, logBuffer);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        authToken = generateToken();
        writeTokenFile(authToken);

        acceptThread = new Thread(() -> {
            try {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
                } catch (IOException e) {
                    Log.w(TAG, "Port " + port + " in use, trying fallback " + (port + 2));
                    port = port + 2;
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
                }
                Log.i(TAG, "System Gateway listening on http://127.0.0.1:" + port + "/gateway");
                try {
                    File pFile = new File(context.getFilesDir(), "logs/gateway.port");
                    if (pFile.getParentFile() != null) pFile.getParentFile().mkdirs();
                    com.agy.util.AtomicFileWriter.writeText(pFile, String.valueOf(port) + "\n");
                } catch (Throwable ignored) {}

                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        client.setSoTimeout(3000);
                        executor.submit(() -> handleClient(client));
                    } catch (Exception e) {
                        if (!running) break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Gateway server bind failed on port " + port + ": " + e.getMessage());
            }
        }, "AgyGatewayServer");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        dispatcher.releaseResources();
        executor.shutdownNow();
        deleteQuietly(new File(context.getFilesDir(), "logs/gateway.token"));
        deleteQuietly(new File(context.getFilesDir(), "logs/gateway.port"));
        authToken = null;
        Log.i(TAG, "System Gateway stopped.");
    }

    private void handleClient(Socket client) {
        try (Socket sock = client;
             InputStream in = new BufferedInputStream(sock.getInputStream(), 8192);
             OutputStream out = sock.getOutputStream()) {

            String reqLine = readHeaderLine(in);
            if (reqLine == null) return;

            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];

            int contentLength = 0;
            String headerToken = null;
            String line;
            while ((line = readHeaderLine(in)) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } else if (lower.startsWith("x-agy-token:")) {
                    headerToken = line.substring(12).trim();
                } else if (lower.startsWith("authorization:") && headerToken == null) {
                    String val = line.substring(14).trim();
                    if (val.regionMatches(true, 0, "Bearer ", 0, 7)) {
                        headerToken = val.substring(7).trim();
                    }
                }
            }

            if (contentLength < 0) {
                writeError(out, 400, "Bad Request", "invalid content-length");
                return;
            }
            if (contentLength > MAX_BODY_BYTES) {
                writeError(out, 413, "Payload Too Large", "payload too large");
                return;
            }

            if (!isAuthorized(headerToken)) {
                writeError(out, 401, "Unauthorized", "unauthorized");
                return;
            }

            int statusCode = 200;
            String statusReason = "OK";
            JSONObject responseJson = new JSONObject();
            if (path.startsWith("/gateway/status")) {
                responseJson.put("ok", true);
                responseJson.put("status", "online");
                responseJson.put("port", port);
            } else if (path.startsWith("/gateway/logs")) {
                responseJson.put("ok", true);
                responseJson.put("logs", LogRingBuffer.toJsonArray(logBuffer.getRecent(50)));
            } else if (path.startsWith("/gateway/commands")) {
                responseJson.put("ok", true);
                responseJson.put("commands", com.agy.log.CommandHistory.toJsonArray(dispatcher.getCommandHistory().getRecent(20)));
            } else if (path.startsWith("/gateway/crash")) {
                JSONObject crash = CrashRecorder.getLatestCrash();
                responseJson.put("ok", true);
                responseJson.put("crash", crash != null ? crash : JSONObject.NULL);
            } else if ("POST".equals(method) && path.startsWith("/gateway")) {
                String bodyStr = readFixedBody(in, contentLength);
                JSONObject req = !bodyStr.isEmpty() ? new JSONObject(bodyStr) : new JSONObject();
                String cmd = req.optString("command", "");
                JSONObject payload = req.optJSONObject("payload");
                responseJson = dispatcher.dispatch(cmd, payload);
            } else {
                statusCode = 404;
                statusReason = "Not Found";
                responseJson.put("ok", false);
                responseJson.put("error", "not found");
            }

            writeJsonResponse(out, statusCode, statusReason, responseJson);
        } catch (Exception e) {
            Log.d(TAG, "client error: " + e.getMessage());
        }
    }

    private String readFixedBody(InputStream in, int contentLength) throws IOException {
        if (contentLength <= 0) {
            return "";
        }
        byte[] payloadBytes = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int r = in.read(payloadBytes, read, contentLength - read);
            if (r == -1) break;
            read += r;
        }
        if (read < contentLength) {
            throw new IOException("Unexpected EOF while reading body: expected " + contentLength + ", got " + read);
        }
        return new String(payloadBytes, StandardCharsets.UTF_8);
    }

    private boolean isAuthorized(String candidate) {
        String expected = this.authToken;
        if (expected == null || expected.isEmpty() || candidate == null || candidate.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private void writeTokenFile(String token) {
        File tokenFile = new File(context.getFilesDir(), "logs/gateway.token");
        File dir = tokenFile.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        File tmpFile = new File(dir, "gateway.token.tmp");
        try {
            deleteQuietly(tmpFile);
            if (!tmpFile.createNewFile()) {
                deleteQuietly(tmpFile);
            }
            enforceOwnerOnlyPermissions(tmpFile);
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write((token + "\n").getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }
            enforceOwnerOnlyPermissions(tmpFile);
            if (!tmpFile.renameTo(tokenFile)) {
                deleteQuietly(tokenFile);
                if (!tmpFile.renameTo(tokenFile)) {
                    try (FileOutputStream fos = new FileOutputStream(tokenFile)) {
                        fos.write((token + "\n").getBytes(StandardCharsets.UTF_8));
                        fos.flush();
                    }
                    deleteQuietly(tmpFile);
                }
            }
            enforceOwnerOnlyPermissions(tokenFile);
        } catch (Throwable t) {
            deleteQuietly(tmpFile);
            Log.w(TAG, "failed to write gateway token file: " + t.getMessage());
        }
    }

    private static void enforceOwnerOnlyPermissions(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Os.chmod(file.getAbsolutePath(), 0600);
        } catch (Throwable ignored) {
        }
        file.setReadable(false, false);
        file.setReadable(true, true);
        file.setWritable(false, false);
        file.setWritable(true, true);
    }

    private static String generateToken() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[random.length * 2];
        for (int i = 0; i < random.length; i++) {
            int v = random[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
    }

    private static void writeError(OutputStream out, int code, String reason, String message) throws IOException {
        JSONObject err = new JSONObject();
        try {
            err.put("ok", false);
            err.put("error", message);
        } catch (Exception ignored) {
        }
        writeJsonResponse(out, code, reason, err);
    }

    private static void writeJsonResponse(OutputStream out, int code, String reason, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            f.delete();
        }
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                continue;
            }
            if (b == '\n') {
                break;
            }
            if (baos.size() >= MAX_HEADER_LINE_BYTES) {
                throw new IOException("HTTP header line exceeded limit of " + MAX_HEADER_LINE_BYTES + " bytes");
            }
            baos.write(b);
        }
        if (b == -1 && baos.size() == 0) {
            return null;
        }
        return baos.toString("UTF-8");
    }
}
