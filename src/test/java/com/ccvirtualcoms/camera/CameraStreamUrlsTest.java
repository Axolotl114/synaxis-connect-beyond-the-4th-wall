package com.ccvirtualcoms.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure JVM tests for {@link CameraStreamUrls}.
 *
 * <p>The class reads the {@code cc_virtual_coms.camera.httpPort} system property on every call, so
 * the tests snapshot and restore it to avoid leaking state into other tests (or into a Gradle run
 * that was started with {@code -Dcc_virtual_coms.camera.httpPort=...}).
 */
class CameraStreamUrlsTest {

    private String originalPortProperty;

    @BeforeEach
    void rememberProperty() {
        this.originalPortProperty = System.getProperty(CameraStreamUrls.PORT_PROPERTY);
        System.clearProperty(CameraStreamUrls.PORT_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (this.originalPortProperty == null) {
            System.clearProperty(CameraStreamUrls.PORT_PROPERTY);
        } else {
            System.setProperty(CameraStreamUrls.PORT_PROPERTY, this.originalPortProperty);
        }
    }

    // ------------------------------------------------------------------ port resolution

    @Test
    void defaultPortIs25586() {
        assertEquals(25586, CameraStreamUrls.DEFAULT_PORT);
        assertEquals(25586, CameraStreamUrls.port());
    }

    @Test
    void propertyOverridesPort() {
        System.setProperty(CameraStreamUrls.PORT_PROPERTY, "26000");
        assertEquals(26000, CameraStreamUrls.port());
    }

    @Test
    void propertyValueIsTrimmed() {
        System.setProperty(CameraStreamUrls.PORT_PROPERTY, "  25590  ");
        assertEquals(25590, CameraStreamUrls.port());
    }

    @Test
    void invalidPropertyValuesFallBackToTheDefault() {
        String[] invalid = {"", "   ", "abc", "0", "-1", "80", "1023", "65536", "70000", "25586x"};
        for (String value : invalid) {
            System.setProperty(CameraStreamUrls.PORT_PROPERTY, value);
            assertEquals(CameraStreamUrls.DEFAULT_PORT, CameraStreamUrls.port(),
                    "property value '" + value + "' should fall back to the default port");
        }
    }

    @Test
    void lowestAndHighestLegalPortsAreAccepted() {
        System.setProperty(CameraStreamUrls.PORT_PROPERTY, "1024");
        assertEquals(1024, CameraStreamUrls.port());

        System.setProperty(CameraStreamUrls.PORT_PROPERTY, "65535");
        assertEquals(65535, CameraStreamUrls.port());
    }

    // ------------------------------------------------------------------ URLs

    @Test
    void urlForUsesLowercaseHexOfThePackedPosition() {
        System.setProperty(CameraStreamUrls.PORT_PROPERTY, "26000");
        String url = CameraStreamUrls.urlFor(0x1234L);

        assertEquals("http://127.0.0.1:26000/camera/1234/stream.mjpeg", url);

        URI uri = URI.create(url);
        assertEquals("http", uri.getScheme());
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(26000, uri.getPort());
        assertEquals("/camera/1234/stream.mjpeg", uri.getPath());
    }

    @Test
    void urlForFormatsNegativePackedPositionsAsUnsignedHex() {
        // BlockPos#asLong can be negative; Long.toHexString gives the unsigned representation.
        String url = CameraStreamUrls.urlFor(-1L);
        assertTrue(url.endsWith("/camera/ffffffffffffffff/stream.mjpeg"), "unexpected url: " + url);
    }

    @Test
    void statusUrlPointsAtTheCameraListApi() {
        System.setProperty(CameraStreamUrls.PORT_PROPERTY, "26001");
        assertEquals("http://127.0.0.1:26001/api/cameras", CameraStreamUrls.statusUrl());
    }

    @Test
    void urlsAlwaysUseTheLoopbackHost() {
        assertTrue(CameraStreamUrls.urlFor(0L).startsWith("http://127.0.0.1:"));
        assertTrue(CameraStreamUrls.statusUrl().startsWith("http://127.0.0.1:"));
    }
}
