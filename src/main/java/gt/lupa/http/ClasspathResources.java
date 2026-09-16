package gt.lupa.http;

import java.io.IOException;
import java.io.InputStream;

public final class ClasspathResources {
    private ClasspathResources(){}
    public static byte[] read(String resource,int maxBytes)throws IOException{try(InputStream in=ClasspathResources.class.getResourceAsStream(resource)){if(in==null)return null;byte[] data=in.readNBytes(maxBytes+1);if(data.length>maxBytes)throw new IOException("resource exceeds configured limit");return data;}}
}
