package dev.riddle.magicpaper

import android.content.Context
import androidx.room.Room
import dev.riddle.magicpaper.conversation.ConversationOrchestrator
import dev.riddle.magicpaper.conversation.ConversationStateMachine
import dev.riddle.magicpaper.conversation.FakeModelProvider
import dev.riddle.magicpaper.memory.DraftPage
import dev.riddle.magicpaper.memory.RiddleDatabase
import dev.riddle.magicpaper.memory.RoomMemoryRepository
import dev.riddle.magicpaper.model.FinishReason
import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelDescriptor
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelProvider
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderDescriptor
import dev.riddle.magicpaper.model.ProviderType
import dev.riddle.magicpaper.model.ValidationResult
import dev.riddle.magicpaper.paperui.PaperPersistence
import dev.riddle.magicpaper.paperui.PaperPreferences
import dev.riddle.magicpaper.paperui.PaperRecovery
import dev.riddle.magicpaper.paperui.PaperViewModel
import dev.riddle.magicpaper.provider.DeepSeekProvider
import dev.riddle.magicpaper.provider.OkHttpModelTransport
import dev.riddle.magicpaper.provider.OpenAiCompatibleProvider
import dev.riddle.magicpaper.security.AndroidKeystoreCredentialStore
import dev.riddle.magicpaper.security.CredentialResult
import dev.riddle.magicpaper.settings.ProviderFactory
import dev.riddle.magicpaper.settings.ProviderProfileRepository
import dev.riddle.magicpaper.settings.ProviderSettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

fun interface AppClock { fun nowMillis(): Long }

data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
)

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val clock: AppClock = AppClock(System::currentTimeMillis)
    val dispatchers = AppDispatchers()
    val httpClient: OkHttpClient = OkHttpModelTransport.defaultClient()
    val database: RiddleDatabase = Room.databaseBuilder(appContext, RiddleDatabase::class.java, "riddle.db").build()
    val credentialStore = AndroidKeystoreCredentialStore(appContext, dispatchers.io)
    val memoryRepository = RoomMemoryRepository(database)
    val profileRepository = SharedPreferencesProfileRepository(appContext)
    private val transport = OkHttpModelTransport(
        credentials = { alias -> (credentialStore.read(alias) as? CredentialResult.Success)?.value },
        client = httpClient,
    )
    val providerFactory = ProviderFactory { configuration ->
        when (configuration.type) {
            ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(configuration, transport)
            ProviderType.DEEPSEEK_COMPATIBLE -> DeepSeekProvider(configuration, transport)
        }
    }
    private val fakeProvider = FakeModelProvider(listOf(
        ModelEvent.TextDelta("The paper remembers."),
        ModelEvent.Completed(FinishReason.STOP),
    ))
    val modelProvider: ModelProvider = SelectedModelProvider(profileRepository, providerFactory, fakeProvider)
    val stateMachine = ConversationStateMachine()
    val conversationOrchestrator = ConversationOrchestrator(modelProvider)
    val paperPreferences: PaperPreferences = SharedPaperPreferences(appContext)
    val paperPersistence: PaperPersistence = RoomPaperPersistence(memoryRepository, appContext)
    val paperViewModel = PaperViewModel(
        modelProvider,
        paperPersistence,
        paperPreferences,
        dispatchers.default,
        selectedModelId = profileRepository::selectedModelId,
    )
    val providerSettingsViewModel = ProviderSettingsViewModel(profileRepository, credentialStore, providerFactory)
}

private class RoomPaperPersistence(
    private val memory: RoomMemoryRepository,
    context: Context,
) : PaperPersistence {
    private val preferences = context.getSharedPreferences("paper-session-v1", Context.MODE_PRIVATE)
    override suspend fun load(): PaperRecovery {
        val draft = memory.loadDraft()
        return PaperRecovery(draft?.strokes.orEmpty(), preferences.getBoolean("streaming", false))
    }
    override suspend fun saveDraft(recovery: PaperRecovery) {
        memory.replaceDraft(DraftPage(System.currentTimeMillis(), recovery.strokes))
    }
    override suspend fun markStreaming(strokes: List<dev.riddle.magicpaper.model.PaperStroke>) {
        memory.replaceDraft(DraftPage(System.currentTimeMillis(), strokes))
        preferences.edit().putBoolean("streaming", true).commit()
    }
    override suspend fun clearStreamingAndDraft() {
        memory.clearDraft()
        preferences.edit().putBoolean("streaming", false).commit()
    }
}

