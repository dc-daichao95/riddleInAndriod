package dev.riddle.magicpaper.paper

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinnedReplyAssetsTest {
    @Test fun `pinned reply assets exist with exact checksums and licenses`() {
        val repository = repositoryRoot()
        val fonts = repository.resolve("android-app/paper-engine/src/main/assets/fonts")
        val unicode = repository.resolve("android-app/paper-engine/unicode-data/15.0.0")
        assertSha(fonts.resolve("LXGWWenKai-Regular.ttf"), "39ad71264b588165b469e35e6afb162a378dacd1f95348160240ba9038ac3009")
        assertSha(fonts.resolve("DancingScript.ttf"), "21808625578fe8d8cd10cb684be546dca077b27cd03a53a2f1ec11dc743c924c")
        assertTrue(fonts.resolve("LXGWWenKai-OFL.txt").readText().contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(fonts.resolve("OFL.txt").readText().contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(unicode.resolve("LICENSE.txt").readText().contains("UNICODE LICENSE"))
        assertSha(unicode.resolve("GraphemeBreakProperty.txt"), "5a0f8748575432f8ff95e1dd5bfaa27bda1a844809e17d6939ee912bba6568a1")
        assertSha(unicode.resolve("emoji-data.txt"), "29071dba22c72c27783a73016afb8ffaeb025866740791f9c2d0b55cc45a3470")
        assertSha(repository.resolve("android-app/paper-engine/src/test/resources/unicode/15.0.0/GraphemeBreakTest.txt"), "0d2080d0def294a4b7660801cc03ddfe5866ff300c789c2cc1b50fd7802b2d97")
    }

    private fun assertSha(file: File, expected: String) {
        assertTrue(file.isFile, "missing ${file.path}")
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual, file.path)
    }

    private fun repositoryRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("AGENTS.md").isFile }
}
