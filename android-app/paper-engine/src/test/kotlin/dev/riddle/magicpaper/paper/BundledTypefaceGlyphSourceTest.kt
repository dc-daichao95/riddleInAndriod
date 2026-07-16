package dev.riddle.magicpaper.paper

import android.graphics.Typeface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BundledTypefaceGlyphSourceTest {
    private val fonts = AndroidProjectTestPaths.paperEngine.resolve("src/main/assets/fonts")

    @Test fun `official bundled typefaces rasterize thin and trace latin and cjk paths`() {
        val source = AndroidTypefaceReplyGlyphSource(
            latin = Typeface.createFromFile(fonts.resolve("DancingScript.ttf")),
            cjk = Typeface.createFromFile(fonts.resolve("LXGWWenKai-Regular.ttf")),
        )

        assertTrue(source.strokes(ReplyFont.LATIN, "Magic").flatten().size > 20)
        assertTrue(source.strokes(ReplyFont.CJK, "魔").flatten().size > 20)
    }

    @Test fun `lxgw font retains official family and version names`() {
        val names = readSfntNames(fonts.resolve("LXGWWenKai-Regular.ttf"))
        assertTrue(names[1].orEmpty().any { it.contains("LXGW WenKai", ignoreCase = true) }, names.toString())
        assertTrue(names[5].orEmpty().any { it.contains("1.522") }, names.toString())
    }

    @Test fun `cancellation probe interrupts raster work without swallowing cancellation`() {
        var checks = 0
        val source = AndroidTypefaceReplyGlyphSource(
            Typeface.DEFAULT, Typeface.DEFAULT,
            cancellation = ReplyCancellationProbe { if (++checks > 2) throw CancellationException("stop") },
        )
        kotlin.test.assertFailsWith<CancellationException> { source.strokes(ReplyFont.LATIN, "W".repeat(200)) }
    }

    private fun readSfntNames(file: File): Map<Int, List<String>> {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val tableCount = buffer.getShort(4).toInt() and 0xffff
        val nameRecord = (0 until tableCount).firstNotNullOf { index ->
            val position = 12 + index * 16
            val tag = String(bytes, position, 4, Charsets.US_ASCII)
            if (tag == "name") buffer.getInt(position + 8) to buffer.getInt(position + 12) else null
        }
        val tableOffset = nameRecord.first
        val count = buffer.getShort(tableOffset + 2).toInt() and 0xffff
        val stringsOffset = tableOffset + (buffer.getShort(tableOffset + 4).toInt() and 0xffff)
        return (0 until count).mapNotNull { index ->
            val position = tableOffset + 6 + index * 12
            val platform = buffer.getShort(position).toInt() and 0xffff
            val nameId = buffer.getShort(position + 6).toInt() and 0xffff
            val length = buffer.getShort(position + 8).toInt() and 0xffff
            val offset = buffer.getShort(position + 10).toInt() and 0xffff
            if (stringsOffset + offset + length > tableOffset + nameRecord.second) return@mapNotNull null
            val charset = if (platform == 0 || platform == 3) Charsets.UTF_16BE else Charsets.ISO_8859_1
            nameId to String(bytes, stringsOffset + offset, length, charset).trim('\u0000')
        }.groupBy({ it.first }, { it.second })
    }
}
