package gt.lupa.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.storage.CatalogImage;
import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.CatalogPublisher;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import gt.lupa.storage.PublishedImageStore;
import gt.lupa.storage.TileData;
import gt.lupa.storage.TileReader;
import gt.lupa.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LupaSelectiveAckTest {
    @TempDir Path temp;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingDeliveryIsRetransmittedSelectivelyWithSameIdAndNoExtraCredit()
            throws Exception {
        AtomicInteger reads = new AtomicInteger();

        TileReader reader =
                (opened, z, x, y) -> {
                    reads.incrementAndGet();
                    return new TileData(
                            new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9},
                            256,
                            192);
                };

        LupaSession session =
                new LupaSession(
                        publishedImage(),
                        reader,
                        Runnable::run,
                        Runnable::run,
                        Runnable::run);

        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender);
        session.onText(
                sender,
                "{\"type\":\"OPEN\",\"epoch\":1,\"imageId\":\"photo\"}");
        session.onText(
                sender,
                """
                {"type":"VIEW","epoch":2,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":256,"height":192},
                 "viewportPx":{"width":256,"height":192},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """);

        assertFalse(sender.binaries.isEmpty());
        JsonNode original = header(sender.binaries.getFirst());
        int deliveryId = original.path("deliveryId").asInt();
        assertEquals(1, deliveryId);

        LupaSession.SessionSnapshot before =
                session.snapshotForTest();
        assertEquals(1, before.pendingDeliveries());
        assertTrue(before.reservedBytes() > 0);

        session.onText(
                sender,
                "{\"type\":\"ACK_STATE\",\"epoch\":2,"
                        + "\"received\":[],\"missing\":[1]}");

        assertTrue(sender.binaries.size() >= 2);
        JsonNode retry = header(sender.binaries.getLast());
        assertEquals(deliveryId, retry.path("deliveryId").asInt());
        assertEquals(1, retry.path("retry").asInt());
        assertEquals(2, reads.get(), "only the missing tile should be re-read");

        LupaSession.SessionSnapshot during =
                session.snapshotForTest();
        assertEquals(
                before.reservedBytes(),
                during.reservedBytes(),
                "selective retransmit must not reserve a second flow-control credit");
        assertEquals(
                before.freeWindowBytes(),
                during.freeWindowBytes(),
                "selective retransmit must reuse the existing reservation");

        session.onText(
                sender,
                "{\"type\":\"ACK_STATE\",\"epoch\":2,"
                        + "\"received\":[[1,1]],\"missing\":[]}");

        LupaSession.SessionSnapshot after =
                session.snapshotForTest();
        assertEquals(0, after.pendingDeliveries());
        assertEquals(after.negotiatedWindowBytes(), after.freeWindowBytes());
        assertEquals(0, after.reservedBytes());
        assertNull(sender.closeCode);
    }

    @Test
    void overlappingSelectiveAckSetsAreRejected() throws Exception {
        LupaSession session =
                new LupaSession(
                        publishedImage(),
                        (opened, z, x, y) ->
                                new TileData(
                                        new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9},
                                        256,
                                        192),
                        Runnable::run);

        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);
        hello(session, sender);

        session.onText(
                sender,
                "{\"type\":\"ACK_STATE\",\"epoch\":1,"
                        + "\"received\":[[3,5]],\"missing\":[4]}");

        assertEquals(1008, sender.closeCode);
    }

    private void hello(
            LupaSession session,
            CapturingSender sender) {
        session.onText(
                sender,
                "{\"type\":\"HELLO\",\"version\":1,"
                        + "\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");
    }

    private JsonNode header(byte[] envelope) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        int length = buffer.getInt();
        byte[] json = new byte[length];
        buffer.get(json);
        return mapper.readTree(json);
    }

    private PublishedImageStore publishedImage() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version = dataRoot.resolve("pyramids/photo/v1");
        Files.createDirectories(version);

        ImageManifest manifest =
                new ImageManifest(
                        1,
                        "photo",
                        "v1",
                        256,
                        192,
                        256,
                        0,
                        "onetile",
                        List.of(new ImageLevel(0, 256, 192)));

        Files.write(
                version.resolve("manifest.json"),
                new CatalogJson().writeManifest(manifest));

        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage("photo", "v1", 256, 192, 256, 0));

        return new PublishedImageStore(dataRoot);
    }

    private static final class CapturingSender
            implements WebSocketEndpoint.Sender {
        private final List<String> texts = new ArrayList<>();
        private final List<byte[]> binaries = new ArrayList<>();
        private Integer closeCode;

        @Override
        public boolean sendText(String text) {
            texts.add(text);
            return true;
        }

        @Override
        public WebSocketEndpoint.TrackedSend sendTextTracked(
                String text,
                Runnable onCommitted,
                Runnable onWritten) {
            texts.add(text);
            onCommitted.run();
            onWritten.run();
            return WebSocketEndpoint.BinarySend.alreadyCommitted();
        }

        @Override
        public boolean sendBinary(byte[] payload) {
            binaries.add(payload.clone());
            return true;
        }

        @Override
        public WebSocketEndpoint.BinarySend sendBinaryTracked(
                byte[] payload,
                Runnable onCommitted,
                Runnable onWritten) {
            binaries.add(payload.clone());
            onCommitted.run();
            onWritten.run();
            return WebSocketEndpoint.BinarySend.alreadyCommitted();
        }

        @Override
        public void close(int code, String reason) {
            closeCode = code;
        }
    }
}
