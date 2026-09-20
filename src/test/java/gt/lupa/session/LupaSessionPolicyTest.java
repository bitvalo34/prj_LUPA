package gt.lupa.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.protocol.LupaProtocol;
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

import static org.junit.jupiter.api.Assertions.*;

class LupaSessionPolicyTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unsupportedHelloIsRecoverableButRepeatedHelloCloses() throws Exception {
        LupaSession session = new LupaSession(store(), reader(1024), Runnable::run);
        Sender sender = new Sender();
        session.onOpen(sender);

        session.onText(sender,
                "{\"type\":\"HELLO\",\"version\":2,\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");
        assertEquals("VERSION_UNSUPPORTED", sender.lastJson().path("code").asText());
        assertEquals(LupaSessionState.ESPERA_HELLO, session.snapshotForTest().state());
        assertNull(sender.closeCode);

        hello(session, sender);
        assertEquals(LupaSessionState.LISTA, session.snapshotForTest().state());

        session.onText(sender,
                "{\"type\":\"HELLO\",\"version\":1,\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");
        assertEquals(1008, sender.closeCode);
        assertEquals(LupaSessionState.CERRADA, session.snapshotForTest().state());
    }

    @Test
    void malformedJsonAndUnknownControlCloseBecauseFramingOrSemanticsAreNotRecoverable() {
        LupaSession malformed = new LupaSession(storeUnchecked(), reader(1024), Runnable::run);
        Sender first = new Sender();
        malformed.onOpen(first);
        malformed.onText(first, "{not-json");
        assertEquals(1008, first.closeCode);

        LupaSession unknown = new LupaSession(storeUnchecked(), reader(1024), Runnable::run);
        Sender second = new Sender();
        unknown.onOpen(second);
        hello(unknown, second);
        unknown.onText(second, "{\"type\":\"CANCEL\"}");
        assertEquals(1008, second.closeCode);
    }

    @Test
    void badViewWithHighEpochDoesNotMutateValidSessionOrConsumeEpoch() throws Exception {
        LupaSession session = new LupaSession(store(), reader(1024), Runnable::run);
        Sender sender = new Sender();
        session.onOpen(sender);
        hello(session, sender);
        open(session, sender, 5);

        LupaSession.SessionSnapshot before = session.snapshotForTest();

        session.onText(sender, """
                {"type":"VIEW","epoch":100,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":2048,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """);

        assertEquals("BAD_VIEW", sender.lastJson().path("code").asText());
        LupaSession.SessionSnapshot after = session.snapshotForTest();
        assertEquals(before.currentEpoch(), after.currentEpoch());
        assertEquals(before.openedImageId(), after.openedImageId());
        assertEquals(before.freeWindowBytes(), after.freeWindowBytes());
        assertEquals(before.reservedBytes(), after.reservedBytes());
        assertNull(sender.closeCode);

        session.onText(sender, view(6));
        assertTrue(sender.hasType("DONE"));
        assertEquals(6, session.snapshotForTest().currentEpoch());
    }

    @Test
    void viewBeforeOpenIsSequenceViolationAndDoesNotCreateImageState() {
        LupaSession session = new LupaSession(storeUnchecked(), reader(1024), Runnable::run);
        Sender sender = new Sender();
        session.onOpen(sender);
        hello(session, sender);

        session.onText(sender, view(1));

        assertEquals(1008, sender.closeCode);
        LupaSession.SessionSnapshot snapshot = session.snapshotForTest();
        assertEquals(LupaSessionState.CERRADA, snapshot.state());
        assertNull(snapshot.openedImageId());
        assertEquals(0, snapshot.currentEpoch());
    }

    @Test
    void maxEpochIsAcceptedWithoutOverflowAndRepeatedMaxEpochCreatesNoWork() throws Exception {
        LupaSession session = new LupaSession(store(), reader(1024), Runnable::run);
        Sender sender = new Sender();
        session.onOpen(sender);
        hello(session, sender);
        open(session, sender, LupaProtocol.MAX_EPOCH);

        int texts = sender.texts.size();
        session.onText(sender, """
                {"type":"OPEN","epoch":2147483647,"imageId":"photo"}
                """);
        assertEquals(texts, sender.texts.size());
        assertEquals(LupaProtocol.MAX_EPOCH, session.snapshotForTest().currentEpoch());
        assertNull(sender.closeCode);
    }

    @Test
    void deliveryIdMaxIsUsedOnceThenSessionRequiresReconnect() throws Exception {
        LupaSession session = new LupaSession(
                store(),
                reader(1024),
                Runnable::run,
                Runnable::run,
                Runnable::run,
                LupaProtocol.MAX_EPOCH);
        Sender sender = new Sender();
        session.onOpen(sender);
        hello(session, sender);
        open(session, sender, 1);
        session.onText(sender, view(2));

        assertFalse(sender.binaries.isEmpty());
        assertEquals(LupaProtocol.MAX_EPOCH, deliveryId(sender.binaries.getFirst()));
        assertTrue(sender.hasError("LIMIT_EXCEEDED"));
        assertEquals(1008, sender.closeCode);
        assertEquals(LupaSessionState.CERRADA, session.snapshotForTest().state());
        assertEquals((long) LupaProtocol.MAX_EPOCH + 1L, session.snapshotForTest().nextDeliveryId());
    }

    @Test
    void deterministicCreditSequencePreservesInvariantAtEveryTransition() throws Exception {
        LupaSession session = new LupaSession(store(), reader(140_000), Runnable::run);
        Sender sender = new Sender();
        session.onOpen(sender);
        hello(session, sender);
        assertCreditInvariant(session);

        open(session, sender, 1);
        assertCreditInvariant(session);

        session.onText(sender, view(2));
        assertCreditInvariant(session);
        assertEquals(3, session.snapshotForTest().pendingDeliveries());

        int d1 = deliveryId(sender.binaries.get(0));
        int d2 = deliveryId(sender.binaries.get(1));

        release(session, sender, d1, "displayed");
        assertCreditInvariant(session);
        assertEquals(3, session.snapshotForTest().pendingDeliveries());

        session.onText(sender, view(3));
        assertCreditInvariant(session);

        release(session, sender, d2, "discarded");
        assertCreditInvariant(session);

        release(session, sender, d1, "failed");
        assertCreditInvariant(session);

        release(session, sender, 2_000_000_000, "discarded");
        assertCreditInvariant(session);

        while (!sender.binaries.isEmpty() && session.snapshotForTest().pendingDeliveries() > 0) {
            List<Integer> pending = new ArrayList<>();
            for (byte[] binary : sender.binaries) {
                int id = deliveryId(binary);
                if (id >= 1) pending.add(id);
            }
            int before = session.snapshotForTest().pendingDeliveries();
            for (int id : pending) release(session, sender, id, "discarded");
            assertCreditInvariant(session);
            if (session.snapshotForTest().pendingDeliveries() == before) break;
        }
        assertCreditInvariant(session);
        assertNull(sender.closeCode);
    }

    private static void assertCreditInvariant(LupaSession session) {
        LupaSession.SessionSnapshot snapshot = session.snapshotForTest();
        if (snapshot.negotiatedWindowBytes() == 0) return;
        assertEquals(
                snapshot.negotiatedWindowBytes(),
                snapshot.freeWindowBytes() + snapshot.reservedBytes());
        assertTrue(snapshot.freeWindowBytes() >= 0);
        assertTrue(snapshot.pendingDeliveries() <= LupaProtocol.MAX_IN_FLIGHT);
    }

    private void hello(LupaSession session, Sender sender) {
        session.onText(sender,
                "{\"type\":\"HELLO\",\"version\":1,\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");
    }

    private void open(LupaSession session, Sender sender, int epoch) {
        session.onText(sender,
                "{\"type\":\"OPEN\",\"epoch\":" + epoch + ",\"imageId\":\"photo\"}");
    }

    private void release(LupaSession session, Sender sender, int id, String status) {
        session.onText(sender,
                "{\"type\":\"RELEASE\",\"deliveryId\":" + id
                        + ",\"status\":\"" + status + "\"}");
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
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        int h = buffer.getInt();
        byte[] header = new byte[h];
        buffer.get(header);
        return mapper.readTree(header).path("deliveryId").asInt();
    }

    private TileReader reader(int bytes) {
        return (opened, z, x, y) -> {
            ImageLevel level = opened.manifest().levels().get(z);
            int w = Math.min(256, level.width() - x * 256);
            int h = Math.min(256, level.height() - y * 256);
            return new TileData(new byte[bytes], w, h);
        };
    }

    private PublishedImageStore storeUnchecked() {
        try {
            return store();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PublishedImageStore store() throws Exception {
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

        JsonNode lastJson() throws Exception {
            assertFalse(texts.isEmpty());
            return mapper.readTree(texts.getLast());
        }

        boolean hasType(String type) throws Exception {
            for (String text : texts) {
                if (type.equals(mapper.readTree(text).path("type").asText())) return true;
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
