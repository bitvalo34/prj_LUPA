package gt.lupa.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

final class CatalogJson {
    private final ObjectMapper mapper=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    CatalogSnapshot parseCatalog(byte[] bytes)throws CatalogException{try{return CatalogValidator.validate(mapper.readValue(bytes,CatalogSnapshot.class));}catch(IOException e){throw new CatalogException("invalid catalog JSON",e);}}
    ImageManifest parseManifest(byte[] bytes)throws CatalogException{try{return CatalogValidator.validateManifest(mapper.readValue(bytes,ImageManifest.class));}catch(IOException e){throw new CatalogException("invalid manifest JSON",e);}}
}
