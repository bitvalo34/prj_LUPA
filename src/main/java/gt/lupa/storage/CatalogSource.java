package gt.lupa.storage;
public interface CatalogSource { CatalogSnapshot readCatalog() throws CatalogException; ImageManifest readManifest(String imageId,String imageVersion) throws CatalogException; }
