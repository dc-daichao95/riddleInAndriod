import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

public abstract class VerifyAndroidCompatibilityTask extends DefaultTask {
    @Input
    public abstract MapProperty<String, String> getModuleKinds();

    @Input
    public abstract MapProperty<String, Integer> getMinSdks();

    @Input
    public abstract MapProperty<String, Integer> getCompileSdks();

    @Input
    public abstract MapProperty<String, Integer> getCompileSdkMinors();

    @Input
    public abstract MapProperty<String, Integer> getTargetSdks();

    @TaskAction
    public void verify() {
        Map<String, String> moduleKinds = getModuleKinds().get();
        Map<String, Integer> minSdks = getMinSdks().get();
        Map<String, Integer> compileSdks = getCompileSdks().get();
        Map<String, Integer> compileSdkMinors = getCompileSdkMinors().get();
        Map<String, Integer> targetSdks = getTargetSdks().get();
        List<String> failures = new ArrayList<>();
        Set<String> modules = new TreeSet<>(moduleKinds.keySet());

        for (String module : modules) {
            Integer minSdk = minSdks.get(module);
            Integer compileSdk = compileSdks.get(module);
            Integer compileSdkMinor = compileSdkMinors.get(module);
            if (minSdk == null) {
                failures.add(module + " is missing minSdk DSL input");
            } else if (minSdk != 33) {
                failures.add(module + " must declare minSdk = 33 (was " + minSdk + ")");
            }
            if (compileSdk == null) {
                failures.add(module + " is missing compileSdk DSL input");
            }
            if (compileSdkMinor == null) {
                failures.add(module + " is missing compileSdk minor DSL input");
            }
            if (compileSdk != null && compileSdkMinor != null
                    && (compileSdk != 36 || compileSdkMinor != 1)) {
                failures.add(module + " must compile against API 36.1 (was "
                        + compileSdk + "." + compileSdkMinor + ")");
            }

            String kind = moduleKinds.get(module);
            if ("application".equals(kind)) {
                Integer targetSdk = targetSdks.get(module);
                if (targetSdk == null) {
                    failures.add(module + " is missing targetSdk DSL input");
                } else if (targetSdk != 36) {
                    failures.add(module + " must declare targetSdk = 36 (was " + targetSdk + ")");
                }
            } else if (!"library".equals(kind)) {
                failures.add(module + " has unsupported Android module kind " + kind);
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Android compatibility verification failed:\n- " + String.join("\n- ", failures));
        }
    }
}
