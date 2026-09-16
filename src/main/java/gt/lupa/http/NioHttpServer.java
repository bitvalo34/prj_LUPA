package gt.lupa.http;

import gt.lupa.config.ServerConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class NioHttpServer implements AutoCloseable {
    private final ServerConfig config;private final HttpRouter router;private final HttpParser parser=new HttpParser();private final Semaphore connectionSlots;private final Set<Connection> connections=ConcurrentHashMap.newKeySet();private final ThreadPoolExecutor workers;private final ScheduledExecutorService timers;private final AtomicBoolean running=new AtomicBoolean();private AsynchronousChannelGroup ioGroup;private AsynchronousServerSocketChannel server;
    public NioHttpServer(ServerConfig config,HttpRouter router){this.config=config;this.router=router;this.connectionSlots=new Semaphore(config.maxConnections());this.workers=new ThreadPoolExecutor(config.workerThreads(),config.workerThreads(),0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(config.workerQueueCapacity()),namedFactory("lupa-worker-"),new ThreadPoolExecutor.AbortPolicy());this.timers=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(namedFactory("lupa-timer-"));}
    public synchronized void start()throws IOException{if(!running.compareAndSet(false,true))throw new IllegalStateException("server already started");try{ioGroup=AsynchronousChannelGroup.withFixedThreadPool(config.ioThreads(),namedFactory("lupa-io-"));server=AsynchronousServerSocketChannel.open(ioGroup);server.setOption(StandardSocketOptions.SO_REUSEADDR,true);server.bind(new InetSocketAddress(config.host(),config.port()),config.maxConnections());acceptNext();}catch(IOException|RuntimeException e){running.set(false);close();throw e;}}
    public int port(){try{if(server==null)throw new IllegalStateException("server not started");return((InetSocketAddress)server.getLocalAddress()).getPort();}catch(IOException e){throw new IllegalStateException("cannot obtain local address",e);}}
    private void acceptNext(){if(!running.get())return;server.accept(null,new CompletionHandler<>(){public void completed(AsynchronousSocketChannel socket,Void a){if(running.get())acceptNext();if(!running.get()){closeQuietly(socket);return;}if(!connectionSlots.tryAcquire()){closeQuietly(socket);return;}try{socket.setOption(StandardSocketOptions.TCP_NODELAY,true);Connection c=new Connection(socket);connections.add(c);c.start();}catch(IOException|RuntimeException e){connectionSlots.release();closeQuietly(socket);}}public void failed(Throwable exc,Void a){if(running.get())acceptNext();}});}
    public synchronized void close(){running.set(false);if(server!=null)closeQuietly(server);for(Connection c:connections.toArray(Connection[]::new))c.finish();workers.shutdownNow();timers.shutdownNow();if(ioGroup!=null)try{ioGroup.shutdownNow();}catch(IOException ignored){}}
    private final class Connection{private final AsynchronousSocketChannel socket;private final HeaderAccumulator headers=new HeaderAccumulator(config.maxHeaderBytes());private final ByteBuffer readBuffer=ByteBuffer.allocate(4096);private final AtomicBoolean finished=new AtomicBoolean(),responseStarted=new AtomicBoolean();private ScheduledFuture<?> timeout;Connection(AsynchronousSocketChannel socket){this.socket=socket;}void start(){Duration d=config.headerTimeout();timeout=timers.schedule(()->respondOnce(ResponseFactory.error(408,"request headers were not completed in time"),false),d.toMillis(),TimeUnit.MILLISECONDS);readNext();}
        void readNext(){if(finished.get()||responseStarted.get())return;socket.read(readBuffer,null,new CompletionHandler<>(){public void completed(Integer read,Void a){if(finished.get()||responseStarted.get())return;if(read==null||read<0){if(headers.size()==0)finish();else respondOnce(ResponseFactory.error(400,"connection closed before headers completed"),false);return;}if(read==0){readNext();return;}readBuffer.flip();HeaderAccumulator.AppendResult result=headers.append(readBuffer);readBuffer.clear();if(result==HeaderAccumulator.AppendResult.TOO_LARGE)respondOnce(ResponseFactory.error(431,"request headers exceed configured limit"),false);else if(result==HeaderAccumulator.AppendResult.COMPLETE)processHeaders();else readNext();}public void failed(Throwable exc,Void a){finish();}});}
        void processHeaders(){cancelTimeout();final HttpRequest request;try{request=parser.parse(headers.headerBytes(),headers.trailingBytes());}catch(HttpParseException e){respondOnce(ResponseFactory.error(e.statusCode(),e.getMessage()),false);return;}try{workers.execute(()->{if(finished.get())return;HttpResponse response;try{response=router.route(request);}catch(RuntimeException e){response=ResponseFactory.error(500,"internal server error");}respondOnce(response,request.method().equals("HEAD"));});}catch(RejectedExecutionException e){respondOnce(ResponseFactory.error(503,"server work queue is full"),request.method().equals("HEAD"));}}
        void respondOnce(HttpResponse response,boolean headOnly){if(finished.get()||!responseStarted.compareAndSet(false,true))return;cancelTimeout();new AsyncWritePump((buffer,handler)->socket.write(buffer,null,handler),response.encode(headOnly),this::finish,ignored->finish()).start();}
        void cancelTimeout(){ScheduledFuture<?> t=timeout;if(t!=null)t.cancel(false);}void finish(){if(!finished.compareAndSet(false,true))return;cancelTimeout();closeQuietly(socket);connections.remove(this);connectionSlots.release();}}
    private static ThreadFactory namedFactory(String prefix){AtomicInteger seq=new AtomicInteger();return runnable->{Thread t=new Thread(runnable,prefix+seq.incrementAndGet());t.setDaemon(true);return t;};}
    private static void closeQuietly(AutoCloseable c){try{c.close();}catch(Exception ignored){}}
}
