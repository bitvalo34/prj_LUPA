package gt.lupa.storage;
import java.util.List;
public record CatalogSnapshot(int schemaVersion,List<CatalogImage> images){}
