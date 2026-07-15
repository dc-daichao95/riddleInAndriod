package dev.riddle.magicpaper

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesProfileRepositoryTest {
    @Test fun `JSON null model decodes as absent and round trips as JSON null`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("provider-profiles-v1", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        preferences.edit().putString("profiles", JSONArray().put(profileJson(JSONObject.NULL)).toString()).commit()
        val repository = SharedPreferencesProfileRepository(context)

        val decoded = repository.list().single()

        assertNull(decoded.defaultModelId)
        repository.upsert(decoded)
        val stored = JSONArray(preferences.getString("profiles", null)).getJSONObject(0)
        assertTrue(!stored.has("model") || stored.isNull("model"))
        assertNull(repository.list().single().defaultModelId)
    }

    private fun profileJson(model: Any): JSONObject = JSONObject().apply {
        put("id", "profile")
        put("type", "OPENAI_COMPATIBLE")
        put("name", "Provider")
        put("url", "https://example.test/v1")
        put("alias", "credential")
        put("model", model)
        put("enabled", true)
        put("capabilities", JSONObject().apply {
            put("streaming", true)
            put("vision", false)
            put("toolCalling", false)
            put("structuredOutput", false)
            put("reasoning", false)
            put("systemMessages", true)
        })
    }
}
