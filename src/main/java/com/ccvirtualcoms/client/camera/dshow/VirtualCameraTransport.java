package com.ccvirtualcoms.client.camera.dshow;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.camera.api.CameraFrameSource;
import com.ccvirtualcoms.camera.api.CameraStreamRegistry;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

/**
 * Loopback TCP frame server feeding the native DirectShow filter.
 *
 * <p>The native filter cannot read Java memory, and the game process must never be blocked by an
 * external application, so frames are pushed over a tiny binary protocol on {@code 127.0.0.1:25587}
 * (override with {@code -Dcc_virtual_coms.camera.dataPort}):
 *
 * <pre>
 * filter -&gt; server  (hello, 16 bytes, little endian)
 *   magic "SCMH" | version u16 | slot u16 | width u16 | height u16 | fps u16 | format u16 (0=RGB24)
 *
 * server -&gt; filter  (frame, 30 byte header + payload, little endian)
 *   magic "SCAM" | version u16 | slot u16 | sequence u32 | timestampUs u64
 *                | width u16 | height u16 | format u16 | length u32
 *   payload: width * height * 3 bytes RGB24, top row first
 * </pre>
 *
 * <p>The filter tells the server which media type an external application negotiated, so the server
 * scales the RGBA camera frame to exactly that size. When the slot has no camera (or no frame yet)
 * the server keeps sending a locally rendered "no signal" RGB24 frame, so external programs never
 * stall waiting for data.
 *
 * <p>This class only ever runs on the client and only binds the loopback interface.
 */
public final class VirtualCameraTransport {

    /** Default loopback TCP port; keep in sync with kDefaultDataPort in synaxis_cam_filter.cpp. */
    public static final int DEFAULT_PORT = 25587;

    /**
     * The native filter reads its port from the device registration ("DataPort") and falls back to
     * 25587, so this side is deliberately fixed to the same value for the MVP. Changing it means
     * rebuilding/re-registering the DLL with the new value (see native/virtual_camera/README.md).
     */
    private static final int FIXED_DATA_PORT = DEFAULT_PORT;

    /** uint16 device slot supported by the native pool. */
    public static final int MAX_DEVICE_SLOTS = DeviceRegistration.DEVICE_COUNT;

    private static final String LOOPBACK = "127.0.0.1";
    private static final int HELLO_SIZE = 16;
    private static final int FRAME_HEADER_SIZE = 30;
    private static final int MAX_CONNECTIONS = 16;
    private static final long START_RETRY_NANOS = 5_000_000_000L;
    private static final long SLOT_SCAN_INTERVAL_NANOS = 250_000_000L;
    private static final int OFFLINE_FPS = 5;

