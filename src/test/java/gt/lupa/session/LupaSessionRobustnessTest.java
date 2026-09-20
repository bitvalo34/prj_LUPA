package gt.lupa.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.storage.CatalogImage;
import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.CatalogPublisher;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import gt.lupa.storage.PublishedImageStore;
import gt.lupa.storage.PublishedTileReader;
import gt.lupa.storage.TileData;
import gt.lupa.storage.TileReadException;
import gt.lupa.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class LupaSessionRobustnessTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingPublishedTileReturnsInternalReadErrorWithoutDone() throws Exception {
        PublishedImageStore store = publishedImage();
        LupaSession session = new LupaSession(store, Runnable::run);
        Sender sender = new Sender();
        session.onOpen(sender);

        hello(session, sender);
        open(session, sender, 1);
        session.onText(sender, view(2));

        assertTrue(sender.hasType("PLAN"));
        assertTrue(sender.hasError("INTERNAL_READ_ERROR"));
        assertFalse(sender.hasType("DONE"));
        assertTrue(sender.binaries.isEmpty());
        LupaSession.SessionSnapshot snapshot = session.snapshotForTest();
        assertEquals(snapshot.negotiatedWindowBytes(), snapshot.freeWindowBytes());
        assertEquals(0, snapshot.reservedBytes());
        assertNull(sender.closeCode);
    }

    @Test
    void staleReadFailureFromReplacedPlanIsIgnoredAndCurrentPlanCompletes() throws Exception {
        PublishedImageStore store = publishedImage();
        ManualExecutor disk = new ManualExecutor();
        int[] reads = {0};
        var reader = (gt.lupa.storage.TileReader) (opened, z, x, y) -> {
            reads[0]++;
            if (reads[0] == 1) {
                throw new TileReadException("obsolete read failed");
            }
            ImageLevel level = opened.manifest().levels().get(z);
            int w = Math.min(256, level.width() - x * 256);
            int h = Math.min(256, level.height() - y * 256);
            return new TileData(new byte[1024], w, h);
        };

        LupaSession session = new LupaSession(store, reader, Runnable::run, disk);
        Sender sender = new Sender();
        session.onOpen(sender);
        hello(session, sender);
        open(session, sender, 1);

        session.onText(sender, view(2));
        session.onText(sender, view(3));
        assertEquals(2, disk.size());

        disk.runAll();

        assertFalse(sender.hasError("INTERNAL_READ_ERROR"),
                "failure from invalidated epoch must not poison the current plan");
        assertTrue(sender.hasDoneForEpoch(3));
        assertFalse(sender.hasDoneForEpoch(2));
        LupaSession.SessionSnapshot snapshot = session.snapshotForTest();
        assertEquals(snapshot.negotiatedWindowBytes(),
                snapshot.freeWindowBytes() + snapshot.reservedBytes());
        assertNull(sender.closeCode);
    }

    @Test
    void disconnectWhileTileReadIsPendingDropsLateResult() throws Exception {
        PublishedImageStore store = publishedImage();
        ManualExecutor disk = new ManualExecutor();
        var reader = (gt.lupa.storage.TileReader) (opened, z, x, y) -> {
            ImageLevel level = opened.manifest().levels().get(z);
            int w = Math.min(256, level.width() - x * 256);
            int h = Math.min(256, level.height() - y * 256);
            return new TileData(new byte[1024], w, h);
        };

        LupaSession session = new LupaSession(store, reader, Runnable::run, disk);
        Sender sender = new Sender();
        session.onOpen(sender);
        hello(session, sender);
        open(session, sender, 1);
        session.onText(sender, view(2));
        assertEquals(1, disk.size());

        session.onClosed(1006, "abrupt");
        disk.runAll();

        assertTrue(sender.binaries.isEmpty(), "late disk result must not be emitted after disconnect");
        assertFalse(sender.hasType("DONE"));
    }

    @Test
    void tileDeletedAfterPlanBeforeDiskReadFailsPlanWithoutDone() throws Exception {
        PublishedImageStore store = publishedImage();
        Path tile = temp.resolve("data/pyramids/photo/v1/tiles/0/0_0.jpg");
        Files.createDirectories(tile.getParent());
        BufferedImage image = new BufferedImage(256, 192, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "jpeg", tile.toFile()));

        ManualExecutor disk = new ManualExecutor();
        LupaSession session = new LupaSession(
                store,
                new PublishedTileReader(store, 262144),
                Runnable::run,
                disk);
        Sender sender = new Sender();
        session.onOpen(sender);

        hello(session, sender);
        open(session, sender, 1);
        session.onText(sender, view(2));
        assertEquals(1, disk.size());

        Files.delete(tile);
        disk.runAll();

        assertTrue(sender.hasType("PLAN"));
        assertTrue(sender.hasError("INTERNAL_READ_ERROR"));
        assertFalse(sender.hasType("DONE"));
        assertTrue(sender.binaries.isEmpty());
    }

    @Test
    void twoSessionsKeepCreditsAndDeliveryIdsIndependent() throws Exception {
        PublishedImageStore store = publishedImage();
        var reader = (gt.lupa.storage.TileReader) (opened, z, x, y) -> {
            ImageLevel level = opened.manifest().levels().get(z);
            int w = Math.min(256, level.width() - x * 256);
            int h = Math.min(256, level.height() - y * 256);
            return new TileData(new byte[140_000], w, h);
        };

        LupaSession first = new LupaSession(store, reader, Runnable::run);
        LupaSession second = new LupaSession(store, reader, Runnable::run);
        Sender a = new Sender();
        Sender b = new Sender();
        first.onOpen(a);
        second.onOpen(b);

        hello(first, a);
        hello(second, b);
        open(first, a, 1);
        open(second, b, 1);
        first.onText(a, view(2));
        second.onText(b, view(2));

        assertEquals(3, a.binaries.size());
        assertEquals(3, b.binaries.size());
        assertEquals(1, deliveryId(a.binaries.getFirst()));
        assertEquals(1, deliveryId(b.binaries.getFirst()));

        first.onText(a, "{\"type\":\"RELEASE\",\"deliveryId\":1,\"status\":\"discarded\"}");
        assertEquals(4, a.binaries.size(), "first connection should resume");
        assertEquals(3, b.binaries.size(), "second connection credit must be unchanged");
    }

    private void hello(LupaSession session, Sender sender) {
        session.onText(sender,
                "{\"type\":\"HELLO\",\"version\":1,\"windowBytes\":524288,\"bitmapBudgetBytes\":67108864}");
    }

    private void open(LupaSession session, Sender sender, int epoch) {
        session.onText(sender,
                "{\"type\":\"OPEN\",\"epoch\":" + epoch + ",\"imageId\":\"photo\"}");
    }

    private static String view(int epoch) {
        return """
                {"type":"VIEW","epoch":%d,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """.formatted(epoch);
    }

    private int deliveryId(byte[] envelope) throws Exception {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(envelope);
        int h = buffer.getInt();
        byte[] header = new byte[h];
        buffer.get(header);
        return mapper.readTree(header).get("deliveryId").asInt();
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

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
    }

    private final class Sender implements WebSocketEndpoint.Sender {
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

        boolean hasType(String type) throws Exception {
            for (String text : texts) {
                JsonNode node = mapper.readTree(text);
                if (type.equals(node.path("type").asText())) return true;
            }
            return false;
        }

        boolean hasError(String code) throws Exception {
            for (String text : texts) {
                JsonNode node = mapper.readTree(text);
                if ("ERROR".equals(node.path("type").asText())
                        && code.equals(node.path("code").asText())) return true;
            }
            return false;
        }

        boolean hasDoneForEpoch(int epoch) throws Exception {
            for (String text : texts) {
                JsonNode node = mapper.readTree(text);
                if ("DONE".equals(node.path("type").asText())
                        && epoch == node.path("epoch").asInt()) return true;
            }
            return false;
        }
    }
}
