package gt.lupa.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.storage.CatalogImage;
import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.CatalogPublisher;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import gt.lupa.storage.PublishedImageStore;
import gt.lupa.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LupaSessionTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void helloOpenAndUniformViewProduceWelcomeManifestAndPlan() throws Exception {
        PublishedImageStore store = publishedImage();
        LupaSession session = new LupaSession(store, Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        session.onText(sender, """
                {"type":"HELLO","version":1,"windowBytes":1048576,"bitmapBudgetBytes":67108864}
                """);
        JsonNode welcome = sender.lastJson();
        assertEquals("WELCOME", welcome.get("type").asText());
        assertEquals(1048576, welcome.get("windowBytes").asInt());
        assertEquals(262144, welcome.get("maxTileBytes").asInt());
        assertEquals(16, welcome.get("maxInFlight").asInt());

        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"photo"}
                """);
        JsonNode manifest = sender.lastJson();
        assertEquals("MANIFEST", manifest.get("type").asText());
        assertEquals(1, manifest.get("epoch").asInt());
        assertEquals("v1", manifest.get("imageVersion").asText());
        assertEquals(3, manifest.get("levels").size());

        session.onText(sender, """
                {"type":"VIEW","epoch":2,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """);
        JsonNode plan = sender.lastJson();
        assertEquals("PLAN", plan.get("type").asText());
        assertEquals(2, plan.get("epoch").asInt());
        assertEquals(1, plan.get("appliedLevel").asInt());
        assertEquals(1, plan.get("contextLevel").asInt());
        assertNull(sender.closeCode);
    }

    @Test
    void staleEpochIsIgnoredAndUnsupportedE20ViewIsExplicitlyRejected() throws Exception {
        LupaSession session = new LupaSession(publishedImage(), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        session.onText(sender, """
                {"type":"HELLO","version":1,"windowBytes":524288,"bitmapBudgetBytes":67108864}
                """);
        session.onText(sender, """
                {"type":"OPEN","epoch":5,"imageId":"photo"}
                """);
        int before = sender.texts.size();

        session.onText(sender, """
                {"type":"OPEN","epoch":4,"imageId":"photo"}
                """);
        assertEquals(before, sender.texts.size(), "older epoch must create no work or response");

        session.onText(sender, """
                {"type":"VIEW","epoch":6,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":-1,"mode":"uniform","focus":null}
                """);
        JsonNode error = sender.lastJson();
        assertEquals("ERROR", error.get("type").asText());
        assertEquals("BAD_VIEW", error.get("code").asText());
        assertEquals(6, error.get("epoch").asInt());
    }

    @Test
    void missingImageReturnsImageNotFoundWithoutDestroyingReadySession() throws Exception {
        LupaSession session = new LupaSession(publishedImage(), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        session.onText(sender, """
                {"type":"HELLO","version":1,"windowBytes":524288,"bitmapBudgetBytes":67108864}
                """);
        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"missing"}
                """);
        JsonNode error = sender.lastJson();
        assertEquals("ERROR", error.get("type").asText());
        assertEquals("IMAGE_NOT_FOUND", error.get("code").asText());

        session.onText(sender, """
                {"type":"OPEN","epoch":2,"imageId":"photo"}
                """);
        assertEquals("MANIFEST", sender.lastJson().get("type").asText());
        assertNull(sender.closeCode);
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
        private Integer closeCode;

        @Override
        public boolean sendText(String text) {
            texts.add(text);
            return true;
        }

        @Override
        public boolean sendBinary(byte[] payload) {
            fail("Block 2 must not emit binary TILE yet");
            return false;
        }

        @Override
        public void close(int code, String reason) {
            closeCode = code;
        }

        private JsonNode lastJson() throws Exception {
            assertFalse(texts.isEmpty());
            return mapper.readTree(texts.getLast());
        }
    }
}
