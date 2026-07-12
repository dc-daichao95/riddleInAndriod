plugins {
    id("com.android.application")
}

android {
    namespace = "dev.riddle.magicpaper"
    buildToolsVersion = "36.1.0"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.riddle.magicpaper"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}
