plugins {
    id("com.android.library")
}

android {
    namespace = "dev.riddle.magicpaper.conversation"
    buildToolsVersion = "36.1.0"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }
    defaultConfig { minSdk = 33 }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":paper-engine"))
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.21")
    testImplementation("junit:junit:4.13.2")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
