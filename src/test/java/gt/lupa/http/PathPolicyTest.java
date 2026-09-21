package gt.lupa.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathPolicyTest {
    @Test
    void acceptsNormalPublicPaths() throws Exception {
        assertEquals(
                "/",
                PathPolicy.decodeAndValidate("/"));
        assertEquals(
                "/assets/app.css",
                PathPolicy.decodeAndValidate(
                        "/assets/app.css"));
    }

    @Test
    void rejectsTraversalSeparatorsNullsBadUtf8AndDoubleEncoding() {
        assertBad("/../secret");
        assertBad("/./secret");
        assertBad("/%2e%2e/secret");
        assertBad("/%2E%2E/secret");
        assertBad("/%252e%252e/secret");
        assertBad("/assets%2fsecret");
        assertBad("/assets%2Fsecret");
        assertBad("/assets%5csecret");
        assertBad("/assets\\secret");
        assertBad("/%00secret");
        assertBad("/%ZZ");
        assertBad("/%C3%28");
        assertBad("//server/share");
    }

    private void assertBad(String path) {
        HttpParseException ex =
                assertThrows(
                        HttpParseException.class,
                        () -> PathPolicy
                                .decodeAndValidate(path));
        assertEquals(400, ex.statusCode());
    }
}
