package gt.lupa.session;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LupaSessionResourceAccountingTest {
    @TempDir Path temp;

    @Test
    void readPermitReturnsAfterStorageAndEnvelopeBytesStayAccountedUntilWriteCompletes()
            throws Exception {
        PublishedImageStore store = publishedImage();
        TileReader reader =
                (opened, z, x, y) ->
                        new TileData(new byte[1024], 256, 192);

        SessionAdmission sessions = new SessionAdmission(1);
        TileReadAdmission reads = new TileReadAdmission(1, 1);
        TransientBufferBudget buffers =
                new TransientBufferBudget(1024 * 1024);

        LupaSession session =
                new LupaSession(
                        store,
                        reader,
                        Runnable::run,
                        Runnable::run,
                        Runnable::run,
                        sessions,
                        reads,
                        buffers);

        HoldingSender sender = new HoldingSender();
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
                 "rect":{"x":0,"y":0,"width":256,"height":192},
                 "viewportPx":{"width":256,"height":192},
                 "detailOffset":0,"mode":"uniform","focus":null}
                """);

        assertEquals(
                0,
                reads.snapshot().inFlight(),
                "read permit covers storage work, not socket write time");
        assertTrue(
                buffers.snapshot().reservedBytes() > 1024,
                "TILE envelope must stay accounted while write is retained");
        assertEquals(1, buffers.snapshot().activeLeases());

        sender.completeBinaryWrite();

        assertEquals(0, buffers.snapshot().reservedBytes());
        assertEquals(0, buffers.snapshot().activeLeases());

        session.onClosed(1000, "normal");
        assertEquals(0, sessions.snapshot().active());
    }

    private PublishedImageStore publishedImage() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version = dataRoot.resolve("pyramids/photo/v1");
        Files.createDirectories(version);

        ImageManifest manifest = new ImageManifest(
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

    private static final class HoldingSender implements WebSocketEndpoint.Sender {
        private Runnable binaryWritten;

        @Override
        public boolean sendText(String text) {
            return true;
        }

        @Override
        public boolean sendBinary(byte[] payload) {
            throw new AssertionError("tracked binary send expected");
        }

        @Override
        public WebSocketEndpoint.BinarySend sendBinaryTracked(
                byte[] payload,
                Runnable onCommitted,
                Runnable onWritten) {
            assertNull(binaryWritten, "only one binary write may be pending");
            onCommitted.run();
            binaryWritten = onWritten;
            return WebSocketEndpoint.BinarySend.alreadyCommitted();
        }

        @Override
        public void close(int code, String reason) {}

        void completeBinaryWrite() {
            Runnable callback = binaryWritten;
            assertNotNull(callback);
            binaryWritten = null;
            callback.run();
        }
    }
}
