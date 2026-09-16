package gt.lupa.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpResponse {
    private final int status;private final String reason;private final byte[] body;private final Map<String,String> headers;
    public HttpResponse(int status,String reason,byte[] body,Map<String,String> headers){this.status=status;this.reason=reason;this.body=body==null?new byte[0]:body.clone();this.headers=new LinkedHashMap<>(headers);}public int status(){return status;}public byte[] body(){return body.clone();}
    public List<ByteBuffer> encode(boolean headOnly){StringBuilder h=new StringBuilder();h.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");headers.forEach((name,value)->h.append(name).append(": ").append(value).append("\r\n"));h.append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n");List<ByteBuffer> buffers=new ArrayList<>(2);buffers.add(ByteBuffer.wrap(h.toString().getBytes(StandardCharsets.ISO_8859_1)));if(!headOnly&&body.length>0)buffers.add(ByteBuffer.wrap(body));return buffers;}
    public static HttpResponse text(int status,String reason,String text){return new HttpResponse(status,reason,text.getBytes(StandardCharsets.UTF_8),Map.of("Content-Type","text/plain; charset=utf-8"));}
}
