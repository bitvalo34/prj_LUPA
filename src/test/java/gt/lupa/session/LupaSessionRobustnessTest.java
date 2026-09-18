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
import gt.lupa.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        assertNull(sender.closeCode);
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
    }
}
