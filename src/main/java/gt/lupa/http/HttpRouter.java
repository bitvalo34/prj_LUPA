package gt.lupa.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class HttpRouter {
    private static final Set<String> FIXED_ROUTES = Set.of("/", "/api/catalog", "/lupa");
    private static final Pattern PUBLIC_ASSET =
            Pattern.compile("^/assets/[A-Za-z0-9._/-]+\\.(?:js|css|svg|png|jpe?g|webp|woff2?)$");

    private final CatalogSource catalogSource;
    private final ObjectMapper mapper;
    private final int maxResourceBytes;

    public HttpRouter(CatalogSource catalogSource) {
        this(catalogSource, 1024 * 1024);
    }

    public HttpRouter(CatalogSource catalogSource, int maxResourceBytes) {
        this.catalogSource = catalogSource;
        this.maxResourceBytes = maxResourceBytes;
        this.mapper = new ObjectMapper();
    }

    public HttpResponse route(HttpRequest request) {
        boolean asset = isPublicAsset(request.path());
        boolean known = FIXED_ROUTES.contains(request.path()) || asset;
        if (!known) return ResponseFactory.error(404, "resource not found");

        boolean supported = request.method().equals("GET") || request.method().equals("HEAD");
        if (!supported) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "text/plain; charset=utf-8");
            headers.put("Allow", "GET, HEAD");
            return new HttpResponse(
                    405,
                    "Method Not Allowed",
                    "method not allowed\n".getBytes(StandardCharsets.UTF_8),
                    headers);
        }

        try {
            if (asset) return resource("/web" + request.path(), contentType(request.path()));
            return switch (request.path()) {
                case "/" -> resource("/web/index.html", "text/html; charset=utf-8");
                case "/api/catalog" -> catalog();
                case "/lupa" -> ResponseFactory.error(426, "WebSocket upgrade required on /lupa");
                default -> ResponseFactory.error(404, "resource not found");
            };
        } catch (CatalogException e) {
            return ResponseFactory.error(503, "catalog unavailable");
        } catch (IOException e) {
            return ResponseFactory.error(500, "resource unavailable");
        }
    }

    private static boolean isPublicAsset(String path) {
        return !path.contains("..") && PUBLIC_ASSET.matcher(path).matches();
    }

    private static String contentType(String path) {
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }

    private HttpResponse catalog() throws CatalogException, IOException {
        byte[] json = mapper.writeValueAsBytes(catalogSource.readCatalog());
        return new HttpResponse(
                200,
                "OK",
                json,
                Map.of(
                        "Content-Type", "application/json; charset=utf-8",
                        "Cache-Control", "no-store"));
    }

    private HttpResponse resource(String path, String contentType) throws IOException {
        byte[] body = ClasspathResources.read(path, maxResourceBytes);
        if (body == null) return ResponseFactory.error(404, "resource not found");
        return new HttpResponse(200, "OK", body, Map.of("Content-Type", contentType));
    }
}
