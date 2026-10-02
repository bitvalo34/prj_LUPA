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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class LupaSessionFairnessTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void eligibleSessionsAlternateTileTurnsWhenOnlyOneReadCanRun() throws Exception {
        PublishedImageStore store = publishedImage();
        ManualExecutor disk = new ManualExecutor();
        TileReadAdmission reads = new TileReadAdmission(1, 4);
        TransientBufferBudget buffers = new TransientBufferBudget(4 * 1024 * 1024);
        SessionAdmission sessions = new SessionAdmission(2);
        List<String> readOrder = new ArrayList<>();

        LupaSession a = session(
                store,
                reader("A", readOrder, 40_000),
                disk,
                sessions,
                reads,
                buffers);
        LupaSession b = session(
                store,
                reader("B", readOrder, 40_000),
                disk,
                sessions,
                reads,
                buffers);

        Sender senderA = new Sender();
        Sender senderB = new Sender();

        start(a, senderA);
        start(b, senderB);

        assertEquals(1, disk.size(), "A owns the first admitted read; B must wait");

        disk.runNext();
        assertEquals(List.of("A"), readOrder);
        assertEquals(
                1,
                disk.size(),
                "B should receive the transferred turn before A can submit another read");

        disk.runNext();
        assertEquals(List.of("A", "B"), readOrder);
        assertEquals(
                1,
                disk.size(),
                "A should be next after B releases its turn");

        disk.runNext();
        assertEquals(List.of("A", "B", "A"), readOrder);

        assertTrue(a.snapshotForTest().tileTurnsGranted() >= 2);
        assertTrue(b.snapshotForTest().tileTurnsGranted() >= 1);
        assertEquals(1, reads.snapshot().highWatermark());

        a.onClosed(1000, "done");
        b.onClosed(1000, "done");
    }

    @Test
    void clientWithoutReleaseStopsAtCreditWindowWhileOtherClientCompletes()
            throws Exception {
        PublishedImageStore store = publishedImage();
        ManualExecutor disk = new ManualExecutor();
        TileReadAdmission reads = new TileReadAdmission(1, 4);
        TransientBufferBudget buffers = new TransientBufferBudget(4 * 1024 * 1024);
        SessionAdmission sessions = new SessionAdmission(2);

        LupaSession slow = session(
                store,
                reader("slow", new ArrayList<>(), 140_000),
                disk,
                sessions,
                reads,
                buffers);
        LupaSession active = session(
                store,
                reader("active", new ArrayList<>(), 20_000),
                disk,
                sessions,
                reads,
                buffers);

        Sender slowSender = new Sender();
        Sender activeSender = new Sender();

        start(slow, slowSender);
        start(active, activeSender);

        disk.runAll(100);

        LupaSession.SessionSnapshot slowSnapshot = slow.snapshotForTest();
        LupaSession.SessionSnapshot activeSnapshot = active.snapshotForTest();

        assertTrue(slowSnapshot.pendingDeliveries() > 0);
        assertTrue(slowSnapshot.pendingDeliveries() <= 16);
        assertEquals(
                slowSnapshot.negotiatedWindowBytes(),
                slowSnapshot.freeWindowBytes() + slowSnapshot.reservedBytes(),
                "credit invariant must hold while RELEASE is withheld");
        assertFalse(
                slowSender.hasType("DONE"),
                "slow client should stall rather than accumulate unbounded work");

        assertTrue(
                activeSender.hasType("DONE"),
                "healthy client must finish while the other session withholds RELEASE");
        assertNull(activeSender.closeCode);
        assertTrue(activeSnapshot.tileTurnsGranted() > 0);

        assertEquals(
                0,
                reads.snapshot().inFlight(),
                "no tile read should remain running after the deterministic drain");
        assertEquals(
                0,
                reads.snapshot().waiting(),
                "no session should remain queued once the healthy client completes and slow is credit-blocked");
        assertEquals(
                0,
                disk.size(),
                "lack of RELEASE must not keep preparing tiles indefinitely");

        long turnsBefore = slowSnapshot.tileTurnsGranted();
        disk.runAll(10);
        assertEquals(
                turnsBefore,
                slow.snapshotForTest().tileTurnsGranted(),
                "credit-blocked session must not resume reads without new credit");

        slow.onClosed(1000, "done");
        active.onClosed(1000, "done");
    }

    @Test
    void closingCreditBlockedSessionReleasesAdmissionAndReplacementProgresses()
            throws Exception {
        PublishedImageStore store = publishedImage();
        ManualExecutor disk = new ManualExecutor();
        TileReadAdmission reads = new TileReadAdmission(1, 4);
        TransientBufferBudget buffers = new TransientBufferBudget(4 * 1024 * 1024);
        SessionAdmission sessions = new SessionAdmission(2);

        LupaSession slow = session(
                store,
                reader("slow", new ArrayList<>(), 140_000),
                disk,
                sessions,
                reads,
                buffers);
        LupaSession active = session(
                store,
                reader("active", new ArrayList<>(), 20_000),
                disk,
                sessions,
                reads,
                buffers);

        Sender slowSender = new Sender();
        Sender activeSender = new Sender();
        start(slow, slowSender);
        start(active, activeSender);
        disk.runAll(100);

        assertEquals(2, sessions.snapshot().active());
        assertTrue(slow.snapshotForTest().pendingDeliveries() > 0);
        assertFalse(slowSender.hasType("DONE"));
        assertTrue(activeSender.hasType("DONE"));

        LupaSession rejected = session(
                store,
                reader("rejected", new ArrayList<>(), 20_000),
                disk,
                sessions,
                reads,
                buffers);
        Sender rejectedSender = new Sender();
        rejected.onOpen(rejectedSender);
        rejected.onText(
                rejectedSender,
                "{\"type\":\"HELLO\",\"version\":1,"
                        + "\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");

        assertTrue(rejectedSender.hasError("LIMIT_EXCEEDED"));
        assertEquals(1013, rejectedSender.closeCode);
        assertEquals(2, sessions.snapshot().active());

        slow.onClosed(1006, "credit-blocked client disconnected");
        assertEquals(1, sessions.snapshot().active(),
                "disconnect must release the application-session slot");

        LupaSession replacement = session(
                store,
                reader("replacement", new ArrayList<>(), 20_000),
                disk,
                sessions,
                reads,
                buffers);
        Sender replacementSender = new Sender();
        start(replacement, replacementSender);
        disk.runAll(100);

        assertTrue(replacementSender.hasType("WELCOME"));
        assertTrue(replacementSender.hasType("DONE"),
                "a new session must progress after saturated client cleanup");
        assertNull(replacementSender.closeCode);
        assertEquals(2, sessions.snapshot().active());
        assertEquals(0, reads.snapshot().inFlight());
        assertEquals(0, reads.snapshot().waiting());

        replacement.onClosed(1000, "done");
        active.onClosed(1000, "done");
        assertEquals(0, sessions.snapshot().active());
    }

    private LupaSession session(
            PublishedImageStore store,
            TileReader reader,
            Executor disk,
            SessionAdmission sessions,
            TileReadAdmission reads,
            TransientBufferBudget buffers) {
        return new LupaSession(
                store,
                reader,
                Runnable::run,
                disk,
                Runnable::run,
                sessions,
                reads,
                buffers);
    }

    private TileReader reader(
            String name,
            List<String> order,
            int payloadBytes) {
        return (opened, z, x, y) -> {
            order.add(name);
            ImageLevel level = opened.manifest().levels().get(z);
            int tileSize = opened.manifest().tileSize();
            int width = Math.min(tileSize, level.width() - x * tileSize);
            int height = Math.min(tileSize, level.height() - y * tileSize);
            return new TileData(new byte[payloadBytes], width, height);
        };
    }

    private void start(LupaSession session, Sender sender) {
        session.onOpen(sender);
        session.onText(
                sender,
                "{\"type\":\"HELLO\",\"version\":1,"
                        + "\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");
        session.onText(
                sender,
                "{\"type\":\"OPEN\",\"epoch\":1,\"imageId\":\"photo\"}");
        session.onText(
                sender,
                """
                {"type":"VIEW","epoch":2,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """);
    }

    private PublishedImageStore publishedImage() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version = dataRoot.resolve("pyramids/photo/v1");
        Files.createDirectories(version);

        ImageManifest manifest = new ImageManifest(
                1,
                "photo",
                "v1",
                1024,
                768,
                256,
                0,
                "onetile",
                List.of(
                        new ImageLevel(0, 256, 192),
                        new ImageLevel(1, 512, 384),
                        new ImageLevel(2, 1024, 768)));

        Files.write(
                version.resolve("manifest.json"),
                new CatalogJson().writeManifest(manifest));

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
        public void close(int code, String reason) {
            closeCode = code;
        }

        boolean hasType(String type) throws Exception {
            for (String text : texts) {
                JsonNode node = mapper.readTree(text);
                if (type.equals(node.path("type").asText())) {
                    return true;
                }
            }
            return false;
        }

        boolean hasError(String code) throws Exception {
            for (String text : texts) {
                JsonNode node = mapper.readTree(text);
                if ("ERROR".equals(node.path("type").asText())
                        && code.equals(node.path("code").asText())) {
                    return true;
                }
            }
            return false;
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

        void runNext() {
            Runnable task = tasks.poll();
            assertNotNull(task, "expected a queued disk task");
            task.run();
        }

        void runAll(int maxTasks) {
            int count = 0;
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
                count++;
                if (count > maxTasks) {
                    fail("disk work did not quiesce within " + maxTasks + " tasks");
                }
            }
        }
    }
}
