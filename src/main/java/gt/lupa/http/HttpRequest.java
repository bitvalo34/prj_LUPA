package gt.lupa.http;

import java.util.List;
import java.util.Map;

public record HttpRequest(String method, String rawTarget, String path, String query, String version, Map<String, List<String>> headers) {
    public List<String> headerValues(String lowerCaseName) { return headers.getOrDefault(lowerCaseName.toLowerCase(), List.of()); }
    public String firstHeader(String lowerCaseName) { List<String> values=headerValues(lowerCaseName); return values.isEmpty()?null:values.getFirst(); }
}
