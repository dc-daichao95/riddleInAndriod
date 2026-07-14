plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.riddle.magicpaper.settings"
    buildToolsVersion = "36.1.0"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig { minSdk = 33 }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":security"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.compose)
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.21")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
