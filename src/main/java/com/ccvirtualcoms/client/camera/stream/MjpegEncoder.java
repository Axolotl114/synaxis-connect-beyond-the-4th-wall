package com.ccvirtualcoms.client.camera.stream;

import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.api.CameraFrame;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Converts one {@link CameraFrame} (packed RGBA) into a JPEG suitable for the MJPEG fallback
 * stream and the virtual camera transport.
 *
 * <p>The encoder is deliberately free of third-party libraries: it only uses {@code java.desktop}
 * ({@link BufferedImage}, {@link Graphics2D}) and the JDK's built-in JPEG writer from
 * {@code javax.imageio}. One encoder instance owns one {@link ImageWriter}, so it must only be used
 * from a single encoder thread; the stream service keeps exactly one encoder per camera.
 *
 * <h2>Look modes</h2>
 *
 * <p>Which pixels are produced is decided by {@link CameraConfig#color()} and
 * {@link CameraConfig#nightVision()}:
 *
 * <ul>
 *   <li><b>colour</b> - straight RGB JPEG, no post-processing;</li>
 *   <li><b>monochrome</b> - ITU-R BT.601 luma ({@code 0.299R + 0.587G + 0.114B}) as an 8-bit grey
 *       JPEG;</li>
 *   <li><b>colour + night vision</b> - amplified and gamma-corrected colour with a mild green
 *       tint, the classic low-light-camera look;</li>
 *   <li><b>monochrome + night vision</b> - <i>thermal-like</i> false colour: luma is contrast
 *       stretched and then mapped through an iron-bow palette (black -&gt; purple/blue -&gt; red
 *       -&gt; orange -&gt; yellow -&gt; white). This is a stylistic choice, not a real thermal
 *       sensor, and is documented here so the GUI can label it accurately.</li>
 * </ul>
 *
 * <p>The output size is always {@link CameraConfig#resolution()}. Frames captured at another size
 * are resampled with nearest-neighbour sampling; that keeps the encoder allocation-free and is
 * visually fine for the low-resolution camera view, whose resolution is normally already the
 * configured one.
 *
 * <p>Encoding never throws for a malformed frame; it is simply reported to the caller. Callers on
 * the stream service thread catch the exception and keep dropping frames, so a bad frame can never
 * stall the render thread.
 */
public final class MjpegEncoder {

    /** Night vision amplification applied before gamma correction (colour mode). */
    private static final double NIGHT_GAIN = 1.9D;

    /** Gamma exponent applied after night vision amplification (colour mode). */
    private static final double NIGHT_GAMMA = 0.78D;

    /** Thermal palette contrast stretch around mid grey. */
    private static final double THERMAL_CONTRAST = 1.55D;

    /** Thermal palette gamma, applied before the contrast stretch. */
    private static final double THERMAL_GAMMA = 0.72D;

    /** Default JPEG quality; good enough for 640x480 while keeping CPU cost low. */
    private static final float JPEG_QUALITY = 0.78F;

    /** Palette used by the thermal look: iron-bow, 256 entries. */
    private static final int[] THERMAL_R = new int[256];
    private static final int[] THERMAL_G = new int[256];
    private static final int[] THERMAL_B = new int[256];

    /** Amplification/gamma lookup table for the colour night vision look. */
    private static final int[] NIGHT_LUT = new int[256];

    private static final Map<String, byte[]> PLACEHOLDERS = new ConcurrentHashMap<>();

    static {
        // --- night vision look-up table -----------------------------------------------------
        for (int value = 0; value < 256; value++) {
            double amplified = Math.min(255.0D, value * NIGHT_GAIN);
            double corrected = Math.pow(amplified / 255.0D, NIGHT_GAMMA) * 255.0D;
            NIGHT_LUT[value] = (int) Math.max(0.0D, Math.min(255.0D, Math.round(corrected)));
        }

        // --- iron-bow thermal palette -------------------------------------------------------
        for (int index = 0; index < 256; index++) {
            double t = index / 255.0D;
            final int[][] stops = {
                    {0, 0, 0},      // black
                    {24, 0, 64},    // deep purple
                    {96, 0, 128},   // violet
                    {192, 0, 96},   // red-violet
                    {255, 32, 0},   // red
                    {255, 160, 0},  // orange
                    {255, 240, 64}, // yellow
                    {255, 255, 255} // white
            };
            double scaled = t * (stops.length - 1);
            int low = (int) Math.floor(scaled);
            int high = Math.min(stops.length - 1, low + 1);
            double fraction = scaled - low;
            THERMAL_R[index] = (int) Math.round(stops[low][0] + (stops[high][0] - stops[low][0]) * fraction);
            THERMAL_G[index] = (int) Math.round(stops[low][1] + (stops[high][1] - stops[low][1]) * fraction);
            THERMAL_B[index] = (int) Math.round(stops[low][2] + (stops[high][2] - stops[low][2]) * fraction);
        }
    }

    private final ImageWriter writer;
    private final ImageWriteParam writeParam;

    public MjpegEncoder() {
        ImageWriter selected = null;
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (writers.hasNext()) {
            selected = writers.next();
        }
        if (selected == null) {
            throw new IllegalStateException("no JPEG ImageWriter available in this JVM");
        }
        this.writer = selected;
        this.writeParam = selected.getDefaultWriteParam();
        if (this.writeParam.canWriteCompressed()) {
            this.writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            this.writeParam.setCompressionQuality(JPEG_QUALITY);
        }
    }

    /** Encodes one frame with the look implied by {@code config}. */
    public byte[] encode(CameraFrame frame, CameraConfig config) throws IOException {
        BufferedImage image = toImage(frame, config);
        return writeJpeg(image);
    }

    /**
     * Renders a cached placeholder JPEG ("offline" / "no signal") at the given size.
     *
     * <p>Placeholders are cached per size and label because the MJPEG server re-sends them several
     * times per second to idle clients.
     */
    public static byte[] placeholder(int width, int height, String label) {
        int safeWidth = Math.max(16, width);
        int safeHeight = Math.max(16, height);
        String safeLabel = label == null || label.isBlank() ? "offline" : label;
        String cacheKey = safeWidth + "x" + safeHeight + ":" + safeLabel;
        return PLACEHOLDERS.computeIfAbsent(cacheKey, ignored -> {
            try {
                BufferedImage image = new BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D graphics = image.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    graphics.setColor(Color.BLACK);
                    graphics.fillRect(0, 0, safeWidth, safeHeight);
                    graphics.setColor(new Color(0x33, 0x33, 0x33));
                    int grid = Math.max(8, safeHeight / 24);
                    for (int x = 0; x < safeWidth; x += grid) {
                        graphics.drawLine(x, 0, x, safeHeight);
                    }
                    for (int y = 0; y < safeHeight; y += grid) {
                        graphics.drawLine(0, y, safeWidth, y);
                    }
                    int fontSize = Math.max(12, Math.min(safeHeight / 6, safeWidth / (safeLabel.length() / 2 + 6)));
                    graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, fontSize));
                    graphics.setColor(Color.WHITE);
                    FontMetrics metrics = graphics.getFontMetrics();
                    int textWidth = metrics.stringWidth(safeLabel);
                    int textHeight = metrics.getAscent();
                    graphics.drawString(safeLabel,
                            Math.max(2, (safeWidth - textWidth) / 2),
                            Math.max(textHeight, (safeHeight + textHeight) / 2 - 2));
                } finally {
                    graphics.dispose();
                }
                return writeJpegStatic(image);
            } catch (IOException failure) {
                return new byte[0];
            }
        });
    }

    /** Releases the native JPEG writer resources. */
    public void close() {
        try {
            this.writer.dispose();
        } catch (RuntimeException ignored) {
            // The JVM is going away; nothing useful to do.
        }
    }

    // ------------------------------------------------------------------ pixel conversion

    private static BufferedImage toImage(CameraFrame frame, CameraConfig config) {
        int targetWidth = config.resolution().width();
        int targetHeight = config.resolution().height();
        boolean colour = config.color();
        boolean nightVision = config.nightVision();
        // Monochrome + night vision is a false-colour thermal palette, so that combination needs an
        // RGB raster even though the user asked for "monochrome". Writing three channels into a
        // single-channel grey raster used to throw and silently keep the previous frame.
        boolean greenMode = !colour && config.green();
        boolean falseColour = !colour && nightVision && !greenMode;

        BufferedImage image = new BufferedImage(targetWidth, targetHeight,
                (colour || falseColour || greenMode) ? BufferedImage.TYPE_3BYTE_BGR
                        : BufferedImage.TYPE_BYTE_GRAY);
        byte[] destination = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        byte[] source = frame.rgba();
        int sourceWidth = frame.width();
        int sourceHeight = frame.height();

        boolean directScale = sourceWidth == targetWidth && sourceHeight == targetHeight;
        int destinationIndex = 0;
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = directScale ? y : Math.min(sourceHeight - 1, (int) ((long) y * sourceHeight / targetHeight));
            int sourceRow = sourceY * sourceWidth;
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = directScale ? x : Math.min(sourceWidth - 1, (int) ((long) x * sourceWidth / targetWidth));
                int sourceIndex = (sourceRow + sourceX) * 4;
                int red = source[sourceIndex] & 0xFF;
                int green = source[sourceIndex + 1] & 0xFF;
                int blue = source[sourceIndex + 2] & 0xFF;
                int luma = (red * 77 + green * 150 + blue * 29) >> 8;

                if (colour) {
                    if (nightVision) {
                        int boostedRed = (NIGHT_LUT[red] * 3) / 4;
                        int boostedGreen = NIGHT_LUT[green];
                        int boostedBlue = (NIGHT_LUT[blue] * 3) / 4;
                        destination[destinationIndex++] = (byte) boostedBlue;
                        destination[destinationIndex++] = (byte) boostedGreen;
                        destination[destinationIndex++] = (byte) boostedRed;
                    } else {
                        destination[destinationIndex++] = (byte) blue;
                        destination[destinationIndex++] = (byte) green;
                        destination[destinationIndex++] = (byte) red;
                    }
                } else if (greenMode) {
                    // Green phosphor look; night vision adds the light-amplifier gain.
                    int channel = nightVision
                            ? (int) Math.round(Math.pow(luma / 255.0D, 0.72D) * 255.0D * 2.2D)
                            : (int) Math.round(luma * 1.15D);
                    channel = Math.max(0, Math.min(255, channel));
                    int r = (channel * 42) / 255;
                    int b = (channel * 30) / 255;
                    destination[destinationIndex++] = (byte) b;
                    destination[destinationIndex++] = (byte) channel;
                    destination[destinationIndex++] = (byte) r;
                } else if (nightVision) {
                    // White-hot thermal look: hot objects go to white instead of the old red
                    // iron-bow palette, and the gain/gamma lifts dark entities and sub-levels.
                    int hot = thermalLuma(luma);
                    // Brightness gain: lift the whole night-vision image, and give very bright
                    // pixels (light sources, lit surfaces) an extra boost.
                    int white = Math.max(0, Math.min(255, (int) Math.round(
                            Math.pow(hot / 255.0D, 0.78D) * 255.0D * 2.6D)));
                    if (hot >= 190) {
                        white = Math.min(255, white + 35);
                    }
                    int r = Math.min(255, white + 6);
                    int g = white;
                    int b = Math.max(0, white - 8);
                    destination[destinationIndex++] = (byte) b;
                    destination[destinationIndex++] = (byte) g;
                    destination[destinationIndex++] = (byte) r;
                } else {
                    destination[destinationIndex++] = (byte) luma;
                }
            }
        }
        return image;
    }

    private static int thermalLuma(int luma) {
        double normalised = Math.pow(luma / 255.0D, THERMAL_GAMMA);
        double contrasted = (normalised - 0.5D) * THERMAL_CONTRAST + 0.5D;
        int index = (int) Math.round(contrasted * 255.0D);
        return Math.max(0, Math.min(255, index));
    }

    private byte[] writeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(bytes)) {
            this.writer.setOutput(stream);
            this.writer.write(null, new IIOImage(image, null, null), this.writeParam);
        }
        return bytes.toByteArray();
    }

    private static byte[] writeJpegStatic(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
        if (!ImageIO.write(image, "jpeg", bytes)) {
            throw new IOException("no JPEG writer");
        }
        return bytes.toByteArray();
    }

    /** Human readable description of the configured look, used in status details. */
    public static String lookDescription(CameraConfig config) {
        if (config.color() && config.nightVision()) {
            return "colour-night";
        }
        if (config.color()) {
            return "colour";
        }
        if (config.nightVision()) {
            return "thermal";
        }
        return "monochrome";
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MjpegEncoder(quality=%.2f)", JPEG_QUALITY);
    }
}
