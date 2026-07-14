import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

public class VerifyAndroidCompatibilityTaskTest {
    @Test
    public void acceptsAndroid13Through16DslValues() {
        VerifyAndroidCompatibilityTask task = task();
        task.getModuleKinds().put(":app", "application");
        task.getModuleKinds().put(":library", "library");
        task.getMinSdks().put(":app", 33);
        task.getMinSdks().put(":library", 33);
        task.getCompileSdks().put(":app", 36);
        task.getCompileSdks().put(":library", 36);
        task.getCompileSdkMinors().put(":app", 1);
        task.getCompileSdkMinors().put(":library", 1);
        task.getTargetSdks().put(":app", 36);

        task.verify();
    }

    @Test
    public void rejectsWrongEffectiveDslValues() {
        VerifyAndroidCompatibilityTask task = task();
        task.getModuleKinds().put(":app", "application");
        task.getMinSdks().put(":app", 36);
        task.getCompileSdks().put(":app", 35);
        task.getCompileSdkMinors().put(":app", 0);
        task.getTargetSdks().put(":app", 35);

        IllegalStateException failure = assertThrows(IllegalStateException.class, task::verify);

        assertTrue(failure.getMessage().contains(":app must declare minSdk = 33 (was 36)"));
        assertTrue(failure.getMessage().contains(":app must compile against API 36.1 (was 35.0)"));
        assertTrue(failure.getMessage().contains(":app must declare targetSdk = 36 (was 35)"));
    }

    @Test
    public void rejectsAnyDiscoveredAndroidModuleMissingDslInputs() {
        VerifyAndroidCompatibilityTask task = task();
        task.getModuleKinds().put(":future", "library");

        IllegalStateException failure = assertThrows(IllegalStateException.class, task::verify);

        assertTrue(failure.getMessage().contains(":future is missing minSdk DSL input"));
        assertTrue(failure.getMessage().contains(":future is missing compileSdk DSL input"));
        assertTrue(failure.getMessage().contains(":future is missing compileSdk minor DSL input"));
    }

    private static VerifyAndroidCompatibilityTask task() {
        Project project = ProjectBuilder.builder().build();
        return project.getTasks().create("verifyAndroidCompatibility", VerifyAndroidCompatibilityTask.class);
    }
}
