package gt.lupa.http;

import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AsyncWritePump {
    private final WriteTarget target;private final Deque<ByteBuffer> queue=new ArrayDeque<>();private final Runnable onSuccess;private final Consumer<Throwable> onFailure;private final AtomicBoolean done=new AtomicBoolean();
    public AsyncWritePump(WriteTarget target,Collection<ByteBuffer> buffers,Runnable onSuccess,Consumer<Throwable> onFailure){this.target=Objects.requireNonNull(target);for(ByteBuffer buffer:buffers)queue.add(buffer.duplicate());this.onSuccess=Objects.requireNonNull(onSuccess);this.onFailure=Objects.requireNonNull(onFailure);}
    public void start(){writeNext();}
    private void writeNext(){while(!queue.isEmpty()&&!queue.peek().hasRemaining())queue.remove();if(queue.isEmpty()){if(done.compareAndSet(false,true))onSuccess.run();return;}ByteBuffer current=queue.peek();target.write(current,new CompletionHandler<>(){public void completed(Integer written,Void attachment){if(written==null||written<0){failed(new IllegalStateException("socket write returned "+written),attachment);return;}if(written==0&&current.hasRemaining()){target.write(current,this);return;}writeNext();}public void failed(Throwable exc,Void attachment){if(done.compareAndSet(false,true))onFailure.accept(exc);}});}
    @FunctionalInterface public interface WriteTarget{void write(ByteBuffer buffer,CompletionHandler<Integer,Void> handler);}
}
