package com.ccvirtualcoms.client.camera.stream;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraStreamUrls;
import com.ccvirtualcoms.camera.api.CameraStreamRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Loopback-only MJPEG fallback HTTP server.
 *
 * <p>It is the "driver unavailable" escape hatch: any local program that can read an HTTP stream can
 * watch a camera even when the native DirectShow device is not installed. The implementation is a
 * deliberately small HTTP/1.1 server written on plain {@link ServerSocket} (no
 * {@code com.sun.net.httpserver}, which is not guaranteed to be resolved by the NeoForge module
 * graph, and no third-party HTTP library).
 *
 * <h2>Endpoints</h2>
 * <pre>
 * GET /                              human readable index of the running streams
 * GET /healthz                       liveness probe ("ok ...")
 * GET /api/cameras                   JSON: driver state + one object per camera
 * GET /camera/&lt;hex&gt;/stream.mjpeg     multipart/x-mixed-replace MJPEG stream
 * GET /camera/&lt;hex&gt;/snapshot.jpg      newest JPEG frame, or the offline placeholder
 * </pre>
 *
 * <p>&lt;hex&gt; is {@link BlockPos#asLong()} rendered as unsigned hexadecimal
 * ({@code Long.toHexString}), exactly like {@link CameraStreamUrls#urlFor(long)}.
 *
 * <h2>Streaming semantics</h2>
 *
 * <ul>
 *   <li>every client owns a small bounded queue (latest-wins / drop-oldest), so a slow client can
 *       never back-pressure the encoder or the render thread;</li>
 *   <li>when a camera has no fresh frame, the connection is kept open and an "offline" placeholder
 *       JPEG is re-sent twice per second - streams never hang silently and never disconnect just
 *       because the game is paused;</li>
 *   <li>the server binds {@code 127.0.0.1} only: it is never reachable from the LAN.</li>
 * </ul>
 */
public final class MjpegHttpServer {

    /** Multipart boundary used by the MJPEG stream. */
    public static final String BOUNDARY = "SynaxisFrame";

    private static final String LOOPBACK = "127.0.0.1";

    /** Upper bound of simultaneously served connections (each MJPEG client uses one thread). */
    private static final int MAX_CONNECTIONS = 24;

    /** Per-client frame queue length; exceeding it drops the oldest frame. */
    private static final int CLIENT_QUEUE_LENGTH = 8;

    /** Re-send the offline placeholder at this interval. */
    private static final long OFFLINE_FRAME_INTERVAL_MILLIS = 500L;

    /** Maximum size of the request head (request line + headers). */
    private static final int MAX_REQUEST_HEAD = 8192;

    /** One published frame, shared by all clients of a camera. */
    private record PublishedFrame(byte[] jpeg, int width, int height, int sequence) {
    }

    /** One connected MJPEG client. */
    private static final class MjpegClient {
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(CLIENT_QUEUE_LENGTH);

        void offer(byte[] frame) {
            if (!this.queue.offer(frame)) {
                this.queue.poll();
                this.queue.offer(frame);
            }
        }
    }

    private final int port;
    private final Map<Long, PublishedFrame> latest = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<MjpegClient>> clients = new ConcurrentHashMap<>();
    private final AtomicLong servedFrames = new AtomicLong();
    private final AtomicLong connectedClients = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;
    private volatile String lastError = "";
    private volatile int boundPort;
    private volatile long startedAtMillis;
    private ThreadPoolExecutor connectionPool;

    public MjpegHttpServer(int port) {
        this.port = port;
    }

    /** Starts listening; returns {@code false} (and keeps the reason) when the port is taken. */
    public synchronized boolean start() {
        if (this.running) {
            return true;
        }
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), this.port), 8);
            this.serverSocket = socket;
            this.boundPort = socket.getLocalPort();
            this.connectionPool = new ThreadPoolExecutor(0, MAX_CONNECTIONS, 30L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), runnable -> {
                        Thread thread = new Thread(runnable, "ccvc-mjpeg-client");
                        thread.setDaemon(true);
                        return thread;
                    });
            this.running = true;
            this.startedAtMillis = System.currentTimeMillis();
            Thread acceptor = new Thread(this::acceptLoop, "ccvc-mjpeg-accept");
            acceptor.setDaemon(true);
            this.acceptThread = acceptor;
            acceptor.start();
            CCVirtualComs.LOGGER.info("[camera] MJPEG fallback listening on http://{}:{}/", LOOPBACK, this.port);
            return true;
        } catch (IOException failure) {
            this.lastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            this.running = false;
            CCVirtualComs.LOGGER.error("[camera] MJPEG fallback could not bind {}:{} - {}",
                    LOOPBACK, this.port, this.lastError);
            return false;
        }
    }

    /** Stops listening and closes every connection. */
    public synchronized void stop() {
        this.running = false;
        ServerSocket socket = this.serverSocket;
        this.serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing is best effort.
            }
        }
        if (this.connectionPool != null) {
            this.connectionPool.shutdownNow();
            this.connectionPool = null;
        }
        this.latest.clear();
        for (List<MjpegClient> list : this.clients.values()) {
            list.clear();
        }
        this.clients.clear();
    }

    /** Publishes a new frame for a camera; fans it out to every connected client. */
    public void publish(long key, byte[] jpeg, int width, int height, int sequence) {
        if (jpeg == null || jpeg.length == 0) {
            return;
        }
        this.latest.put(key, new PublishedFrame(jpeg, width, height, sequence));
        List<MjpegClient> targets = this.clients.get(key);
        if (targets != null) {
            this.servedFrames.incrementAndGet();
            for (MjpegClient client : targets) {
                client.offer(jpeg);
            }
        }
    }

    /** Drops the published frame of a camera that is no longer registered. */
    public void remove(long key) {
        this.latest.remove(key);
    }

    /** {@code true} while the server is listening. */
    public boolean isRunning() {
        return this.running;
    }

    /** Why {@link #start()} failed, or an empty string. */
    public String lastError() {
        return this.lastError;
    }

    /** The actual port this server is bound to (differs from the configured one only for port 0). */
    public int port() {
        return this.boundPort != 0 ? this.boundPort : this.port;
    }

    /** Number of MJPEG clients currently connected. */
    public long clientCount() {
        return this.connectedClients.get();
    }

    /** A short status line for logs and the client command. */
    public String describe() {
        if (!this.running) {
            return "mjpeg=stopped(" + this.lastError + ")";
        }
        return "mjpeg=http://" + LOOPBACK + ":" + this.port
                + " cameras=" + this.latest.size()
                + " clients=" + this.connectedClients.get()
                + " frames=" + this.servedFrames.get();
    }

    // ------------------------------------------------------------------ accept loop

    private void acceptLoop() {
        while (this.running) {
            ServerSocket socket = this.serverSocket;
            if (socket == null) {
                return;
            }
            try {
                Socket accepted = socket.accept();
                accepted.setTcpNoDelay(true);
                accepted.setSoTimeout(5000);
                ThreadPoolExecutor pool = this.connectionPool;
                if (pool == null) {
                    accepted.close();
                    continue;
                }
                try {
                    pool.execute(() -> {
                        this.connectedClients.incrementAndGet();
                        try {
                            handle(accepted);
                        } finally {
                            this.connectedClients.decrementAndGet();
                            closeQuietly(accepted);
                        }
                    });
                } catch (RejectedExecutionException overloaded) {
                    closeQuietly(accepted);
                }
            } catch (IOException failure) {
                if (this.running) {
                    CCVirtualComs.LOGGER.debug("[camera] MJPEG accept failed: {}", failure.toString());
                } else {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ one connection

    private void handle(Socket socket) {
        try {
            String head = readRequestHead(socket.getInputStream());
            if (head == null) {
                return;
            }
            List<String> lines = splitHead(head);
            if (lines.isEmpty()) {
                return;
            }
            String[] requestLine = lines.get(0).trim().split("\\s+");
            if (requestLine.length < 2) {
                writeText(socket, 400, "Bad Request", "text/plain; charset=utf-8", "bad request line\n");
                return;
            }
            String method = requestLine[0].toUpperCase(Locale.ROOT);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeText(socket, 405, "Method Not Allowed", "text/plain; charset=utf-8", "only GET is supported\n");
                return;
            }
            String path = requestLine[1];
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            route(socket, path);
        } catch (IOException failure) {
            // Client went away or a write failed: nothing to do, the socket is closed by the caller.
        } catch (RuntimeException failure) {
            CCVirtualComs.LOGGER.debug("[camera] MJPEG request failed: {}", failure.toString());
        }
    }

    private void route(Socket socket, String path) throws IOException {
        if ("/".equals(path) || path.isEmpty()) {
            writeText(socket, 200, "OK", "text/html; charset=utf-8", buildIndexHtml());
            return;
        }
        if ("/healthz".equals(path)) {
            writeText(socket, 200, "OK", "text/plain; charset=utf-8", buildHealth());
            return;
        }
        if ("/api/cameras".equals(path)) {
            writeText(socket, 200, "OK", "application/json; charset=utf-8", buildCamerasJson());
            return;
        }
        if (path.startsWith("/camera/")) {
            String rest = path.substring("/camera/".length());
            int slash = rest.indexOf('/');
            String hex = slash < 0 ? rest : rest.substring(0, slash);
            String tail = slash < 0 ? "" : rest.substring(slash + 1);
            Long parsedKey = parseKeyOrNull(hex);
            if (parsedKey == null) {
                writeText(socket, 400, "Bad Request", "text/plain; charset=utf-8", "invalid camera key\n");
                return;
            }
            long key = parsedKey;
            if ("stream.mjpeg".equals(tail)) {
                streamMjpeg(socket, key);
                return;
            }
            if ("snapshot.jpg".equals(tail)) {
                writeJpeg(socket, snapshotFor(key));
                return;
            }
            writeText(socket, 404, "Not Found", "text/plain; charset=utf-8", "unknown camera endpoint\n");
            return;
        }
        writeText(socket, 404, "Not Found", "text/plain; charset=utf-8", "not found\n");
    }

    // ------------------------------------------------------------------ MJPEG stream

    private void streamMjpeg(Socket socket, long key) throws IOException {
        OutputStream out = socket.getOutputStream();
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                + "Pragma: no-cache\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        CCVirtualComs.LOGGER.debug("[camera] MJPEG client connected for {}", hexKey(key));

        MjpegClient client = new MjpegClient();
        this.clients.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(client);
        try {
            while (this.running) {
                byte[] frame = client.queue.poll(OFFLINE_FRAME_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    frame = placeholderFor(key);
                }
                writeMjpegPart(out, frame);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            CopyOnWriteArrayList<MjpegClient> list = this.clients.get(key);
            if (list != null) {
                list.remove(client);
                if (list.isEmpty()) {
                    this.clients.remove(key);
                }
            }
            try {
                out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (IOException ignored) {
                // The client disconnected first.
            }
        }
    }

    private void writeMjpegPart(OutputStream out, byte[] jpeg) throws IOException {
        byte[] partHead = ("--" + BOUNDARY + "\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + jpeg.length + "\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] partTail = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int chunkLength = partHead.length + jpeg.length + partTail.length;
        out.write((Integer.toHexString(chunkLength) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(partHead);
        out.write(jpeg);
        out.write(partTail);
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private byte[] snapshotFor(long key) {
        PublishedFrame frame = this.latest.get(key);
        return frame != null ? frame.jpeg() : placeholderFor(key);
    }

    /**
     * The newest encoded JPEG for a camera, or {@code null} when nothing has been published.
     *
     * <p>Used by the development autotest to prove that colour/monochrome/night-vision settings are
     * applied at the encoding stage (the raw capture frame is always RGBA).
     */
    public @Nullable byte[] latestJpeg(long key) {
        PublishedFrame frame = this.latest.get(key);
        return frame == null ? null : frame.jpeg();
    }

    private byte[] placeholderFor(long key) {
        @Nullable CameraStreamRegistry.Entry entry = CameraStreamRegistry.get(key);
        if (entry != null && entry.config() != null) {
            CameraConfig config = entry.config();
            return MjpegEncoder.placeholder(config.resolution().width(), config.resolution().height(), "offline");
        }
        return MjpegEncoder.placeholder(640, 480, "offline");
    }

    // ------------------------------------------------------------------ status JSON / HTML

    private String buildHealth() {
        CameraDriverStatus.Snapshot driver = CameraDriverStatus.get();
        return "ok port=" + this.port
                + " driver=" + driver.state().serializedName()
                + " cameras=" + CameraStreamRegistry.size()
                + " clients=" + this.connectedClients.get()
                + " frames=" + this.servedFrames.get()
                + "\n";
    }

    private String buildCamerasJson() {
        CameraDriverStatus.Snapshot driver = CameraDriverStatus.get();
        List<CameraStreamRegistry.Entry> entries = new ArrayList<>(CameraStreamRegistry.all());
        entries.sort(Comparator.comparingLong(CameraStreamRegistry.Entry::key));

        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        json.append("\"driver\":{");
        json.append("\"state\":\"").append(driver.state().serializedName()).append("\",");
        json.append("\"available\":").append(driver.available()).append(',');
        json.append("\"detail\":").append(quote(driver.detail())).append(',');
        json.append("\"dll_path\":").append(quote(driver.dllPath())).append(',');
        json.append("\"registered_devices\":").append(driver.registeredDevices()).append(',');
        json.append("\"checked_at_ms\":").append(driver.checkedAtMillis());
        json.append('}');
        json.append(",\"server\":{");
        json.append("\"port\":").append(this.port).append(',');
        json.append("\"loopback\":true,");
        json.append("\"running\":").append(this.running).append(',');
        json.append("\"clients\":").append(this.connectedClients.get()).append(',');
        json.append("\"fallback_base\":\"http://").append(LOOPBACK).append(':').append(this.port).append("\"");
        json.append('}');
        json.append(",\"cameras\":[");
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            appendCameraJson(json, entries.get(index));
        }
        json.append("]}");
        return json.toString();
    }

    private void appendCameraJson(StringBuilder json, CameraStreamRegistry.Entry entry) {
        long key = entry.key();
        CameraConfig config = entry.config() == null ? CameraConfig.DEFAULT : entry.config();
        PublishedFrame published = this.latest.get(key);
        BlockPos position = BlockPos.of(key);
        String deviceName = entry.device() == null ? "" : entry.device().deviceName();
        String displayName = deviceName.isBlank() ? "Camera " + hexKey(key) : deviceName;
        String status = entry.status() == null ? "offline" : entry.status().serializedName();
        int slot = entry.device() == null ? -1 : entry.device().slot();
        boolean driverAvailable = CameraDriverStatus.available()
                && entry.device() != null && entry.device().driverAvailable();

        json.append('{');
        json.append("\"key\":").append(quote(hexKey(key))).append(',');
        json.append("\"name\":").append(quote(displayName)).append(',');
        json.append("\"device_name\":").append(quote(deviceName)).append(',');
        json.append("\"slot\":").append(slot).append(',');
        json.append("\"x\":").append(position.getX()).append(',');
        json.append("\"y\":").append(position.getY()).append(',');
        json.append("\"z\":").append(position.getZ()).append(',');
        json.append("\"width\":").append(config.resolution().width()).append(',');
        json.append("\"height\":").append(config.resolution().height()).append(',');
        json.append("\"fps\":").append(config.fps()).append(',');
        json.append("\"color\":").append(config.color()).append(',');
        json.append("\"night_vision\":").append(config.nightVision()).append(',');
        json.append("\"look\":\"").append(MjpegEncoder.lookDescription(config)).append("\",");
        json.append("\"status\":").append(quote(status)).append(',');
        json.append("\"driver_available\":").append(driverAvailable).append(',');
        json.append("\"stream_url\":").append(quote(CameraStreamUrls.urlFor(key))).append(',');
        json.append("\"snapshot_url\":").append(quote("http://" + LOOPBACK + ':' + this.port
                + "/camera/" + hexKey(key) + "/snapshot.jpg")).append(',');
        json.append("\"published\":").append(published != null).append(',');
        json.append("\"sequence\":").append(published == null ? -1 : published.sequence()).append(',');
        json.append("\"detail\":").append(quote(entry.detail() == null ? "" : entry.detail()));
        json.append('}');
    }

    private String buildIndexHtml() {
        List<CameraStreamRegistry.Entry> entries = new ArrayList<>(CameraStreamRegistry.all());
        entries.sort(Comparator.comparingLong(CameraStreamRegistry.Entry::key));
        CameraDriverStatus.Snapshot driver = CameraDriverStatus.get();

        StringBuilder html = new StringBuilder(1024);
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<title>CC Virtual Coms cameras</title>")
                .append("<style>body{background:#121212;color:#ddd;font-family:monospace;padding:16px}")
                .append("a{color:#6cf}img{max-width:480px;border:1px solid #333;margin:4px 0}")
                .append(".off{color:#e66}.ok{color:#6c6}</style></head><body>");
        html.append("<h1>CC Synaxis Virtual Coms - camera streams</h1>");
        html.append("<p>Driver: <span class=\"").append(driver.available() ? "ok" : "off").append("\">")
                .append(escapeHtml(driver.state().serializedName())).append("</span> - ")
                .append(escapeHtml(driver.detail())).append("</p>");
        html.append("<p>MJPEG fallback base: <code>http://").append(LOOPBACK).append(':')
                .append(this.port).append("</code></p>");
        if (entries.isEmpty()) {
            html.append("<p>No camera transmitters registered on this client yet. ")
                    .append("Place a camera transmitter block; the stream appears here.</p>");
        }
        for (CameraStreamRegistry.Entry entry : entries) {
            long key = entry.key();
            String name = entry.device() != null && !entry.device().deviceName().isBlank()
                    ? entry.device().deviceName()
                    : "Camera " + hexKey(key);
            html.append("<h2>").append(escapeHtml(name)).append("</h2>");
            html.append("<p>pos=").append(BlockPos.of(key).toShortString())
                    .append(" status=").append(escapeHtml(entry.status() == null ? "?" : entry.status().serializedName()))
                    .append("</p>");
            html.append("<p><a href=\"/camera/").append(hexKey(key)).append("/stream.mjpeg\">stream.mjpeg</a> ")
                    .append("| <a href=\"/camera/").append(hexKey(key)).append("/snapshot.jpg\">snapshot.jpg</a></p>");
            html.append("<img src=\"/camera/").append(hexKey(key)).append("/stream.mjpeg\" alt=\"")
                    .append(escapeHtml(name)).append("\">");
        }
        html.append("</body></html>");
        return html.toString();
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private static void writeText(Socket socket, int code, String reason, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        writeBytes(socket, code, reason, contentType, bytes);
    }

    private static void writeJpeg(Socket socket, byte[] jpeg) throws IOException {
        writeBytes(socket, 200, "OK", "image/jpeg", jpeg);
    }

    private static void writeBytes(Socket socket, int code, String reason, String contentType, byte[] body)
            throws IOException {
        OutputStream out = socket.getOutputStream();
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /**
     * Reads the request line and headers, stopping at the first empty line.
     *
     * <p>Line endings are normalised to {@code \n}; the returned text therefore never contains
     * {@code \r}. A very long or malformed request makes the method return {@code null} instead of
     * consuming arbitrary memory.
     */
    private static @Nullable String readRequestHead(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        byte[] line = new byte[512];
        while (true) {
            int length = 0;
            int value;
            boolean endOfStream = true;
            while ((value = input.read()) != -1) {
                endOfStream = false;
                if (value == '\n') {
                    break;
                }
                if (length < line.length) {
                    line[length++] = (byte) value;
                }
            }
            while (length > 0 && line[length - 1] == '\r') {
                length--;
            }
            if (length == 0) {
                // Blank line: end of the request head.
                return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.ISO_8859_1);
            }
            buffer.write(line, 0, length);
            buffer.write('\n');
            if (buffer.size() > MAX_REQUEST_HEAD) {
                return null;
            }
            if (endOfStream) {
                return buffer.toString(StandardCharsets.ISO_8859_1);
            }
        }
    }

    private static List<String> splitHead(String head) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < head.length(); index++) {
            if (head.charAt(index) == '\n') {
                if (index > start) {
                    lines.add(head.substring(start, index));
                }
                start = index + 1;
            }
        }
        if (start < head.length()) {
            lines.add(head.substring(start));
        }
        return lines;
    }

    private static @Nullable Long parseKeyOrNull(String hex) {
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String hexKey(long key) {
        return Long.toHexString(key);
    }

    private static String quote(@Nullable String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        escaped.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
