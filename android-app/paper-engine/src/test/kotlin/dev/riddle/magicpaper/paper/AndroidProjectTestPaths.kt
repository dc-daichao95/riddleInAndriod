package dev.riddle.magicpaper.paper

import java.io.File

internal object AndroidProjectTestPaths {
    val paperEngine: File = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile.normalize().also {
        check(it.resolve("build.gradle.kts").isFile) { "Tests must run from the paper-engine module root: ${it.path}" }
    }
}
