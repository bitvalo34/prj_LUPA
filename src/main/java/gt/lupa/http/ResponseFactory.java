package gt.lupa.http;

public final class ResponseFactory {
    private ResponseFactory(){}
    public static HttpResponse error(int status,String message){return HttpResponse.text(status,reason(status),message+"\n");}
    public static String reason(int status){return switch(status){case 200->"OK";case 400->"Bad Request";case 404->"Not Found";case 405->"Method Not Allowed";case 408->"Request Timeout";case 426->"Upgrade Required";case 431->"Request Header Fields Too Large";case 500->"Internal Server Error";case 501->"Not Implemented";case 503->"Service Unavailable";case 505->"HTTP Version Not Supported";default->"Error";};}
}
