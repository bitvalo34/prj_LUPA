package gt.lupa.http;

public final class HttpParseException extends Exception {
    private final int statusCode;
    public HttpParseException(int statusCode,String message){super(message);this.statusCode=statusCode;}
    public int statusCode(){return statusCode;}
}
