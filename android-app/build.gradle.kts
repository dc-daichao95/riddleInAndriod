import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.getByType

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("com.android.legacy-kapt") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

val verifyAndroidCompatibility = tasks.register<VerifyAndroidCompatibilityTask>("verifyAndroidCompatibility") {
    group = "verification"
    description = "Verifies the Android 13-16 SDK compatibility baseline for every module."
}

subprojects {
    val androidProject = this
    plugins.withId("com.android.application") {
        afterEvaluate {
            val android = androidProject.extensions.getByType<ApplicationExtension>()
            verifyAndroidCompatibility.configure {
                moduleKinds.put(androidProject.path, "application")
                minSdks.put(androidProject.path, android.defaultConfig.minSdk ?: -1)
                compileSdks.put(androidProject.path, android.compileSdk ?: -1)
                compileSdkMinors.put(androidProject.path, android.compileSdkMinor ?: 0)
                targetSdks.put(androidProject.path, android.defaultConfig.targetSdk ?: -1)
            }
        }
    }
    plugins.withId("com.android.library") {
        afterEvaluate {
            val android = androidProject.extensions.getByType<LibraryExtension>()
            verifyAndroidCompatibility.configure {
                moduleKinds.put(androidProject.path, "library")
                minSdks.put(androidProject.path, android.defaultConfig.minSdk ?: -1)
                compileSdks.put(androidProject.path, android.compileSdk ?: -1)
                compileSdkMinors.put(androidProject.path, android.compileSdkMinor ?: 0)
            }
        }
    }
}
