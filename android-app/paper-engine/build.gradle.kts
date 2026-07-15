plugins {
    id("com.android.library")
}

val replyFonts = layout.projectDirectory.dir("src/main/assets/fonts")

val pinnedReplyAssets = mapOf(
    replyFonts.file("DancingScript.ttf").asFile to "21808625578fe8d8cd10cb684be546dca077b27cd03a53a2f1ec11dc743c924c",
    replyFonts.file("OFL.txt").asFile to "5a296979e49df4e947349fd6afdc3c0b305282d563f7ef2fad5412d2a235d1e8",
    replyFonts.file("LXGWWenKai-Regular.ttf").asFile to "39ad71264b588165b469e35e6afb162a378dacd1f95348160240ba9038ac3009",
    replyFonts.file("LXGWWenKai-OFL.txt").asFile to "c38b1994a5e48ac30ac7d1da7d0409fd8fd8127dfe28a13d6e787d5b1ef34a5e",
    file("unicode-data/15.0.0/GraphemeBreakProperty.txt") to "5a0f8748575432f8ff95e1dd5bfaa27bda1a844809e17d6939ee912bba6568a1",
    file("unicode-data/15.0.0/emoji-data.txt") to "29071dba22c72c27783a73016afb8ffaeb025866740791f9c2d0b55cc45a3470",
    file("unicode-data/15.0.0/LICENSE.txt") to "e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96",
)

tasks.register<VerifySha256Task>("verifyPinnedReplyAssets") {
    group = "verification"
    description = "Verifies pinned reply fonts, licenses, and Unicode 15.0 data inputs."
    assets.from(pinnedReplyAssets.keys)
    assets.from(replyFonts.file("MANIFEST.sha256"), file("unicode-data/15.0.0/MANIFEST.sha256"))
    expectedHashes.set(pinnedReplyAssets.mapKeys { (asset, _) -> asset.absolutePath })
    manifests.put(replyFonts.file("MANIFEST.sha256").asFile.absolutePath, replyFonts.asFile.absolutePath)
    manifests.put(file("unicode-data/15.0.0/MANIFEST.sha256").absolutePath, file("unicode-data/15.0.0").absolutePath)
}

android {
    namespace = "dev.riddle.magicpaper.paper"
    buildToolsVersion = "36.1.0"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 33
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

}

dependencies {
    implementation(project(":core-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.21")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
