package gt.lupa.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.concurrent.TileReadAdmission;
import gt.lupa.concurrent.TransientBufferBudget;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LupaSessionReconnectCleanupTest {
    @TempDir Path temp;

    private final ObjectMapper mapper =
            new ObjectMapper();

    @Test
    void closeIsIdempotentLateCallbackIsIgnoredAndReconnectStartsFresh()
            throws Exception {
        PublishedImageStore store =
                publishedImage();

        TileReader reader =
                (opened, z, x, y) ->
                        new TileData(
                                new byte[20_000],
                                256,
                                192);

        SessionAdmission sessions =
                new SessionAdmission(1);

        TileReadAdmission reads =
                new TileReadAdmission(
                        1,
                        1);

        TransientBufferBudget buffers =
                new TransientBufferBudget(
                        2 * 1024 * 1024);

        HoldingSender firstSender =
                new HoldingSender();

        LupaSession first =
                new LupaSession(
                        store,
                        reader,
                        Runnable::run,
                        Runnable::run,
                        Runnable::run,
                        sessions,
                        reads,
                        buffers);

        runInitialFlow(
                first,
                firstSender);

        assertEquals(
                1,
                sessions.snapshot().active());
        assertTrue(
                first.snapshotForTest()
                        .pendingDeliveries()
                        > 0);
        assertTrue(
                buffers.snapshot()
                        .reservedBytes()
                        > 0);

        int firstDelivery =
                deliveryId(
                        firstSender.payload);

        assertEquals(1, firstDelivery);

        first.onClosed(
                1006,
                "disconnect with pending delivery");
        first.onClosed(
                1006,
                "duplicate close");

        assertEquals(
                0,
                sessions.snapshot().active());
        assertEquals(
                0,
                buffers.snapshot()
                        .reservedBytes());
        assertEquals(
                0,
                reads.snapshot().inFlight());
        assertEquals(
                0,
                reads.snapshot().waiting());

        firstSender.completeLateWrite();

        assertEquals(
                LupaSessionState.CERRADA,
                first.snapshotForTest().state());
        assertEquals(
                0,
                first.snapshotForTest()
                        .pendingDeliveries());
        assertEquals(
                0,
                sessions.snapshot().active());
        assertEquals(
                0,
                buffers.snapshot()
                        .reservedBytes());

        HoldingSender secondSender =
                new HoldingSender();

        LupaSession second =
                new LupaSession(
                        store,
                        reader,
                        Runnable::run,
                        Runnable::run,
                        Runnable::run,
                        sessions,
                        reads,
                        buffers);

        runInitialFlow(
                second,
                secondSender);

        assertEquals(
                1,
                sessions.snapshot().active());

        int secondDelivery =
                deliveryId(
                        secondSender.payload);

        assertEquals(
                1,
                secondDelivery,
                "deliveryId scope must restart with the new connection");

        assertEquals(
                2,
                second.snapshotForTest()
                        .currentEpoch(),
                "new connection negotiates OPEN and VIEW from fresh local state");

        second.onClosed(
                1000,
                "done");

        assertEquals(
                0,
                sessions.snapshot().active());
        assertEquals(
                0,
                buffers.snapshot()
                        .reservedBytes());
    }

    private void runInitialFlow(
            LupaSession session,
            HoldingSender sender) {
        session.onOpen(sender);

        session.onText(
                sender,
                "{\"type\":\"HELLO\",\"version\":1,"
                        + "\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");

        session.onText(
                sender,
                "{\"type\":\"OPEN\","
                        + "\"epoch\":1,"
                        + "\"imageId\":\"photo\"}");

        session.onText(
                sender,
                """
                {"type":"VIEW","epoch":2,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":256,"height":192},
                 "viewportPx":{"width":256,"height":192},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """);
    }

    private int deliveryId(
            byte[] envelope) throws Exception {
        assertNotNull(envelope);

        ByteBuffer buffer =
                ByteBuffer.wrap(envelope);

        int headerLength =
                buffer.getInt();

        byte[] header =
                new byte[headerLength];

        buffer.get(header);

        JsonNode node =
                mapper.readTree(header);

        return node.path(
                        "deliveryId")
                .asInt();
    }

    private PublishedImageStore publishedImage()
            throws Exception {
        Path dataRoot =
                temp.resolve("data");

        Path version =
                dataRoot.resolve(
                        "pyramids/photo/v1");

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
                        List.of(
                                new ImageLevel(
                                        0,
                                        256,
                                        192)));

        Files.write(
                version.resolve("manifest.json"),
                new CatalogJson()
                        .writeManifest(manifest));

        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage(
                        "photo",
                        "v1",
                        256,
                        192,
                        256,
                        0));

        return new PublishedImageStore(
                dataRoot);
    }

    private static final class HoldingSender
            implements WebSocketEndpoint.Sender {
        private byte[] payload;
        private Runnable onWritten;

        @Override
        public boolean sendText(String text) {
            return true;
        }

        @Override
        public boolean sendBinary(
                byte[] payload) {
            throw new AssertionError(
                    "tracked binary send expected");
        }

        @Override
        public WebSocketEndpoint.BinarySend sendBinaryTracked(
                byte[] payload,
                Runnable onCommitted,
                Runnable onWritten) {
            assertNull(
                    this.payload,
                    "only one binary write should be pending");

            this.payload =
                    payload.clone();
            this.onWritten =
                    onWritten;

            onCommitted.run();

            return WebSocketEndpoint.BinarySend
                    .alreadyCommitted();
        }

        @Override
        public void close(
                int code,
                String reason) {}

        void completeLateWrite() {
            Runnable callback =
                    onWritten;

            assertNotNull(callback);

            onWritten = null;
            callback.run();
        }
    }
}
