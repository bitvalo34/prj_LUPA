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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class LupaSessionTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void helloOpenViewStreamThumbnailThenRegionAndDone() throws Exception {
        PublishedImageStore store = publishedImage();
        LupaSession session = new LupaSession(store, fakeTileReader(1024), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 1048576);
        assertEquals("WELCOME", sender.textType(0));
        assertEquals(1048576, sender.json(0).get("windowBytes").asInt());

        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"photo"}
                """);
        assertEquals("MANIFEST", sender.lastJson().get("type").asText());

        session.onText(sender, view(2));
        assertEquals(5, sender.binaries.size(), "thumbnail + four level-1 tiles expected");
        assertEquals("PLAN", sender.firstTextAfter("MANIFEST").get("type").asText());
        assertEquals("DONE", sender.lastJson().get("type").asText());
        assertEquals(5, sender.lastJson().get("sentTiles").asInt());

        JsonNode first = header(sender.binaries.get(0));
        assertEquals(0, first.get("z").asInt());
        assertEquals(0, first.get("x").asInt());
        assertEquals(0, first.get("y").asInt());

        for (int i = 1; i < sender.binaries.size(); i++) {
            assertEquals(1, header(sender.binaries.get(i)).get("z").asInt());
        }
        assertNull(sender.closeCode);
    }

    @Test
    void creditExhaustionPausesAndReleaseResumesWithoutDuplicateCredit() throws Exception {
        PublishedImageStore store = publishedImage();
        LupaSession session = new LupaSession(store, fakeTileReader(140_000), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 524288);
        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"photo"}
                """);
        session.onText(sender, view(2));

        assertEquals(3, sender.binaries.size(), "window should pause before fourth ~140 KiB delivery");
        assertNotEquals("DONE", sender.lastJson().path("type").asText());

        int firstDelivery = header(sender.binaries.getFirst()).get("deliveryId").asInt();
        release(session, sender, firstDelivery);
        assertEquals(4, sender.binaries.size());

        release(session, sender, firstDelivery);
        release(session, sender, 2_000_000_000);
        assertEquals(4, sender.binaries.size(), "duplicate/unknown RELEASE must not create credit");

        int secondDelivery = header(sender.binaries.get(1)).get("deliveryId").asInt();
        release(session, sender, secondDelivery);
        assertEquals(5, sender.binaries.size());
        assertEquals("DONE", sender.lastJson().get("type").asText());
        assertEquals(5, sender.lastJson().get("sentTiles").asInt());
    }

    @Test
    void staleDiskReadDoesNotEmitTileForReplacedEpoch() throws Exception {
        PublishedImageStore store = publishedImage();
        ManualExecutor disk = new ManualExecutor();
        LupaSession session = new LupaSession(store, fakeTileReader(1024), Runnable::run, disk);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 1048576);
        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"photo"}
                """);
        session.onText(sender, view(2));
        assertEquals(1, disk.size());

        session.onText(sender, view(3));
        assertEquals(2, disk.size());

        disk.runAll();

        assertFalse(sender.binaries.isEmpty());
        for (byte[] binary : sender.binaries) {
            assertEquals(3, header(binary).get("epoch").asInt(),
                    "late result from epoch 2 must be discarded");
        }
        assertEquals("DONE", sender.lastJson().get("type").asText());
        assertEquals(3, sender.lastJson().get("epoch").asInt());
    }

    @Test
    void oldDeliveryCanStillBeReleasedAfterNewEpoch() throws Exception {
        PublishedImageStore store = publishedImage();
        ManualExecutor disk = new ManualExecutor();
        LupaSession session = new LupaSession(store, fakeTileReader(140_000), Runnable::run, disk);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 524288);
        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"photo"}
                """);
        session.onText(sender, view(2));
        disk.runOne();
        disk.runAll();
        assertFalse(sender.binaries.isEmpty());

        int oldDelivery = header(sender.binaries.getFirst()).get("deliveryId").asInt();
        session.onText(sender, view(3));
        release(session, sender, oldDelivery);
        disk.runAll();

        assertNull(sender.closeCode);
        boolean sawEpoch3 = false;
        for (byte[] binary : sender.binaries) {
            if (header(binary).get("epoch").asInt() == 3) sawEpoch3 = true;
        }
        assertTrue(sawEpoch3);
    }

    @Test
    void staleEpochAndUnsupportedViewAreHandledWithoutMutatingValidState() throws Exception {
        LupaSession session = new LupaSession(publishedImage(), fakeTileReader(1024), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 524288);
        session.onText(sender, """
                {"type":"OPEN","epoch":5,"imageId":"photo"}
                """);
        int before = sender.texts.size();

        session.onText(sender, """
                {"type":"OPEN","epoch":4,"imageId":"photo"}
                """);
        assertEquals(before, sender.texts.size());

        session.onText(sender, """
                {"type":"VIEW","epoch":6,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":-1,"mode":"uniform","focus":null}
                """);
        JsonNode error = sender.lastJson();
        assertEquals("ERROR", error.get("type").asText());
        assertEquals("BAD_VIEW", error.get("code").asText());

        session.onText(sender, view(6));
        assertEquals("DONE", sender.lastJson().get("type").asText(),
                "rejected VIEW must not consume epoch 6");
    }

    @Test
    void missingImageReturnsImageNotFoundWithoutDestroyingReadySession() throws Exception {
        LupaSession session = new LupaSession(publishedImage(), fakeTileReader(1024), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 524288);
        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"missing"}
                """);
        assertEquals("IMAGE_NOT_FOUND", sender.lastJson().get("code").asText());

        session.onText(sender, """
                {"type":"OPEN","epoch":2,"imageId":"photo"}
                """);
        assertEquals("MANIFEST", sender.lastJson().get("type").asText());
        assertNull(sender.closeCode);
    }

    private void hello(LupaSession session, CapturingSender sender, int windowBytes) {
        session.onText(sender,
                "{\"type\":\"HELLO\",\"version\":1,\"windowBytes\":" + windowBytes
                        + ",\"bitmapBudgetBytes\":67108864}");
    }

    private static String view(int epoch) {
        return """
                {"type":"VIEW","epoch":%d,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """.formatted(epoch);
    }

    private void release(LupaSession session, CapturingSender sender, int deliveryId) {
        session.onText(sender,
                "{\"type\":\"RELEASE\",\"deliveryId\":" + deliveryId + ",\"status\":\"discarded\"}");
    }

    private TileReader fakeTileReader(int bytes) {
        return (opened, z, x, y) -> {
            ImageLevel level = opened.manifest().levels().get(z);
            int w = Math.min(256, level.width() - x * 256);
            int h = Math.min(256, level.height() - y * 256);
            byte[] jpeg = new byte[bytes];
            jpeg[0] = (byte) 0xff;
            jpeg[1] = (byte) 0xd8;
            jpeg[jpeg.length - 2] = (byte) 0xff;
            jpeg[jpeg.length - 1] = (byte) 0xd9;
            return new TileData(jpeg, w, h);
        };
    }

    private JsonNode header(byte[] envelope) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        int h = buffer.getInt();
        assertTrue(h > 0 && h <= 4096);
        byte[] jsonBytes = new byte[h];
        buffer.get(jsonBytes);
        JsonNode header = mapper.readTree(jsonBytes);
        assertEquals("TILE", header.get("type").asText());
        assertEquals(buffer.remaining(), header.get("payloadBytes").asInt());
        return header;
    }

    private PublishedImageStore publishedImage() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version = dataRoot.resolve("pyramids/photo/v1");
        Files.createDirectories(version);
        ImageManifest manifest = new ImageManifest(
                1, "photo", "v1", 1024, 768, 256, 0, "onetile",
                List.of(
                        new ImageLevel(0, 256, 192),
                        new ImageLevel(1, 512, 384),
                        new ImageLevel(2, 1024, 768)));
        Files.write(version.resolve("manifest.json"), new CatalogJson().writeManifest(manifest));
        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage("photo", "v1", 1024, 768, 256, 2));
        return new PublishedImageStore(dataRoot);
    }

    private final class CapturingSender implements WebSocketEndpoint.Sender {
        private final List<String> texts = new ArrayList<>();
        private final List<byte[]> binaries = new ArrayList<>();
        private Integer closeCode;

        @Override
        public boolean sendText(String text) {
            texts.add(text);
            return true;
        }

        @Override
        public boolean sendBinary(byte[] payload) {
            binaries.add(payload.clone());
            return true;
        }

        @Override
        public boolean sendBinary(byte[] payload, Runnable onWritten) {
            binaries.add(payload.clone());
            onWritten.run();
            return true;
        }

        @Override
        public void close(int code, String reason) {
            closeCode = code;
        }

        private JsonNode lastJson() throws Exception {
            assertFalse(texts.isEmpty());
            return mapper.readTree(texts.getLast());
        }

        private JsonNode json(int index) throws Exception {
            return mapper.readTree(texts.get(index));
        }

        private String textType(int index) throws Exception {
            return json(index).path("type").asText();
        }

        private JsonNode firstTextAfter(String type) throws Exception {
            for (int i = 0; i + 1 < texts.size(); i++) {
                if (type.equals(json(i).path("type").asText())) return json(i + 1);
            }
            fail("text after " + type + " not found");
            return null;
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runOne() {
            Runnable next = tasks.poll();
            if (next != null) next.run();
        }

        void runAll() {
            while (!tasks.isEmpty()) runOne();
        }
    }
}
