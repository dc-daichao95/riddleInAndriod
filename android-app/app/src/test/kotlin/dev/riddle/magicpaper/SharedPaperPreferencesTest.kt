package dev.riddle.magicpaper

import android.content.Context
import dev.riddle.magicpaper.model.HandwritingLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SharedPaperPreferencesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("app-preferences-v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `unknown handwriting language migrates to automatic`() = runBlocking {
        context.getSharedPreferences("app-preferences-v1", Context.MODE_PRIVATE)
            .edit().putString("handwriting_language", "corrupt").commit()

        assertEquals(
            HandwritingLanguage.AUTOMATIC,
            SharedPaperPreferences(context).handwritingLanguage.first(),
        )
    }

    @Test fun `explicit handwriting language persists with stable identifier`() = runBlocking {
        val preferences = SharedPaperPreferences(context)

        preferences.setHandwritingLanguage(HandwritingLanguage.SIMPLIFIED_CHINESE)

        assertEquals(
            "simplified_chinese",
            context.getSharedPreferences("app-preferences-v1", Context.MODE_PRIVATE)
                .getString("handwriting_language", null),
        )
        assertEquals(
            HandwritingLanguage.SIMPLIFIED_CHINESE,
            SharedPaperPreferences(context).handwritingLanguage.first(),
        )
    }
}
