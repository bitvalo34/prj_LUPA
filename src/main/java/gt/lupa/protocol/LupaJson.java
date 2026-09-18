package gt.lupa.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public final class LupaJson {
    public static final int MAX_NESTING_DEPTH = 8;
    public static final int MAX_STRING_CHARS = 256;
    public static final int MAX_OBJECT_FIELDS = 32;
    public static final int MAX_ARRAY_ITEMS = 64;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public ObjectNode parseControl(String text) throws LupaControlException {
        final JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new LupaControlException("malformed JSON control", e);
        }
        if (!(root instanceof ObjectNode object)) {
            throw new LupaControlException("LUPA control must be a JSON object");
        }
        validateTree(object, 1);
        return object;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public static void requireOnly(ObjectNode object, String... allowed) throws LupaControlException {
        Set<String> names = new HashSet<>(Set.of(allowed));
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!names.contains(field)) {
                throw new LupaControlException("unexpected field: " + field);
            }
        }
        for (String field : allowed) {
            if (!object.has(field)) throw new LupaControlException("missing required field: " + field);
        }
    }

    public static String requireText(ObjectNode object, String field) throws LupaControlException {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual()) throw new LupaControlException(field + " must be a string");
        String value = node.textValue();
        if (value.isEmpty() || value.length() > MAX_STRING_CHARS) {
            throw new LupaControlException(field + " has invalid length");
        }
        return value;
    }

    public static int requireInt(ObjectNode object, String field, int min, int max) throws LupaControlException {
        JsonNode node = object.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new LupaControlException(field + " must be an integer");
        }
        int value = node.intValue();
        if (value < min || value > max) throw new LupaControlException(field + " is outside its allowed range");
        return value;
    }

    public static long requireLong(ObjectNode object, String field, long min, long max) throws LupaControlException {
        JsonNode node = object.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new LupaControlException(field + " must be an integer");
        }
        long value = node.longValue();
        if (value < min || value > max) throw new LupaControlException(field + " is outside its allowed range");
        return value;
    }

    public static ObjectNode requireObject(ObjectNode object, String field) throws LupaControlException {
        JsonNode node = object.get(field);
        if (!(node instanceof ObjectNode child)) throw new LupaControlException(field + " must be an object");
        return child;
    }

    public static void requireNull(ObjectNode object, String field) throws LupaControlException {
        JsonNode node = object.get(field);
        if (node == null || !node.isNull()) throw new LupaControlException(field + " must be null");
    }

    private static void validateTree(JsonNode node, int depth) throws LupaControlException {
        if (depth > MAX_NESTING_DEPTH) throw new LupaControlException("JSON nesting is too deep");
        if (node.isTextual() && node.textValue().length() > MAX_STRING_CHARS) {
            throw new LupaControlException("JSON string exceeds configured limit");
        }
        if (node.isObject()) {
            if (node.size() > MAX_OBJECT_FIELDS) throw new LupaControlException("JSON object has too many fields");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().length() > 64) throw new LupaControlException("JSON field name is too long");
                validateTree(field.getValue(), depth + 1);
            }
        } else if (node.isArray()) {
            if (node.size() > MAX_ARRAY_ITEMS) throw new LupaControlException("JSON array has too many items");
            for (JsonNode child : node) validateTree(child, depth + 1);
        } else if (node.isFloatingPointNumber()) {
            double value = node.doubleValue();
            if (!Double.isFinite(value)) throw new LupaControlException("JSON number must be finite");
        }
    }
}
