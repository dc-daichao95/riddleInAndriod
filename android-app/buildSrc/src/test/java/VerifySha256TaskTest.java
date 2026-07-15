import static org.junit.Assert.assertThrows;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

public class VerifySha256TaskTest {
    @Test public void missingManifestFails() {
        VerifySha256Task task = task();
        task.getManifests().put("missing", "missing");
        assertThrows(IllegalStateException.class, task::verify);
    }
    @Test public void tamperedListedFileFails() throws Exception {
        Path root = fixtureRoot();
        Files.writeString(root.resolve("asset.txt"), "tampered");
        VerifySha256Task task = configured(root, "00  asset.txt\n");
        assertThrows(IllegalStateException.class, task::verify);
    }
    @Test public void missingValidEntryFailsExactManifestValidation() throws Exception {
        Path root = fixtureRoot();
        VerifySha256Task task = configured(root, entry(root, "asset.txt"));
        assertThrows(IllegalStateException.class, task::verify);
    }
    @Test public void extraEntryFailsExactManifestValidation() throws Exception {
        Path root = fixtureRoot();
        Files.writeString(root.resolve("extra.txt"), "extra");
        VerifySha256Task task = configured(root, entry(root, "asset.txt") + entry(root, "other.txt") + entry(root, "extra.txt"));
        assertThrows(IllegalStateException.class, task::verify);
    }
    @Test public void duplicateEntryFailsExactManifestValidation() throws Exception {
        Path root = fixtureRoot();
        String asset = entry(root, "asset.txt");
        VerifySha256Task task = configured(root, asset + asset);
        assertThrows(IllegalStateException.class, task::verify);
    }
    @Test public void malformedAndEscapingPathsFailExactManifestValidation() throws Exception {
        Path root = fixtureRoot();
        VerifySha256Task malformed = configured(root, "not-a-sha  asset.txt\n");
        assertThrows(IllegalStateException.class, malformed::verify);
        VerifySha256Task escaping = configured(root, hash(root.resolve("asset.txt")) + "  ../asset.txt\n");
        assertThrows(IllegalStateException.class, escaping::verify);
    }
    private static Path fixtureRoot() throws Exception {
        Path root = Files.createTempDirectory("manifest");
        Files.writeString(root.resolve("asset.txt"), "asset");
        Files.writeString(root.resolve("other.txt"), "other");
        return root;
    }
    private static VerifySha256Task configured(Path root, String entries) throws Exception {
        Files.writeString(root.resolve("MANIFEST.sha256"), entries);
        VerifySha256Task task = task();
        task.getManifests().put(root.resolve("MANIFEST.sha256").toString(), root.toString());
        task.getExpectedHashes().put(root.resolve("asset.txt").toString(), hash(root.resolve("asset.txt")));
        task.getExpectedHashes().put(root.resolve("other.txt").toString(), hash(root.resolve("other.txt")));
        return task;
    }
    private static String entry(Path root, String name) throws Exception { return hash(root.resolve(name)) + "  " + name + "\n"; }
    private static String hash(Path file) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private static VerifySha256Task task() {
        Project project = ProjectBuilder.builder().build();
        return project.getTasks().create("verifySha", VerifySha256Task.class);
    }
}
