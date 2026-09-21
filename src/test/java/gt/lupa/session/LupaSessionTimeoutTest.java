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
import gt.lupa.test.ManualMonotonicScheduler;
import gt.lupa.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LupaSessionTimeoutTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void helloTimeoutClosesIncompleteSessionDeterministically()
            throws Exception {
        ManualMonotonicScheduler scheduler =
                new ManualMonotonicScheduler();
        LupaSession session =
                timedSession(
                        publishedImage(),
                        scheduler,
                        new byte[1024]);
        Sender sender = new Sender();

        session.onOpen(sender);

        scheduler.advance(Duration.ofSeconds(4));
        assertNull(sender.closeCode);

        scheduler.advance(Duration.ofSeconds(1));

        assertEquals(1008, sender.closeCode);
        assertTrue(sender.closeReason.contains("HELLO timeout"));
        assertEquals(
                LupaSessionState.CERRADA,
                session.snapshotForTest().state());
    }

    @Test
    void healthySessionWithoutNewViewDoesNotExpireFromHelloTimer()
            throws Exception {
        ManualMonotonicScheduler scheduler =
                new ManualMonotonicScheduler();
        LupaSession session =
                timedSession(
                        publishedImage(),
                        scheduler,
                        new byte[1024]);
        Sender sender = new Sender();

        session.onOpen(sender);
        hello(session, sender);

        scheduler.advance(Duration.ofMinutes(5));

        assertNull(sender.closeCode);
        assertEquals(
                LupaSessionState.LISTA,
                session.snapshotForTest().state());

        session.onClosed(1000, "done");
    }

    @Test
    void oldestWrittenDeliveryExpiresEvenWhenNewerDeliveryIsReleased()
            throws Exception {
        ManualMonotonicScheduler scheduler =
                new ManualMonotonicScheduler();
        LupaSession session =
                timedSession(
                        publishedImage(),
                        scheduler,
                        new byte[20_000]);
        Sender sender = new Sender();

        session.onOpen(sender);
        hello(session, sender);
        open(session, sender);
        view(session, sender);

        assertEquals(1, sender.binaryWrites.size());

        int firstDelivery =
                deliveryId(sender.binaryWrites.get(0).payload);
        sender.completeWrite(0);

        assertTrue(
                sender.binaryWrites.size() >= 2,
                "first completed write should allow next tile");

        scheduler.advance(Duration.ofSeconds(10));

        int secondDelivery =
                deliveryId(sender.binaryWrites.get(1).payload);
        sender.completeWrite(1);

        session.onText(
                sender,
                "{\"type\":\"RELEASE\",\"deliveryId\":"
                        + secondDelivery
                        + ",\"status\":\"displayed\"}");

        scheduler.advance(Duration.ofSeconds(19));
        assertNull(sender.closeCode);

        scheduler.advance(Duration.ofSeconds(1));

        assertEquals(1008, sender.closeCode);
        assertTrue(sender.closeReason.contains(
                "delivery " + firstDelivery + " RELEASE timeout"));
        assertEquals(
                LupaSessionState.CERRADA,
                session.snapshotForTest().state());
    }

    private LupaSession timedSession(
            PublishedImageStore store,
            ManualMonotonicScheduler scheduler,
            byte[] jpeg) {
        TileReader reader =
                (opened, z, x, y) -> {
                    ImageLevel level =
                            opened.manifest().levels().get(z);
                    int size =
                            opened.manifest().tileSize();
                    int width =
                            Math.min(
                                    size,
                                    level.width() - x * size);
                    int height =
                            Math.min(
                                    size,
                                    level.height() - y * size);
                    return new TileData(
                            jpeg,
                            width,
                            height);
                };

        return new LupaSession(
                store,
                reader,
                Runnable::run,
                Runnable::run,
                Runnable::run,
                new SessionAdmission(2),
                new TileReadAdmission(1, 2),
                new TransientBufferBudget(4 * 1024 * 1024),
                scheduler,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30));
    }

    private static void hello(
            LupaSession session,
            Sender sender) {
        session.onText(
                sender,
                "{\"type\":\"HELLO\",\"version\":1,"
                        + "\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");
    }

    private static void open(
            LupaSession session,
            Sender sender) {
        session.onText(
                sender,
                "{\"type\":\"OPEN\",\"epoch\":1,"
                        + "\"imageId\":\"photo\"}");
    }

    private static void view(
            LupaSession session,
            Sender sender) {
        session.onText(
                sender,
                """
                {"type":"VIEW","epoch":2,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """);
    }

    private int deliveryId(byte[] envelope)
            throws Exception {
        ByteBuffer buffer =
                ByteBuffer.wrap(envelope);
        int headerLength =
                buffer.getInt();
        byte[] header =
                new byte[headerLength];
        buffer.get(header);

        JsonNode node =
                mapper.readTree(header);
        return node.path("deliveryId").asInt();
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
                        1024,
                        768,
                        256,
                        0,
                        "onetile",
                        List.of(
                                new ImageLevel(
                                        0,
                                        256,
                                        192),
                                new ImageLevel(
                                        1,
                                        512,
                                        384),
                                new ImageLevel(
                                        2,
                                        1024,
                                        768)));

        Files.write(
                version.resolve("manifest.json"),
                new CatalogJson()
                        .writeManifest(manifest));

        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage(
                        "photo",
                        "v1",
                        1024,
                        768,
                        256,
                        2));

        return new PublishedImageStore(dataRoot);
    }

    private static final class BinaryWrite {
        private final byte[] payload;
        private final Runnable onWritten;

        private BinaryWrite(
                byte[] payload,
                Runnable onWritten) {
            this.payload = payload;
            this.onWritten = onWritten;
        }
    }

    private static final class Sender
            implements WebSocketEndpoint.Sender {
        private final List<BinaryWrite> binaryWrites =
                new ArrayList<>();
        private Integer closeCode;
        private String closeReason = "";

        @Override
        public boolean sendText(String text) {
            return true;
        }

        @Override
        public boolean sendBinary(byte[] payload) {
            throw new AssertionError(
                    "tracked binary send expected");
        }

        @Override
        public WebSocketEndpoint.BinarySend sendBinaryTracked(
                byte[] payload,
                Runnable onCommitted,
                Runnable onWritten) {
            onCommitted.run();
            binaryWrites.add(
                    new BinaryWrite(
                            payload.clone(),
                            onWritten));
            return WebSocketEndpoint.BinarySend.alreadyCommitted();
        }

        @Override
        public void close(
                int code,
                String reason) {
            closeCode = code;
            closeReason = reason == null ? "" : reason;
        }

        void completeWrite(int index) {
            binaryWrites.get(index)
                    .onWritten.run();
        }
    }
}
