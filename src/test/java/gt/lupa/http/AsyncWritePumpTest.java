package gt.lupa.http;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AsyncWritePumpTest {
    @Test void drainsBuffersAcrossDeterministicPartialWrites(){ByteArrayOutputStream out=new ByteArrayOutputStream();AtomicBoolean success=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();AsyncWritePump.WriteTarget target=(buffer,handler)->{int n=Math.min(3,buffer.remaining());byte[] piece=new byte[n];buffer.get(piece);out.writeBytes(piece);handler.completed(n,null);};new AsyncWritePump(target,List.of(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)),ByteBuffer.wrap("-world".getBytes(StandardCharsets.UTF_8))),()->success.set(true),failure::set).start();assertTrue(success.get());assertNull(failure.get());assertEquals("hello-world",out.toString(StandardCharsets.UTF_8));}
    @Test void propagatesWriteFailureOnce(){AtomicBoolean success=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();RuntimeException boom=new RuntimeException("boom");AsyncWritePump.WriteTarget target=new AsyncWritePump.WriteTarget(){public void write(ByteBuffer buffer,CompletionHandler<Integer,Void> handler){handler.failed(boom,null);}};new AsyncWritePump(target,List.of(ByteBuffer.wrap(new byte[]{1,2,3})),()->success.set(true),failure::set).start();assertFalse(success.get());assertSame(boom,failure.get());}
}
