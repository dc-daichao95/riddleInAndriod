pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "riddle-android"

include(
    ":app",
    ":core-model",
    ":paper-engine",
    ":conversation",
    ":model-provider",
    ":security",
    ":memory",
    ":feature-paper",
    ":feature-settings",
)