private class SharedPaperPreferences(context: Context) : PaperPreferences {
    private val preferences = context.getSharedPreferences("app-preferences-v1", Context.MODE_PRIVATE)
    override val portraitLocked = MutableStateFlow(preferences.getBoolean("portrait_locked", false))
    override suspend fun setPortraitLocked(locked: Boolean) {
        if (preferences.edit().putBoolean("portrait_locked", locked).commit()) portraitLocked.value = locked
    }
}

private class SelectedModelProvider(
    private val profiles: SharedPreferencesProfileRepository,
    private val factory: ProviderFactory,
    private val fake: ModelProvider,
) : ModelProvider {
    private fun selected(): ModelProvider = profiles.selectedConfiguration()?.let(factory::create) ?: fake
    override val descriptor: ProviderDescriptor get() = selected().descriptor
    override fun stream(request: ModelRequest): Flow<ModelEvent> = selected().stream(request)
    override suspend fun listModels(): Result<List<ModelDescriptor>> = selected().listModels()
    override suspend fun validate(configuration: ProviderConfiguration): ValidationResult = factory.create(configuration).validate(configuration)
}

class SharedPreferencesProfileRepository(context: Context) : ProviderProfileRepository {
    private val preferences = context.getSharedPreferences("provider-profiles-v1", Context.MODE_PRIVATE)

    override suspend fun list(): List<ProviderConfiguration> = readProfiles()
    override suspend fun upsert(profile: ProviderConfiguration) {
        val profiles = readProfiles().filterNot { it.id == profile.id } + profile
        writeProfiles(profiles)
    }
    override suspend fun delete(id: String) {
        writeProfiles(readProfiles().filterNot { it.id == id })
        if (selectedId() == id) select(null)
    }
    override suspend fun select(id: String?) { preferences.edit().putString("selected", id).commit() }
    override suspend fun selectedId(): String? = preferences.getString("selected", null)
    fun selectedConfiguration(): ProviderConfiguration? {
        val selected = preferences.getString("selected", null) ?: return null
        return readProfiles().firstOrNull { it.id == selected && it.enabled }
    }
    fun selectedModelId(): String = selectedConfiguration()?.defaultModelId ?: "fake"

    private fun readProfiles(): List<ProviderConfiguration> = runCatching {
        val array = JSONArray(preferences.getString("profiles", "[]"))
        (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun writeProfiles(profiles: List<ProviderConfiguration>) {
        val array = JSONArray().apply { profiles.forEach { put(encode(it)) } }
        check(preferences.edit().putString("profiles", array.toString()).commit())
    }

    private fun encode(profile: ProviderConfiguration) = JSONObject().apply {
        put("id", profile.id); put("type", profile.type.name); put("name", profile.displayName)
        put("url", profile.baseUrl); put("alias", profile.credentialAlias); put("model", profile.defaultModelId)
        put("enabled", profile.enabled)
        put("capabilities", JSONObject().apply {
            put("streaming", profile.capabilities.streaming); put("vision", profile.capabilities.vision)
            put("toolCalling", profile.capabilities.toolCalling); put("structuredOutput", profile.capabilities.structuredOutput)
            put("reasoning", profile.capabilities.reasoning); put("systemMessages", profile.capabilities.systemMessages)
        })
    }

    private fun decode(value: JSONObject): ProviderConfiguration {
        val capabilities = value.getJSONObject("capabilities")
        return ProviderConfiguration(
            value.getString("id"), ProviderType.valueOf(value.getString("type")), value.getString("name"),
            value.getString("url"), value.getString("alias"), value.optString("model").takeIf { it.isNotBlank() },
            value.getBoolean("enabled"), ModelCapabilities(
                capabilities.getBoolean("streaming"), capabilities.getBoolean("vision"), capabilities.getBoolean("toolCalling"),
                capabilities.getBoolean("structuredOutput"), capabilities.getBoolean("reasoning"), capabilities.getBoolean("systemMessages"),
            ),
        )
    }
}
