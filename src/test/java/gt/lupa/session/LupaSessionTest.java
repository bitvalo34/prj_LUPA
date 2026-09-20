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
    void openingThumbnailIsSentOnlyOnFirstPlanOfSameOpen() throws Exception {
        PublishedImageStore store = publishedImage();
        LupaSession session = new LupaSession(store, fakeTileReader(1024), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 1048576);
        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"photo"}
                """);
        session.onText(sender, view(2));
        assertEquals(5, sender.binaries.size());
        assertEquals(5, sender.lastJson().get("sentTiles").asInt());

        session.onText(sender, view(3));
        assertEquals(9, sender.binaries.size(), "second plan should add only four level-1 region tiles");
        assertEquals("DONE", sender.lastJson().get("type").asText());
        assertEquals(4, sender.lastJson().get("sentTiles").asInt());

        for (int i = 5; i < 9; i++) {
            assertEquals(1, header(sender.binaries.get(i)).get("z").asInt());
        }
    }

    @Test
    void partialRegionAfterOpeningSendsOnlyIntersectingDetailTile() throws Exception {
        PublishedImageStore store = publishedImage();
        LupaSession session = new LupaSession(store, fakeTileReader(1024), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 1048576);
        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"photo"}
                """);
        session.onText(sender, view(2));
        assertEquals(5, sender.binaries.size());

        int before = sender.binaries.size();
        session.onText(sender, """
                {"type":"VIEW","epoch":3,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":256,"y":192,"width":256,"height":192},
                 "viewportPx":{"width":128,"height":96},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """);

        assertEquals(before + 1, sender.binaries.size(),
                "partial region must not resend the thumbnail or all level tiles");
        JsonNode tile = header(sender.binaries.getLast());
        assertEquals(3, tile.get("epoch").asInt());
        assertEquals(1, tile.get("z").asInt());
        assertEquals(0, tile.get("x").asInt());
        assertEquals(0, tile.get("y").asInt());
        assertEquals("DONE", sender.lastJson().get("type").asText());
        assertEquals(1, sender.lastJson().get("sentTiles").asInt());
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
        assertEquals(1, firstDelivery);
        assertEquals(2, header(sender.binaries.get(1)).get("deliveryId").asInt());
        assertEquals(3, header(sender.binaries.get(2)).get("deliveryId").asInt());

        release(session, sender, firstDelivery);
        assertEquals(4, sender.binaries.size());
        assertEquals(4, header(sender.binaries.get(3)).get("deliveryId").asInt(),
                "waiting for credit must not consume an unused deliveryId");

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
                 "detailOffset":-3,"mode":"uniform","focus":null}
                """);
        JsonNode error = sender.lastJson();
        assertEquals("ERROR", error.get("type").asText());
        assertEquals("BAD_VIEW", error.get("code").asText());

        session.onText(sender, view(6));
        assertEquals("DONE", sender.lastJson().get("type").asText(),
                "rejected VIEW must not consume epoch 6");
    }

    @Test
    void detailOffsetIsAppliedToRealPlanLevel() throws Exception {
        LupaSession session = new LupaSession(publishedImage(), fakeTileReader(1024), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 1048576);
        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"photo"}
                """);
        session.onText(sender, """
                {"type":"VIEW","epoch":2,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":-1,"mode":"uniform","focus":null}
                """);

        JsonNode plan = sender.firstTextAfter("MANIFEST");
        assertEquals("PLAN", plan.get("type").asText());
        assertEquals(0, plan.get("appliedLevel").asInt());
        assertEquals(0, plan.get("contextLevel").asInt());
        assertEquals(1, sender.binaries.size(), "z=0 must be enough after detailOffset=-1");
        assertEquals(0, header(sender.binaries.getFirst()).get("z").asInt());
        assertEquals("DONE", sender.lastJson().get("type").asText());
    }

    @Test
    void focusViewPublishesDifferentContextAndDetailLevels() throws Exception {
        LupaSession session = new LupaSession(publishedImage(), fakeTileReader(1024), Runnable::run);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 1048576);
        session.onText(sender, """
                {"type":"OPEN","epoch":1,"imageId":"photo"}
                """);
        session.onText(sender, """
                {"type":"VIEW","epoch":2,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":1024,"height":768},
                 "detailOffset":0,"mode":"focus",
                 "focus":{"x":512,"y":384,"radiusPx":120}}
                """);

        JsonNode plan = sender.firstTextAfter("MANIFEST");
        assertEquals(2, plan.get("appliedLevel").asInt());
        assertEquals(1, plan.get("contextLevel").asInt());
        assertEquals("DONE", sender.lastJson().get("type").asText());

        boolean sawFocusLevel = false;
        boolean sawContextLevel = false;
        for (byte[] binary : sender.binaries) {
            int z = header(binary).get("z").asInt();
            if (z == 2) sawFocusLevel = true;
            if (z == 1) sawContextLevel = true;
        }
        assertTrue(sawFocusLevel);
        assertTrue(sawContextLevel);
    }

    @Test
    void newViewCancelsReservedButUncommittedTileAndDoesNotReuseDeliveryId() throws Exception {
        LupaSession session = new LupaSession(
                publishedImage(), fakeTileReader(262_000), Runnable::run);
        ControlledSender sender = new ControlledSender(HoldMode.BEFORE_COMMIT);
        session.onOpen(sender);

        session.onText(sender,
                "{\"type\":\"HELLO\",\"version\":1,\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");
        session.onText(sender, "{\"type\":\"OPEN\",\"epoch\":1,\"imageId\":\"photo\"}");
        session.onText(sender, view(2));

        assertNotNull(sender.firstSend);
        assertFalse(sender.firstSend.committed());
        assertEquals(0, sender.binaries.size(), "queued tile must not be visible on the wire");

        session.onText(sender, view(3));

        assertTrue(sender.firstSend.cancelled);
        assertFalse(sender.binaries.isEmpty(),
                "credit from cancelled uncommitted delivery must be refunded for the new plan");
        JsonNode firstVisible = header(sender.binaries.getFirst());
        assertEquals(3, firstVisible.get("epoch").asInt());
        assertEquals(2, firstVisible.get("deliveryId").asInt(),
                "cancelled deliveryId=1 is never reused");
        assertNull(sender.closeCode);
    }

    @Test
    void committedOldTileKeepsReservationUntilReleaseAndReleaseMayBeatWriteCallback() throws Exception {
        LupaSession session = new LupaSession(
                publishedImage(), fakeTileReader(262_000), Runnable::run);
        ControlledSender sender = new ControlledSender(HoldMode.AFTER_COMMIT);
        session.onOpen(sender);

        session.onText(sender,
                "{\"type\":\"HELLO\",\"version\":1,\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");
        session.onText(sender, "{\"type\":\"OPEN\",\"epoch\":1,\"imageId\":\"photo\"}");
        session.onText(sender, view(2));

        assertNotNull(sender.firstSend);
        assertTrue(sender.firstSend.committed());
        assertEquals(1, sender.binaries.size());
        int oldDelivery = header(sender.binaries.getFirst()).get("deliveryId").asInt();

        session.onText(sender, view(3));
        assertEquals(1, sender.binaries.size(),
                "committed old TILE keeps its credit and cannot be removed by VIEW replacement");

        session.onText(sender,
                "{\"type\":\"RELEASE\",\"deliveryId\":" + oldDelivery
                        + ",\"status\":\"discarded\"}");

        assertTrue(sender.binaries.size() >= 2,
                "RELEASE of old epoch must restore credit and let the current plan advance");
        JsonNode newTile = header(sender.binaries.get(1));
        assertEquals(3, newTile.get("epoch").asInt());
        assertTrue(newTile.get("deliveryId").asInt() > oldDelivery);

        int beforeLateCallback = sender.binaries.size();
        sender.firstSend.completeWritten();
        session.onText(sender,
                "{\"type\":\"RELEASE\",\"deliveryId\":" + oldDelivery
                        + ",\"status\":\"discarded\"}");

        assertEquals(beforeLateCallback, sender.binaries.size(),
                "late write callback and duplicate RELEASE must not manufacture credit");
        assertNull(sender.closeCode);
    }

    @Test
    void replacedViewCancelsQueuedPlanBeforeItBecomesVisible() throws Exception {
        LupaSession session = new LupaSession(
                publishedImage(), fakeTileReader(1024), Runnable::run);
        ControlHoldingSender sender = new ControlHoldingSender("PLAN");
        session.onOpen(sender);

        hello(session, sender, 1048576);
        session.onText(sender, "{\"type\":\"OPEN\",\"epoch\":1,\"imageId\":\"photo\"}");
        session.onText(sender, view(2));

        assertNotNull(sender.held);
        assertFalse(sender.held.committed());
        assertTrue(sender.binaries.isEmpty(),
                "TILE must not start before PLAN crosses the commitment boundary");

        session.onText(sender, view(3));

        assertTrue(sender.held.cancelled);
        assertTrue(sender.texts.stream().noneMatch(text -> {
            try {
                JsonNode node = mapper.readTree(text);
                return "PLAN".equals(node.path("type").asText())
                        && node.path("epoch").asInt() == 2;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
        assertTrue(sender.texts.stream().anyMatch(text -> {
            try {
                JsonNode node = mapper.readTree(text);
                return "PLAN".equals(node.path("type").asText())
                        && node.path("epoch").asInt() == 3;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
        assertFalse(sender.binaries.isEmpty());
        for (byte[] binary : sender.binaries) {
            assertEquals(3, header(binary).path("epoch").asInt());
        }
    }

    @Test
    void replacedViewCancelsQueuedDoneBeforeItBecomesVisible() throws Exception {
        LupaSession session = new LupaSession(
                publishedImage(), fakeTileReader(1024), Runnable::run);
        ControlHoldingSender sender = new ControlHoldingSender("DONE");
        session.onOpen(sender);

        hello(session, sender, 1048576);
        session.onText(sender, "{\"type\":\"OPEN\",\"epoch\":1,\"imageId\":\"photo\"}");
        session.onText(sender, view(2));

        assertNotNull(sender.held);
        assertFalse(sender.held.committed());
        assertTrue(sender.texts.stream().noneMatch(text -> {
            try {
                JsonNode node = mapper.readTree(text);
                return "DONE".equals(node.path("type").asText())
                        && node.path("epoch").asInt() == 2;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));

        session.onText(sender, view(3));

        assertTrue(sender.held.cancelled);
        assertTrue(sender.texts.stream().noneMatch(text -> {
            try {
                JsonNode node = mapper.readTree(text);
                return "DONE".equals(node.path("type").asText())
                        && node.path("epoch").asInt() == 2;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
        assertTrue(sender.texts.stream().anyMatch(text -> {
            try {
                JsonNode node = mapper.readTree(text);
                return "DONE".equals(node.path("type").asText())
                        && node.path("epoch").asInt() == 3;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    @Test
    void laterSuccessfulOpenWinsEvenWhenOlderStorageResultCompletesLast() throws Exception {
        ManualExecutor metadata = new ManualExecutor();
        LupaSession session = new LupaSession(
                publishedImage(),
                fakeTileReader(1024),
                Runnable::run,
                Runnable::run,
                metadata);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 1048576);
        session.onText(sender, "{\"type\":\"OPEN\",\"epoch\":2,\"imageId\":\"photo\"}");
        session.onText(sender, "{\"type\":\"OPEN\",\"epoch\":3,\"imageId\":\"photo\"}");
        assertEquals(2, metadata.size());

        metadata.runLast();
        metadata.runAll();

        long manifests = sender.texts.stream()
                .map(text -> {
                    try { return mapper.readTree(text); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .filter(node -> "MANIFEST".equals(node.path("type").asText()))
                .count();
        assertEquals(1, manifests);
        JsonNode manifest = sender.texts.stream()
                .map(text -> {
                    try { return mapper.readTree(text); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .filter(node -> "MANIFEST".equals(node.path("type").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals(3, manifest.get("epoch").asInt());
    }

    @Test
    void invalidHigherOpenDoesNotConsumeEpochOrSuppressOlderValidOpen() throws Exception {
        ManualExecutor metadata = new ManualExecutor();
        LupaSession session = new LupaSession(
                publishedImage(),
                fakeTileReader(1024),
                Runnable::run,
                Runnable::run,
                metadata);
        CapturingSender sender = new CapturingSender();
        session.onOpen(sender);

        hello(session, sender, 1048576);
        session.onText(sender, "{\"type\":\"OPEN\",\"epoch\":2,\"imageId\":\"photo\"}");
        session.onText(sender, "{\"type\":\"OPEN\",\"epoch\":3,\"imageId\":\"missing\"}");

        metadata.runLast();
        assertEquals("IMAGE_NOT_FOUND", sender.lastJson().path("code").asText());

        metadata.runAll();
        assertEquals("MANIFEST", sender.lastJson().path("type").asText());
        assertEquals(2, sender.lastJson().path("epoch").asInt(),
                "failed epoch 3 must not consume the last accepted epoch");
        assertNull(sender.closeCode);
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

    private void hello(LupaSession session, WebSocketEndpoint.Sender sender, int windowBytes) {
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

    private final class ControlHoldingSender implements WebSocketEndpoint.Sender {
        private final List<String> texts = new ArrayList<>();
        private final List<byte[]> binaries = new ArrayList<>();
        private final String holdType;
        private TestControlSend held;
        private Integer closeCode;

        private ControlHoldingSender(String holdType) {
            this.holdType = holdType;
        }

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
            try {
                JsonNode node = mapper.readTree(text);
                if (held == null && holdType.equals(node.path("type").asText())) {
                    held = new TestControlSend(text, onCommitted, onWritten);
                    return held;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

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

        private final class TestControlSend implements WebSocketEndpoint.TrackedSend {
            private final String text;
            private final Runnable onCommitted;
            private final Runnable onWritten;
            private boolean committed;
            private boolean written;
            private boolean cancelled;

            private TestControlSend(String text, Runnable onCommitted, Runnable onWritten) {
                this.text = text;
                this.onCommitted = onCommitted;
                this.onWritten = onWritten;
            }

            @Override
            public boolean accepted() {
                return !cancelled;
            }

            @Override
            public boolean committed() {
                return committed;
            }

            @Override
            public boolean cancelIfNotCommitted() {
                if (committed || cancelled) return false;
                cancelled = true;
                return true;
            }

            @SuppressWarnings("unused")
            private void commitAndWrite() {
                if (cancelled || committed) return;
                committed = true;
                texts.add(text);
                onCommitted.run();
                if (!written) {
                    written = true;
                    onWritten.run();
                }
            }
        }
    }

    private final class ControlledSender implements WebSocketEndpoint.Sender {
        private final List<String> texts = new ArrayList<>();
        private final List<byte[]> binaries = new ArrayList<>();
        private final HoldMode mode;
        private TestSend firstSend;
        private int trackedCount;
        private Integer closeCode;

        private ControlledSender(HoldMode mode) {
            this.mode = mode;
        }


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
        public WebSocketEndpoint.BinarySend sendBinaryTracked(
                byte[] payload,
                Runnable onCommitted,
                Runnable onWritten) {
            trackedCount++;
            if (trackedCount == 1 && mode != HoldMode.NONE) {
                firstSend = new TestSend(payload.clone(), onCommitted, onWritten);
                if (mode == HoldMode.AFTER_COMMIT) firstSend.commit();
                return firstSend;
            }

            binaries.add(payload.clone());
            onCommitted.run();
            onWritten.run();
            return WebSocketEndpoint.BinarySend.alreadyCommitted();
        }

        @Override
        public void close(int code, String reason) {
            closeCode = code;
        }

        private final class TestSend implements WebSocketEndpoint.BinarySend {
            private final byte[] payload;
            private final Runnable onCommitted;
            private final Runnable onWritten;
            private boolean committed;
            private boolean written;
            private boolean cancelled;

            private TestSend(byte[] payload, Runnable onCommitted, Runnable onWritten) {
                this.payload = payload;
                this.onCommitted = onCommitted;
                this.onWritten = onWritten;
            }

            private void commit() {
                if (cancelled || committed) return;
                committed = true;
                binaries.add(payload.clone());
                onCommitted.run();
            }

            private void completeWritten() {
                if (!committed || written) return;
                written = true;
                onWritten.run();
            }

            @Override
            public boolean accepted() {
                return !cancelled;
            }

            @Override
            public boolean committed() {
                return committed;
            }

            @Override
            public boolean cancelIfNotCommitted() {
                if (committed || cancelled) return false;
                cancelled = true;
                return true;
            }
        }
    }

    private enum HoldMode {
        NONE,
        BEFORE_COMMIT,
        AFTER_COMMIT
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

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

        void runLast() {
            Runnable last = tasks.pollLast();
            if (last != null) last.run();
        }

        void runAll() {
            while (!tasks.isEmpty()) runOne();
        }
    }
}
