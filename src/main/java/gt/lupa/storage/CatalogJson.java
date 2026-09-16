package gt.lupa.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public final class CatalogJson {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public CatalogSnapshot parseCatalog(byte[] bytes) throws CatalogException {
        try {
            return CatalogValidator.validate(mapper.readValue(bytes, CatalogSnapshot.class));
        } catch (IOException e) {
            throw new CatalogException("invalid catalog JSON", e);
        }
    }

    public ImageManifest parseManifest(byte[] bytes) throws CatalogException {
        try {
            return CatalogValidator.validateManifest(mapper.readValue(bytes, ImageManifest.class));
        } catch (IOException e) {
            throw new CatalogException("invalid manifest JSON", e);
        }
    }

    public byte[] writeCatalog(CatalogSnapshot catalog) throws CatalogException {
        CatalogSnapshot validated = CatalogValidator.validate(catalog);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(validated);
        } catch (JsonProcessingException e) {
            throw new CatalogException("cannot serialize catalog JSON", e);
        }
    }

    public byte[] writeManifest(ImageManifest manifest) throws CatalogException {
        ImageManifest validated = CatalogValidator.validateManifest(manifest);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(validated);
        } catch (JsonProcessingException e) {
            throw new CatalogException("cannot serialize manifest JSON", e);
        }
    }
}
