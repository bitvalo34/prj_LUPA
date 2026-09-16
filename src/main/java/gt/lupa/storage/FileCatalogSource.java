package gt.lupa.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class FileCatalogSource implements CatalogSource {
    private final Path catalogPath,dataRoot;private final int maxJsonBytes;private final CatalogJson json=new CatalogJson();
    public FileCatalogSource(Path catalogPath){this(catalogPath,256*1024);}public FileCatalogSource(Path catalogPath,int maxJsonBytes){if(maxJsonBytes<1)throw new IllegalArgumentException("maxJsonBytes must be positive");this.catalogPath=catalogPath.toAbsolutePath().normalize();Path parent=this.catalogPath.getParent();this.dataRoot=parent==null?Path.of(".").toAbsolutePath().normalize():parent;this.maxJsonBytes=maxJsonBytes;}
    public CatalogSnapshot readCatalog()throws CatalogException{return json.parseCatalog(readRegularFile(catalogPath));}
    public ImageManifest readManifest(String imageId,String imageVersion)throws CatalogException{CatalogValidator.validateId(imageId);CatalogValidator.validateVersion(imageVersion);Path manifest=dataRoot.resolve("pyramids").resolve(imageId).resolve(imageVersion).resolve("manifest.json").normalize();if(!manifest.startsWith(dataRoot))throw new CatalogException("manifest path escaped data root");ImageManifest parsed=json.parseManifest(readRegularFile(manifest));if(!parsed.imageId().equals(imageId)||!parsed.imageVersion().equals(imageVersion))throw new CatalogException("manifest identity does not match requested version");return parsed;}
    private byte[] readRegularFile(Path path)throws CatalogException{try{if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw new CatalogException("JSON data file is absent, non-regular or symbolic link");long size=Files.size(path);if(size<1||size>maxJsonBytes)throw new CatalogException("JSON data file size is invalid");try(InputStream in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(maxJsonBytes+1);if(bytes.length>maxJsonBytes)throw new CatalogException("JSON data file exceeds size limit");return bytes;}}catch(IOException e){throw new CatalogException("cannot read JSON data file",e);}}
}
