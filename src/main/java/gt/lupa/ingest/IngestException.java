package gt.lupa.ingest;

public final class IngestException extends Exception {
    private final int exitCode;

    public IngestException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public IngestException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
