package gt.lupa.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CatalogSourceTest {
    @TempDir Path temp;
    @Test void fixtureMatchesContract()throws Exception{ClasspathCatalogSource source=ClasspathCatalogSource.defaultFixture();CatalogSnapshot c=source.readCatalog();assertEquals(1,c.schemaVersion());assertEquals(1,c.images().size());CatalogImage i=c.images().getFirst();assertEquals("lupa-small",i.imageId());assertEquals("v1",i.imageVersion());assertEquals(256,i.tileSize());assertEquals(4,i.maxLevel());ImageManifest m=source.readManifest("lupa-small","v1");assertEquals(i.width(),m.width());assertEquals(i.height(),m.height());assertEquals(i.maxLevel(),m.levels().getLast().z());}
    @Test void fileSourceRejectsMissingCorruptAndSymlinkCatalog()throws Exception{Path catalog=temp.resolve("catalog.json");FileCatalogSource source=new FileCatalogSource(catalog);assertThrows(CatalogException.class,source::readCatalog);Files.writeString(catalog,"{bad json}");assertThrows(CatalogException.class,source::readCatalog);Path target=temp.resolve("real.json");Files.writeString(target,"{\"schemaVersion\":1,\"images\":[]}");Path link=temp.resolve("link.json");try{Files.createSymbolicLink(link,target.getFileName());assertThrows(CatalogException.class,new FileCatalogSource(link)::readCatalog);}catch(UnsupportedOperationException|java.nio.file.FileSystemException ignored){}}
}
