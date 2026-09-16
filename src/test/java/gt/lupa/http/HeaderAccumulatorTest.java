package gt.lupa.http;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HeaderAccumulatorTest {
    @Test void completesWhenTerminatorIsSplitAcrossReads(){HeaderAccumulator a=new HeaderAccumulator(1024);assertEquals(HeaderAccumulator.AppendResult.INCOMPLETE,append(a,"GET / HTTP/1.1\r\nHost: x\r\n\r"));assertEquals(HeaderAccumulator.AppendResult.COMPLETE,append(a,"\n"));assertTrue(a.complete());assertEquals(0,a.trailingBytes());}
    @Test void detectsExtraBytesAndHeaderLimit(){HeaderAccumulator a=new HeaderAccumulator(1024);assertEquals(HeaderAccumulator.AppendResult.COMPLETE,append(a,"GET / HTTP/1.1\r\nHost: x\r\n\r\nNEXT"));assertEquals(4,a.trailingBytes());HeaderAccumulator b=new HeaderAccumulator(1024);String huge="GET / HTTP/1.1\r\nHost: x\r\nX: "+"a".repeat(1100);assertEquals(HeaderAccumulator.AppendResult.TOO_LARGE,append(b,huge));}
    private HeaderAccumulator.AppendResult append(HeaderAccumulator a,String text){return a.append(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));}
}
