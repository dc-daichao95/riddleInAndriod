plugins { id("com.android.library") }

android {
    namespace = "dev.riddle.magicpaper.provider"
    buildToolsVersion = "36.1.0"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig { minSdk = 36 }
}

dependencies {
    implementation(project(":core-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.21")
    testImplementation("junit:junit:4.13.2")
}
