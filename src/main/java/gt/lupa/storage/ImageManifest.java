package gt.lupa.storage;
import java.util.List;
public record ImageManifest(int schemaVersion,String imageId,String imageVersion,int width,int height,int tileSize,int overlap,String depth,List<ImageLevel> levels){}
