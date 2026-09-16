package gt.lupa.http;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HttpParserTest {
    private final HttpParser parser=new HttpParser();
    @Test void parsesGetQueryAndCaseInsensitiveHeaders()throws Exception{HttpRequest r=parse("GET /api/catalog?x=1 HTTP/1.1\r\nhOsT: localhost\r\nX-Test: ok\r\n\r\n");assertEquals("GET",r.method());assertEquals("/api/catalog",r.path());assertEquals("x=1",r.query());assertEquals("localhost",r.firstHeader("host"));}
    @Test void rejectsMissingOrInvalidHost(){assertStatus(400,"GET / HTTP/1.1\r\nX: y\r\n\r\n");assertStatus(400,"GET / HTTP/1.1\r\nHost: bad host\r\n\r\n");assertStatus(400,"GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n");}
    @Test void rejectsUnsupportedVersionAndMalformedHeaders(){assertStatus(505,"GET / HTTP/1.0\r\nHost: localhost\r\n\r\n");assertStatus(400,"GET  / HTTP/1.1\r\nHost: localhost\r\n\r\n");assertStatus(400,"GET / HTTP/1.1\r\n folded: bad\r\nHost: localhost\r\n\r\n");}
    @Test void rejectsBodiesTransferEncodingAndAmbiguousFraming(){assertStatus(400,"GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\n");assertStatus(400,"GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: nope\r\n\r\n");assertStatus(400,"GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n");assertStatus(400,"GET / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n");assertStatus(400,"GET / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n");}
    @Test void rejectsTrailingBytesAfterHeaderBlock(){String raw="GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";HttpParseException ex=assertThrows(HttpParseException.class,()->parser.parse(raw.getBytes(StandardCharsets.ISO_8859_1),5));assertEquals(400,ex.statusCode());}
    private HttpRequest parse(String raw)throws Exception{return parser.parse(raw.getBytes(StandardCharsets.ISO_8859_1),0);}private void assertStatus(int status,String raw){HttpParseException ex=assertThrows(HttpParseException.class,()->parse(raw));assertEquals(status,ex.statusCode());}
}
