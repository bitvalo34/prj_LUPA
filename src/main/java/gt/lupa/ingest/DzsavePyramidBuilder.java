package gt.lupa.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class DzsavePyramidBuilder {
    private static final Pattern TILE_NAME = Pattern.compile("^(\\d+)_(\\d+)\\.jpg$");
    private final ProcessRunner processRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DzsavePyramidBuilder(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    public StagingResult build(
            IngestCliConfig config,
            String imageId,
            String imageVersion,
            Path jobDirectory,
            NormalizedImage normalizedImage,
            PyramidPlan plan) throws IngestException {

        Path workDir = jobDirectory.resolve("work");
        Path dzDir = workDir.resolve("dz");
        Path dzBase = dzDir.resolve("pyramid");
        Path filesDir = dzDir.resolve("pyramid_files");

        Path stagedVersionPath = jobDirectory
                .resolve("publish")
                .resolve(imageId)
                .resolve(imageVersion);

        try {
            Files.createDirectories(dzDir);
            Files.createDirectories(stagedVersionPath.resolve("tiles"));
        } catch (IOException e) {
            throw new IngestException(4, "cannot create dzsave staging directories", e);
        }

        ProcessResult result = processRunner.run(
                List.of(
                        config.vipsExecutable(),
                        "dzsave",
                        normalizedImage.file().toString(),
                        dzBase.toString(),
                        "--layout=dz",
                        "--suffix=.jpg[Q=" + config.jpegQuality() + "]",
                        "--tile-size=" + PyramidMath.TILE_SIZE,
                        "--overlap=0",
                        "--depth=onetile",
                        "--container=fs"
                ),
                config.processTimeout(),
                config.dataRoot()
        );

        if (result.timedOut()) {
            throw new IngestException(3, "dzsave timed out");
        }
        if (result.exitCode() != 0) {
            throw new IngestException(3, "dzsave failed: " + diagnostic(result));
        }
        if (!Files.isDirectory(filesDir)) {
            throw new IngestException(3, "dzsave did not produce the expected pyramid_files directory");
        }

        List<Path> sourceLevelDirs = listNumericDirectories(filesDir);
        if (sourceLevelDirs.size() != plan.levels().size()) {
            throw new IngestException(
                    3,
                    "dzsave produced " + sourceLevelDirs.size() + " level directories but LUPA expected " + plan.levels().size()
            );
        }

        for (int i = 0; i < plan.levels().size(); i++) {
            PyramidLevel level = plan.levels().get(i);
            Path sourceLevelDir = sourceLevelDirs.get(i);
            Path targetLevelDir = stagedVersionPath.resolve("tiles").resolve(Integer.toString(level.z()));
            copyAndValidateLevel(sourceLevelDir, targetLevelDir, level);
        }

        writeManifest(stagedVersionPath.resolve("manifest.json"), imageId, imageVersion, plan);

        return new StagingResult(
                jobDirectory,
                stagedVersionPath,
                imageVersion,
                plan.width(),
                plan.height(),
                plan.maxLevel(),
                plan.levels().size()
        );
    }

    private void copyAndValidateLevel(Path sourceLevelDir, Path targetLevelDir, PyramidLevel level)
            throws IngestException {
        try {
            Files.createDirectories(targetLevelDir);
        } catch (IOException e) {
            throw new IngestException(4, "cannot create target level directory " + targetLevelDir, e);
        }

        int expectedCount = Math.multiplyExact(level.columns(), level.rows());
        boolean[] seen = new boolean[expectedCount];
        int actualCount = 0;

        try (Stream<Path> stream = Files.list(sourceLevelDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            for (Path sourceTile : files) {
                String name = sourceTile.getFileName().toString();
                Matcher matcher = TILE_NAME.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }

                int x = Integer.parseInt(matcher.group(1));
                int y = Integer.parseInt(matcher.group(2));

                if (x < 0 || x >= level.columns() || y < 0 || y >= level.rows()) {
                    throw new IngestException(3, "tile coordinate out of range at level " + level.z() + ": " + name);
                }

                int index = y * level.columns() + x;
                if (seen[index]) {
                    throw new IngestException(3, "duplicate tile coordinate at level " + level.z() + ": " + name);
                }
                seen[index] = true;

                moveOrCopy(sourceTile, targetLevelDir.resolve(name));
                actualCount++;
            }
        } catch (IOException e) {
            throw new IngestException(4, "cannot inspect generated tiles in " + sourceLevelDir, e);
        }

        if (actualCount != expectedCount) {
            throw new IngestException(
                    3,
                    "level " + level.z() + " expected " + expectedCount + " tiles but found " + actualCount
            );
        }

        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) {
                int x = i % level.columns();
                int y = i / level.columns();
                throw new IngestException(3, "missing tile at level " + level.z() + " coordinate " + x + "_" + y);
            }
        }
    }

    private List<Path> listNumericDirectories(Path root) throws IngestException {
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> directories = new ArrayList<>();
            stream.filter(Files::isDirectory).forEach(directories::add);
            directories.sort(Comparator.comparingInt(path -> Integer.parseInt(path.getFileName().toString())));
            return directories;
        } catch (NumberFormatException e) {
            throw new IngestException(3, "dzsave produced a non-numeric level directory", e);
        } catch (IOException e) {
            throw new IngestException(4, "cannot list dzsave output directories", e);
        }
    }

    private void writeManifest(Path manifestPath, String imageId, String imageVersion, PyramidPlan plan)
            throws IngestException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("imageId", imageId);
        root.put("imageVersion", imageVersion);
        root.put("width", plan.width());
        root.put("height", plan.height());
        root.put("tileSize", PyramidMath.TILE_SIZE);
        root.put("overlap", 0);
        root.put("depth", "onetile");

        ArrayNode levels = root.putArray("levels");
        for (PyramidLevel level : plan.levels()) {
            ObjectNode levelNode = levels.addObject();
            levelNode.put("z", level.z());
            levelNode.put("width", level.width());
            levelNode.put("height", level.height());
            levelNode.put("columns", level.columns());
            levelNode.put("rows", level.rows());
        }

        try {
            Files.createDirectories(manifestPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), root);
        } catch (IOException e) {
            throw new IngestException(4, "cannot write manifest.json", e);
        }
    }

    private void moveOrCopy(Path source, Path target) throws IngestException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ioException) {
                throw new IngestException(4, "cannot move tile " + source + " to " + target, ioException);
            }
        } catch (IOException e) {
            throw new IngestException(4, "cannot move tile " + source + " to " + target, e);
        }
    }

    private String diagnostic(ProcessResult result) {
        if (!result.stderr().isBlank()) {
            return result.stderr().strip();
        }
        if (!result.stdout().isBlank()) {
            return result.stdout().strip();
        }
        return "exitCode=" + result.exitCode();
    }
}