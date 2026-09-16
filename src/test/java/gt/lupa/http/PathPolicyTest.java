package gt.lupa.http;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathPolicyTest {
    @Test void acceptsNormalPublicPaths()throws Exception{assertEquals("/",PathPolicy.decodeAndValidate("/"));assertEquals("/assets/app.css",PathPolicy.decodeAndValidate("/assets/app.css"));}
    @Test void rejectsTraversalEncodedSeparatorsBackslashesAndBadEncoding(){assertBad("/../secret");assertBad("/%2e%2e/secret");assertBad("/assets%2fsecret");assertBad("/assets\\secret");assertBad("/%ZZ");assertBad("/%C3%28");}
    private void assertBad(String path){HttpParseException ex=assertThrows(HttpParseException.class,()->PathPolicy.decodeAndValidate(path));assertEquals(400,ex.statusCode());}
}
