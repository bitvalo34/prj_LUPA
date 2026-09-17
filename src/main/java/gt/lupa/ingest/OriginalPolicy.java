package gt.lupa.ingest;

import java.util.Locale;

public enum OriginalPolicy {
    COPY,
    REFERENCE;

    public static OriginalPolicy parse(String value) throws IngestException {
        if (value == null || value.isBlank()) {
            throw new IngestException(2, "original-policy must not be blank");
        }
        try {
            return OriginalPolicy.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IngestException(2, "original-policy must be copy or reference", e);
        }
    }

    public String cliValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