    private static final byte[] HELLO_MAGIC = "SCMH".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] FRAME_MAGIC = "SCAM".getBytes(StandardCharsets.ISO_8859_1);

    private static final VirtualCameraTransport INSTANCE = new VirtualCameraTransport();

    private final Map<String, byte[]> offlineFrames = new ConcurrentHashMap<>();
    private final AtomicLong sentFrames = new AtomicLong();
    private final AtomicLong connectedClients = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;
    private volatile String lastError = "";
    private long nextStartAttemptNanos;
    private ThreadPoolExecutor connectionPool;

    private VirtualCameraTransport() {
    }

    public static VirtualCameraTransport get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called from the client tick; starts lazily and retries a failed bind. */
    public void tick() {
        if (this.running) {
            return;
        }
        if (System.nanoTime() < this.nextStartAttemptNanos) {
            return;
        }
        start();
    }

    /** Starts listening on 127.0.0.1:{@link #configuredPort()}. */
    public synchronized void start() {
        if (this.running) {
            return;
        }
        int port = configuredPort();
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 8);
            this.serverSocket = socket;
            this.connectionPool = new ThreadPoolExecutor(0, MAX_CONNECTIONS, 30L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), runnable -> {
                        Thread thread = new Thread(runnable, "ccvc-camera-frame-client");
                        thread.setDaemon(true);
                        return thread;
                    });
            this.running = true;
            this.lastError = "";
            Thread acceptor = new Thread(this::acceptLoop, "ccvc-camera-frame-accept");
            acceptor.setDaemon(true);
            this.acceptThread = acceptor;
            acceptor.start();
            CCVirtualComs.LOGGER.info("[camera] native virtual camera frame bridge listening on {}:{}", LOOPBACK, port);
        } catch (IOException failure) {
            this.lastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            this.running = false;
            this.nextStartAttemptNanos = System.nanoTime() + START_RETRY_NANOS;
            CCVirtualComs.LOGGER.warn("[camera] native frame bridge could not bind {}:{} - {} (retrying in 5s)",
                    LOOPBACK, port, this.lastError);
        }
    }

    /** Stops the server and closes every connection. */
    public synchronized void stop() {
        this.running = false;
        ServerSocket socket = this.serverSocket;
        this.serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
        if (this.connectionPool != null) {
            this.connectionPool.shutdownNow();
            this.connectionPool = null;
        }
        this.offlineFrames.clear();
    }

    public boolean isRunning() {
        return this.running;
    }

    public int port() {
        return configuredPort();
    }

    public long clientCount() {
        return this.connectedClients.get();
    }

    /** One line for {@code /ccvc_cameras status}. */
    public String describe() {
        if (!this.running) {
            return "native-frame-bridge=stopped" + (this.lastError.isBlank() ? "" : " (" + this.lastError + ")");
        }
        return "native-frame-bridge=tcp://" + LOOPBACK + ":" + configuredPort()
                + " clients=" + this.connectedClients.get()
                + " frames=" + this.sentFrames.get();
    }

    private static int configuredPort() {
        return FIXED_DATA_PORT;
    }

    // ------------------------------------------------------------------ accept / per connection

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
                        } catch (IOException failure) {
                            CCVirtualComs.LOGGER.debug("[camera] native frame client disconnected: {}",
                                    failure.toString());
                        } catch (RuntimeException failure) {
                            CCVirtualComs.LOGGER.warn("[camera] native frame client failed: {}", failure.toString());
                        } finally {
                            this.connectedClients.decrementAndGet();
                            try {
                                accepted.close();
                            } catch (IOException ignored) {
                                // Best effort.
                            }
                        }
                    });
                } catch (RejectedExecutionException overloaded) {
                    accepted.close();
                }
            } catch (IOException failure) {
                if (!this.running) {
                    return;
                }
                CCVirtualComs.LOGGER.debug("[camera] native frame accept failed: {}", failure.toString());
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        byte[] hello = new byte[HELLO_SIZE];
        input.readFully(hello);
        if (!hasMagic(hello, HELLO_MAGIC)) {
            return;
        }
        ByteBuffer helloBuffer = ByteBuffer.wrap(hello).order(ByteOrder.LITTLE_ENDIAN);
        int version = helloBuffer.getShort(4) & 0xFFFF;
        int slot = helloBuffer.getShort(6) & 0xFFFF;
        int width = sanitizeDimension(helloBuffer.getShort(8) & 0xFFFF, 640);
        int height = sanitizeDimension(helloBuffer.getShort(10) & 0xFFFF, 480);
        int fps = clampFps(helloBuffer.getShort(12) & 0xFFFF);
        int format = helloBuffer.getShort(14) & 0xFFFF;
        if (version != 1 || slot >= MAX_DEVICE_SLOTS || format != 0) {
            CCVirtualComs.LOGGER.warn("[camera] native frame client rejected: version={} slot={} format={}",
                    version, slot, format);
            return;
        }
        CCVirtualComs.LOGGER.info("[camera] native camera {} connected ({}x{} @ {}fps)",
                slot + 1, width, height, fps);

        OutputStream output = new BufferedOutputStream(socket.getOutputStream(), 256 * 1024);
        byte[] header = new byte[FRAME_HEADER_SIZE];
        long frameInterval = Math.max(1L, 1_000_000_000L / Math.max(1, fps));
        long nextSend = System.nanoTime();
        long lastScan = 0L;
        long entryKey = Long.MIN_VALUE;
        int sequence = 0;
        byte[] cachedRgb = null;
        int cachedFrameSequence = Integer.MIN_VALUE;
        int cachedWidth = -1;
        int cachedHeight = -1;
        long startedAt = System.nanoTime();

        while (this.running && !socket.isClosed()) {
            long now = System.nanoTime();
            if (now < nextSend) {
                long waitNanos = Math.min(nextSend - now, 2_000_000L);
                if (waitNanos > 0) {
                    try {
                        Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }

            if (now - lastScan >= SLOT_SCAN_INTERVAL_NANOS) {
                lastScan = now;
                entryKey = findEntryKeyForSlot(slot);
            }

            CameraFrame frame = null;
            if (entryKey != Long.MIN_VALUE) {
                CameraStreamRegistry.Entry entry = CameraStreamRegistry.get(entryKey);
                if (entry != null && entry.source() != null) {
                    frame = entry.source().latestFrame();
                }
            }

            byte[] rgb;
            int usedFrameSequence;
            if (frame != null) {
                if (frame.sequence() != cachedFrameSequence || cachedWidth != width || cachedHeight != height) {
                    cachedRgb = scaleToRgb24(frame, width, height);
                    cachedFrameSequence = frame.sequence();
                    cachedWidth = width;
                    cachedHeight = height;
                }
                rgb = cachedRgb;
                usedFrameSequence = cachedFrameSequence;
            } else {
                rgb = offlineFrame(slot, width, height);
                usedFrameSequence = -1;
            }
            if (rgb == null) {
                rgb = offlineFrame(slot, width, height);
            }

            long timestampUs = (System.nanoTime() - startedAt) / 1000L;
            writeFrameHeader(header, slot, sequence, timestampUs, width, height, rgb.length);
            output.write(header);
            output.write(rgb);
            output.flush();
            this.sentFrames.incrementAndGet();
            sequence++;
            if (sequence % 300 == 0) {
                CCVirtualComs.LOGGER.debug("[camera] camera {} heartbeat (frame {}, source seq {})",
                        slot + 1, sequence, usedFrameSequence);
            }

            nextSend += frameInterval;
            if (nextSend < System.nanoTime() - frameInterval * 4L) {
                nextSend = System.nanoTime() + frameInterval;
            }
        }
    }

    // ------------------------------------------------------------------ slot / frame helpers

    private static long findEntryKeyForSlot(int slot) {
        for (CameraStreamRegistry.Entry entry : CameraStreamRegistry.all()) {
            int entrySlot = slotOf(entry);
            if (entrySlot == slot) {
                return entry.key();
            }
        }
        return Long.MIN_VALUE;
    }

    private static int slotOf(CameraStreamRegistry.Entry entry) {
        if (entry.device() != null && entry.device().slot() >= 0) {
            return entry.device().slot();
        }
        String deviceName = entry.device() == null ? "" : entry.device().deviceName();
        int index = deviceName.lastIndexOf(' ');
        if (index > 0 && index + 1 < deviceName.length()) {
            try {
                return Math.max(0, Integer.parseInt(deviceName.substring(index + 1).trim()) - 1);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static byte[] scaleToRgb24(CameraFrame frame, int targetWidth, int targetHeight) {
        byte[] source = frame.rgba();
        int sourceWidth = frame.width();
        int sourceHeight = frame.height();
        byte[] target = new byte[targetWidth * targetHeight * 3];
        boolean direct = sourceWidth == targetWidth && sourceHeight == targetHeight;
        int destination = 0;
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = direct ? y : Math.min(sourceHeight - 1, (int) ((long) y * sourceHeight / targetHeight));
            int sourceRow = sourceY * sourceWidth;
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = direct ? x : Math.min(sourceWidth - 1, (int) ((long) x * sourceWidth / targetWidth));
                int sourceIndex = (sourceRow + sourceX) * 4;
                target[destination++] = source[sourceIndex];
                target[destination++] = source[sourceIndex + 1];
                target[destination++] = source[sourceIndex + 2];
            }
        }
        return target;
    }

    private byte[] offlineFrame(int slot, int width, int height) {
        String cacheKey = slot + ":" + width + "x" + height;
        return this.offlineFrames.computeIfAbsent(cacheKey, ignored -> renderOfflineFrame(slot, width, height));
    }

    private static byte[] renderOfflineFrame(int slot, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0x0C0C10));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(0x202028));
            int grid = Math.max(16, height / 24);
            for (int x = 0; x < width; x += grid) {
                graphics.drawLine(x, 0, x, height);
            }
            for (int y = 0; y < height; y += grid) {
                graphics.drawLine(0, y, width, y);
            }
            int titleSize = Math.max(14, Math.min(height / 7, width / 16));
            graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, titleSize));
            graphics.setColor(new Color(0xEBEBEB));
            FontMetrics metrics = graphics.getFontMetrics();
            String title = "Synaxis Camera " + (slot + 1);
            graphics.drawString(title, Math.max(2, (width - metrics.stringWidth(title)) / 2),
                    height / 2 - metrics.getDescent());
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, titleSize / 2)));
            graphics.setColor(new Color(0xA0A0AA));
            String subtitle = "no signal - start the game and place a camera";
            FontMetrics subtitleMetrics = graphics.getFontMetrics();
            graphics.drawString(subtitle, Math.max(2, (width - subtitleMetrics.stringWidth(subtitle)) / 2),
                    height / 2 + subtitleMetrics.getAscent() + 4);
        } finally {
            graphics.dispose();
        }
        byte[] bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        byte[] rgb = new byte[width * height * 3];
        for (int index = 0; index < rgb.length; index += 3) {
            rgb[index] = bgr[index + 2];
            rgb[index + 1] = bgr[index + 1];
            rgb[index + 2] = bgr[index];
        }
        return rgb;
    }

    private static void writeFrameHeader(byte[] header, int slot, int sequence, long timestampUs,
                                         int width, int height, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(FRAME_MAGIC);
        buffer.putShort((short) 1);
        buffer.putShort((short) slot);
        buffer.putInt(sequence);
        buffer.putLong(timestampUs);
        buffer.putShort((short) width);
        buffer.putShort((short) height);
        buffer.putShort((short) 0);
        buffer.putInt(length);
    }

    private static boolean hasMagic(byte[] buffer, byte[] magic) {
        if (buffer.length < magic.length) {
            return false;
        }
        for (int index = 0; index < magic.length; index++) {
            if (buffer[index] != magic[index]) {
                return false;
            }
        }
        return true;
    }

    private static int sanitizeDimension(int value, int fallback) {
        int even = value & ~1;
        if (even < 64 || even > 1920) {
            return fallback;
        }
        return even;
    }

    private static int clampFps(int value) {
        if (value < 1) {
            return 30;
        }
        return Math.min(60, value);
    }

    /** Human readable summary used in the status command. */
    public String summary() {
        return String.format(Locale.ROOT, "%s (slot pool: %d)", describe(), MAX_DEVICE_SLOTS);
    }

    /** Null-safe helper for {@code /ccvc_cameras status} to show the camera feeding a slot. */
    public static @Nullable CameraFrameSource sourceForSlot(int slot) {
        for (CameraStreamRegistry.Entry entry : CameraStreamRegistry.all()) {
            if (slotOf(entry) == slot) {
                return entry.source();
            }
        }
        return null;
    }

    /** Configuration of the camera feeding a slot, if any. */
    public static @Nullable CameraConfig configForSlot(int slot) {
        for (CameraStreamRegistry.Entry entry : CameraStreamRegistry.all()) {
            if (slotOf(entry) == slot) {
                return entry.config();
            }
        }
        return null;
    }
}
