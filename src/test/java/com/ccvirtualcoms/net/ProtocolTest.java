package com.ccvirtualcoms.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Pure JVM contract tests for the binary framing protocol.
 *
 * <p>These tests deliberately depend on nothing but the JDK and
 * {@link com.ccvirtualcoms.net.Protocol}; they do not start Minecraft, NeoForge or the mod, so they
 * can run in the plain JUnit 5 configuration as well as under MDG's {@code unitTest} mode.
 *
 * <p>The protocol is a stable user facing contract (Python clients and external programs parse it):
 * {@code VERSION = 1}, 12 byte header, 8 MiB payload ceiling. If one of these tests has to change,
 * the wire protocol changed.
 */
class ProtocolTest {

    private static final int[] ALL_MESSAGE_TYPES = {
            Protocol.MSG_DATA_PUSH,
            Protocol.MSG_DATA_WRITE,
            Protocol.MSG_HEARTBEAT,
            Protocol.MSG_ERROR,
    };

    // ------------------------------------------------------------------ framing

    @Test
    void encodeWritesCcvcMagicVersionTypeAndBigEndianLength() {
        byte[] payload = "{\"port\":\"drive\"}".getBytes(StandardCharsets.UTF_8);
        byte[] frame = Protocol.encode(Protocol.MSG_DATA_WRITE, payload);

        assertEquals(Protocol.HEADER_SIZE + payload.length, frame.length);
        assertArrayEquals(new byte[] {'C', 'C', 'V', 'C'}, new byte[] {frame[0], frame[1], frame[2], frame[3]});
        assertEquals(Protocol.VERSION, ((frame[4] & 0xFF) << 8) | (frame[5] & 0xFF));
        assertEquals(Protocol.MSG_DATA_WRITE, ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF));
        long declaredLength = ((long) (frame[8] & 0xFF) << 24)
                | ((long) (frame[9] & 0xFF) << 16)
                | ((long) (frame[10] & 0xFF) << 8)
                | (frame[11] & 0xFF);
        assertEquals(payload.length, declaredLength);
        assertArrayEquals(payload, Arrays.copyOfRange(frame, Protocol.HEADER_SIZE, frame.length));
    }

    @Test
    void roundTripsEveryMessageTypeWithJsonPayload() throws Exception {
        String json = "{\"com\":\"COM1\",\"port\":\"drive\",\"type\":\"real\",\"value\":0.25}";
        for (int messageType : ALL_MESSAGE_TYPES) {
            byte[] frame = Protocol.encodeJson(messageType, json);
            Protocol.Frame decoded = Protocol.read(new ByteArrayInputStream(frame));

            assertEquals(messageType, decoded.messageType());
            assertEquals(json, decoded.json());
            assertFalse(decoded.isEmpty());
            assertFalse(Protocol.messageTypeName(messageType).startsWith("UNKNOWN"));
        }
    }

    @Test
    void readsTwoFramesBackToBackFromOneStream() throws Exception {
        byte[] first = Protocol.encodeJson(Protocol.MSG_DATA_PUSH, "{\"port\":\"a\"}");
        byte[] second = Protocol.encodeJson(Protocol.MSG_HEARTBEAT, "");
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);

        InputStream in = new ByteArrayInputStream(both);
        Protocol.Frame frameA = Protocol.read(in);
        Protocol.Frame frameB = Protocol.read(in);

        assertEquals(Protocol.MSG_DATA_PUSH, frameA.messageType());
        assertEquals("{\"port\":\"a\"}", frameA.json());
        assertEquals(Protocol.MSG_HEARTBEAT, frameB.messageType());
        assertTrue(frameB.isEmpty());
    }

    @Test
    void nullPayloadEncodesAsEmptyFrame() throws Exception {
        byte[] frame = Protocol.encode(Protocol.MSG_HEARTBEAT, null);
        Protocol.Frame decoded = Protocol.read(new ByteArrayInputStream(frame));

        assertEquals(Protocol.HEADER_SIZE, frame.length);
        assertEquals(Protocol.MSG_HEARTBEAT, decoded.messageType());
        assertTrue(decoded.isEmpty());
        assertEquals("", decoded.json());
    }

    // ------------------------------------------------------------------ limits and defence

    @Test
    void acceptsPayloadOfExactlyMaxSize() throws Exception {
        byte[] payload = new byte[Protocol.MAX_PAYLOAD_SIZE];
        Arrays.fill(payload, (byte) 'x');

        byte[] frame = Protocol.encode(Protocol.MSG_DATA_PUSH, payload);
        Protocol.Frame decoded = Protocol.read(new ByteArrayInputStream(frame));

        assertEquals(Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD_SIZE, frame.length);
        assertEquals(Protocol.MAX_PAYLOAD_SIZE, decoded.payload().length);
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void rejectsPayloadLargerThanMaxSizeOnEncode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Protocol.encode(Protocol.MSG_DATA_PUSH, new byte[Protocol.MAX_PAYLOAD_SIZE + 1]));
        assertTrue(error.getMessage().contains("limit"), "message should mention the protocol limit: " + error.getMessage());
    }

    @Test
    void rejectsDeclaredLengthLargerThanMaxSizeOnRead() {
        byte[] frame = Protocol.encode(Protocol.MSG_DATA_PUSH, new byte[0]);
        long declared = (long) Protocol.MAX_PAYLOAD_SIZE + 1L;
        frame[8] = (byte) (declared >>> 24);
        frame[9] = (byte) (declared >>> 16);
        frame[10] = (byte) (declared >>> 8);
        frame[11] = (byte) declared;

        // No payload bytes follow: the length must be rejected before any allocation happens.
        Protocol.ProtocolException error = assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.read(new ByteArrayInputStream(frame)));
        assertTrue(error.getMessage().contains("limit"), "message should mention the protocol limit: " + error.getMessage());
    }

    @Test
    void rejectsBadMagic() {
        byte[] frame = Protocol.encode(Protocol.MSG_HEARTBEAT, new byte[0]);
        frame[0] = 'X';

        Protocol.ProtocolException error = assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.read(new ByteArrayInputStream(frame)));
        assertTrue(error.getMessage().contains("magic"), "message should mention magic: " + error.getMessage());
    }

    @Test
    void rejectsUnsupportedVersion() {
        byte[] frame = Protocol.encode(Protocol.MSG_HEARTBEAT, new byte[0]);
        frame[4] = 0x00;
        frame[5] = (byte) (Protocol.VERSION + 1);

        Protocol.ProtocolException error = assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.read(new ByteArrayInputStream(frame)));
        assertTrue(error.getMessage().contains("version"), "message should mention version: " + error.getMessage());
    }

    @Test
    void rejectsTruncatedHeader() {
        byte[] partial = {'C', 'C', 'V'};
        assertThrows(EOFException.class, () -> Protocol.read(new ByteArrayInputStream(partial)));
    }

    @Test
    void rejectsTruncatedPayloadBody() {
        byte[] frame = Protocol.encodeJson(Protocol.MSG_DATA_WRITE, "{\"port\":\"x\"}");
        byte[] truncated = Arrays.copyOf(frame, frame.length - 3);

        assertThrows(EOFException.class, () -> Protocol.read(new ByteArrayInputStream(truncated)));
    }

    @Test
    void unknownMessageTypesRoundTripAndAreNamedForLogging() throws Exception {
        byte[] frame = Protocol.encode(0x0102, new byte[0]);
        Protocol.Frame decoded = Protocol.read(new ByteArrayInputStream(frame));

        assertEquals(0x0102, decoded.messageType());
        assertEquals("UNKNOWN(0x102)", Protocol.messageTypeName(0x0102));
    }

    // ------------------------------------------------------------------ stable contract

    @Test
    void protocolConstantsAreStableContract() {
        assertArrayEquals(new byte[] {'C', 'C', 'V', 'C'}, Protocol.MAGIC);
        assertEquals(1, Protocol.VERSION, "VERSION is part of the wire contract; bumping it breaks existing clients");
        assertEquals(12, Protocol.HEADER_SIZE);
        assertEquals(8 * 1024 * 1024, Protocol.MAX_PAYLOAD_SIZE);
        assertEquals(0x01, Protocol.MSG_DATA_PUSH);
        assertEquals(0x02, Protocol.MSG_DATA_WRITE);
        assertEquals(0x03, Protocol.MSG_HEARTBEAT);
        assertEquals(0x04, Protocol.MSG_ERROR);
    }

    @Test
    void messageTypeNamesAreStable() {
        assertEquals("DATA_PUSH", Protocol.messageTypeName(Protocol.MSG_DATA_PUSH));
        assertEquals("DATA_WRITE", Protocol.messageTypeName(Protocol.MSG_DATA_WRITE));
        assertEquals("HEARTBEAT", Protocol.messageTypeName(Protocol.MSG_HEARTBEAT));
        assertEquals("ERROR", Protocol.messageTypeName(Protocol.MSG_ERROR));
    }
}
