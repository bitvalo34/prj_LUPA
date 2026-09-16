package gt.lupa.storage;

import java.io.IOException;
import java.io.InputStream;

public final class ClasspathCatalogSource implements CatalogSource {
    private static final int MAX_JSON_BYTES=256*1024;private final String catalogResource,manifestResource;private final CatalogJson json=new CatalogJson();
    public ClasspathCatalogSource(String catalogResource,String manifestResource){this.catalogResource=catalogResource;this.manifestResource=manifestResource;}public static ClasspathCatalogSource defaultFixture(){return new ClasspathCatalogSource("/fixtures/catalog.json","/fixtures/lupa-small-v1-manifest.json");}
    public CatalogSnapshot readCatalog()throws CatalogException{return json.parseCatalog(read(catalogResource));}
    public ImageManifest readManifest(String imageId,String imageVersion)throws CatalogException{CatalogValidator.validateId(imageId);CatalogValidator.validateVersion(imageVersion);ImageManifest m=json.parseManifest(read(manifestResource));if(!m.imageId().equals(imageId)||!m.imageVersion().equals(imageVersion))throw new CatalogException("fixture manifest does not match requested image/version");return m;}
    private static byte[] read(String resource)throws CatalogException{try(InputStream in=ClasspathCatalogSource.class.getResourceAsStream(resource)){if(in==null)throw new CatalogException("fixture resource not found");byte[] bytes=in.readNBytes(MAX_JSON_BYTES+1);if(bytes.length>MAX_JSON_BYTES)throw new CatalogException("fixture JSON exceeds size limit");return bytes;}catch(IOException e){throw new CatalogException("cannot read fixture resource",e);}}
}
