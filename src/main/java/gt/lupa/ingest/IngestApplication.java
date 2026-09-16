package gt.lupa.ingest;

public final class IngestApplication {
    private IngestApplication() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) {
        if (args.length == 0 || !"preflight".equals(args[0])) {
            printUsage();
            return 2;
        }
        String[] optionArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
        try {
            IngestCliConfig config = IngestCliConfig.parse(optionArgs);
            PreflightService service = new PreflightService(new ProcessRunner(64 * 1024));
            PreflightReport report = service.check(config);
            System.out.println("LUPA A19 preflight OK");
            System.out.println("original=" + report.original());
            System.out.println("originalBytes=" + report.originalBytes());
            System.out.println("formatExtension=" + report.extension());
            System.out.println("dataRoot=" + report.dataRoot());
            System.out.println("usableBytes=" + report.usableBytes());
            System.out.println("libvips=" + report.vipsVersion());
            if (report.vipsOutputTruncated()) {
                System.out.println("warning=libvips version output was truncated");
            }
            return 0;
        } catch (IngestException e) {
            System.err.println("A19 error: " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("A19 unexpected error: " + e.getMessage());
            return 1;
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java -cp target/lupa.jar gt.lupa.ingest.IngestApplication preflight \\");
        System.err.println("    --original=/private/path/photo.jpg \\");
        System.err.println("    --image-id=sample-photo \\");
        System.err.println("    --display-name=\"Sample photo\" \\");
        System.err.println("    --license-ref=\"Own photograph; permission confirmed by Erwin\" \\");
        System.err.println("    [--data-root=data] [--vips=vips] [--vipsheader=vipsheader] \\");
        System.err.println("    [--timeout-seconds=600] [--jpeg-quality=85]");
    }
}
