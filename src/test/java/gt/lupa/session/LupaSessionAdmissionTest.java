package gt.lupa.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.storage.PublishedImageStore;
import gt.lupa.storage.TileReader;
import gt.lupa.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LupaSessionAdmissionTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validHelloConsumesGlobalSlotAndCloseAllowsNewSession() throws Exception {
        SessionAdmission admission = new SessionAdmission(1);
        PublishedImageStore store = new PublishedImageStore(temp.resolve("data"));
        TileReader unused = (opened, z, x, y) -> {
            throw new AssertionError("HELLO admission must not read a tile");
        };

        LupaSession first = new LupaSession(
                store, unused, Runnable::run, Runnable::run, Runnable::run, admission);
        Sender a = new Sender();
        first.onOpen(a);
        hello(first, a);
        assertTrue(a.hasType("WELCOME"));
        assertEquals(1, admission.snapshot().active());

        LupaSession rejected = new LupaSession(
                store, unused, Runnable::run, Runnable::run, Runnable::run, admission);
        Sender b = new Sender();
        rejected.onOpen(b);
        hello(rejected, b);
        assertTrue(b.hasError("LIMIT_EXCEEDED"));
        assertEquals(1013, b.closeCode);
        assertEquals(1, admission.snapshot().active());

        first.onClosed(1000, "normal");
        assertEquals(0, admission.snapshot().active());

        LupaSession replacement = new LupaSession(
                store, unused, Runnable::run, Runnable::run, Runnable::run, admission);
        Sender c = new Sender();
        replacement.onOpen(c);
        hello(replacement, c);
        assertTrue(c.hasType("WELCOME"));
        assertEquals(1, admission.snapshot().active());

        replacement.onClosed(1000, "normal");
        assertEquals(0, admission.snapshot().active());
    }

    private static void hello(LupaSession session, Sender sender) {
        session.onText(
                sender,
                "{\"type\":\"HELLO\",\"version\":1,"
                        + "\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");
    }

    private final class Sender implements WebSocketEndpoint.Sender {
        private final List<String> texts = new ArrayList<>();
        private Integer closeCode;

        @Override
        public boolean sendText(String text) {
            texts.add(text);
            return true;
        }

        @Override
        public boolean sendBinary(byte[] payload) {
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
                        && code.equals(node.path("code").asText())) {
                    return true;
                }
            }
            return false;
        }
    }
}
