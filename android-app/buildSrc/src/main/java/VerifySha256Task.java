import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Configuration-cache-safe checksum verification for pinned repository assets. */
public abstract class VerifySha256Task extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getAssets();

    @Input
    public abstract MapProperty<String, String> getExpectedHashes();

    /** Maps each manifest file to the root used to resolve its listed relative paths. */
    @Input
    public abstract MapProperty<String, String> getManifests();

    @TaskAction
    public void verify() throws Exception {
        Map<String, String> expectedHashes = getExpectedHashes().get();
        for (Map.Entry<String, String> entry : getManifests().getOrElse(Map.of()).entrySet()) {
            File manifest = new File(entry.getKey());
            if (!manifest.isFile()) {
                throw new IllegalStateException("Missing pinned manifest: " + manifest);
            }
            Path root = new File(entry.getValue()).toPath().toAbsolutePath().normalize();
            Map<String, String> expectedEntries = expectedEntriesUnder(root, expectedHashes);
            Map<String, String> manifestEntries = new LinkedHashMap<>();
            for (String line : Files.readAllLines(manifest.toPath())) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] fields = trimmed.split("\\s+", 2);
                if (fields.length != 2 || !fields[0].matches("[0-9a-fA-F]{64}")) {
                    throw new IllegalStateException("Malformed pinned manifest entry: " + trimmed);
                }
                String relative = normalizedRelativePath(fields[1].trim());
                if (manifestEntries.putIfAbsent(relative, fields[0]) != null) {
                    throw new IllegalStateException("Duplicate pinned manifest entry: " + relative);
                }
                Path asset = root.resolve(relative).normalize();
                if (!asset.startsWith(root) || !Files.isRegularFile(asset)) {
                    throw new IllegalStateException("Missing manifest asset: " + asset);
                }
                String actual = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(asset)));
                if (!actual.equalsIgnoreCase(fields[0])) {
                    throw new IllegalStateException("Manifest SHA-256 mismatch for " + asset + ": " + actual);
                }
            }
            if (!manifestEntries.keySet().equals(expectedEntries.keySet())) {
                throw new IllegalStateException("Pinned manifest entries do not exactly match configured assets: " + manifest);
            }
            for (Map.Entry<String, String> expected : expectedEntries.entrySet()) {
                if (!expected.getValue().equalsIgnoreCase(manifestEntries.get(expected.getKey()))) {
                    throw new IllegalStateException("Pinned manifest hash disagrees with configured source record: " + expected.getKey());
                }
            }
        }
        for (Map.Entry<String, String> entry : expectedHashes.entrySet()) {
            File asset = new File(entry.getKey());
            if (!asset.isFile()) {
                throw new IllegalStateException("Missing pinned asset: " + asset);
            }
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(asset.toPath())));
            if (!actual.equals(entry.getValue())) {
                throw new IllegalStateException("SHA-256 mismatch for " + asset + ": " + actual);
            }
        }
    }

    private static Map<String, String> expectedEntriesUnder(Path root, Map<String, String> expectedHashes) {
        Map<String, String> entries = new HashMap<>();
        for (Map.Entry<String, String> expected : expectedHashes.entrySet()) {
            Path asset = Path.of(expected.getKey()).toAbsolutePath().normalize();
            if (asset.startsWith(root)) {
                entries.put(assetName(root.relativize(asset)), expected.getValue());
            }
        }
        return entries;
    }

    private static String normalizedRelativePath(String value) {
        if (value.isBlank()) throw new IllegalStateException("Malformed pinned manifest path");
        Path path = Path.of(value);
        if (path.isAbsolute()) throw new IllegalStateException("Pinned manifest path must be relative: " + value);
        Path normalized = path.normalize();
        if (normalized.getNameCount() == 0 || normalized.startsWith("..") || normalized.toString().equals(".")) {
            throw new IllegalStateException("Pinned manifest path escapes root: " + value);
        }
        return assetName(normalized);
    }

    private static String assetName(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }
}
