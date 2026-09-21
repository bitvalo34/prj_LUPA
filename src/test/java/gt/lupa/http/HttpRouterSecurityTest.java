package gt.lupa.http;

import gt.lupa.storage.ClasspathCatalogSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpRouterSecurityTest {
    private final HttpParser parser =
            new HttpParser();

    private final HttpRouter router =
            new HttpRouter(
                    ClasspathCatalogSource
                            .defaultFixture());

    @Test
    void onlyExplicitPublicSurfaceIsRoutable()
            throws Exception {
        assertStatus(
                200,
                "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertStatus(
                200,
                "GET /api/catalog HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertStatus(
                426,
                "GET /lupa HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertStatus(
                404,
                "GET /data/originals/secret.jpg HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertStatus(
                404,
                "GET /data/staging/job/file.jpg HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertStatus(
                404,
                "GET /logs/server.log HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertStatus(
                404,
                "GET /.git/config HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertStatus(
                404,
                "GET /etc/passwd HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertStatus(
                404,
                "GET /C:/Windows/win.ini HTTP/1.1\r\nHost: localhost\r\n\r\n");
    }

    @Test
    void parserRejectsTraversalAndDoubleDecodingBeforeRouter()
            throws Exception {
        assertParseBad(
                "GET /../secret HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertParseBad(
                "GET /%2e%2e/secret HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertParseBad(
                "GET /%252e%252e/secret HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertParseBad(
                "GET /assets%2fapp.js HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertParseBad(
                "GET /assets%5capp.js HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertParseBad(
                "GET //server/share HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertParseBad(
                "GET /%00secret HTTP/1.1\r\nHost: localhost\r\n\r\n");
    }

    private void assertStatus(
            int status,
            String wire) throws Exception {
        HttpRequest request =
                parser.parse(
                        wire.getBytes(
                                StandardCharsets.ISO_8859_1));

        assertEquals(
                status,
                router.route(request)
                        .status());
    }

    private void assertParseBad(
            String wire) {
        HttpParseException error =
                assertThrows(
                        HttpParseException.class,
                        () -> parser.parse(
                                wire.getBytes(
                                        StandardCharsets.ISO_8859_1)));

        assertEquals(
                400,
                error.statusCode());
    }
}
