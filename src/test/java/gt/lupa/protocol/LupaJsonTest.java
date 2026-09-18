package gt.lupa.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LupaJsonTest {
    private final LupaJson json = new LupaJson();

    @Test
    void rejectsDuplicateFieldsTrailingTokensAndNonObjectRoot() {
        assertThrows(
                LupaControlException.class,
                () -> json.parseControl("{\"type\":\"HELLO\",\"type\":\"OPEN\"}"));

        assertThrows(
                LupaControlException.class,
                () -> json.parseControl("{\"type\":\"HELLO\"} {\"type\":\"OPEN\"}"));

        assertThrows(
                LupaControlException.class,
                () -> json.parseControl("[{\"type\":\"HELLO\"}]"));
    }

    @Test
    void rejectsExcessiveDepthAndLongStrings() {
        String deep = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":{\"h\":{\"i\":1}}}}}}}}}";
        assertThrows(LupaControlException.class, () -> json.parseControl(deep));

        String longValue = "x".repeat(LupaJson.MAX_STRING_CHARS + 1);
        assertThrows(
                LupaControlException.class,
                () -> json.parseControl("{\"type\":\"" + longValue + "\"}"));
    }
}
