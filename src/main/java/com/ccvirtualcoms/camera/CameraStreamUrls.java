package com.ccvirtualcoms.camera;

/**
 * Runtime URLs of the MJPEG fallback stream server.
 *
 * <p>The stream is deliberately kept off the 25585 JSON protocol: it is a separate loopback HTTP
 * server (default port 25586) that any local application can consume with a plain
 * {@code multipart/x-mixed-replace} GET. The same server also exposes a small status JSON used by
 * the Python client to list cameras.
 */
public final class CameraStreamUrls {

    /** System property used to override the fallback HTTP port. */
    public static final String PORT_PROPERTY = "cc_virtual_coms.camera.httpPort";

    /** Default loopback port of the MJPEG fallback server. */
    public static final int DEFAULT_PORT = 25586;

    private CameraStreamUrls() {
    }

    /** The configured port (validated into 1024-65535). */
    public static int port() {
        String raw = System.getProperty(PORT_PROPERTY);
        if (raw != null) {
            try {
                int value = Integer.parseInt(raw.trim());
                if (value >= 1024 && value <= 65535) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the default; a bad -D value must not take the server down.
            }
        }
        return DEFAULT_PORT;
    }

    /** The stream URL for a camera identified by its packed block position. */
    public static String urlFor(long positionKey) {
        return "http://127.0.0.1:" + port() + "/camera/" + Long.toHexString(positionKey) + "/stream.mjpeg";
    }

    /** The status JSON URL of the fallback server. */
    public static String statusUrl() {
        return "http://127.0.0.1:" + port() + "/api/cameras";
    }
}
