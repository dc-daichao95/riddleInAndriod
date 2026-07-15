package dev.riddle.magicpaper

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.createSavedStateHandle
import dev.riddle.magicpaper.conversation.ConversationOrchestrator
import dev.riddle.magicpaper.conversation.ConversationStateMachine
import dev.riddle.magicpaper.conversation.FakeModelProvider
import dev.riddle.magicpaper.conversation.MlKitHandwritingRecognizer
import dev.riddle.magicpaper.conversation.TurnInputRouter
import dev.riddle.magicpaper.memory.DraftPage
import dev.riddle.magicpaper.memory.RiddleDatabase
import dev.riddle.magicpaper.memory.RoomMemoryRepository
import dev.riddle.magicpaper.model.FinishReason
import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelProvider
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderType
import dev.riddle.magicpaper.paperui.PaperPersistence
import dev.riddle.magicpaper.paperui.AppClock
import dev.riddle.magicpaper.paperui.PaperPreferences
import dev.riddle.magicpaper.paperui.PaperRecovery
import dev.riddle.magicpaper.paperui.PaperViewModel
import dev.riddle.magicpaper.paperui.ModelSelection
import dev.riddle.magicpaper.paperui.SelectedModel
import dev.riddle.magicpaper.paper.PageRasterizer
import dev.riddle.magicpaper.paperui.AtomicPageGeometryPort
import dev.riddle.magicpaper.model.SettingsEntryMode
import dev.riddle.magicpaper.provider.DeepSeekProvider
import dev.riddle.magicpaper.provider.OkHttpModelTransport
import dev.riddle.magicpaper.provider.OpenAiCompatibleProvider
import dev.riddle.magicpaper.security.AndroidKeystoreCredentialStore
import dev.riddle.magicpaper.security.CredentialResult
import dev.riddle.magicpaper.security.DurableCredentialTransactionJournalStore
import dev.riddle.magicpaper.security.SharedPreferencesCredentialTransactionJournalStorage
import dev.riddle.magicpaper.settings.CredentialTransactionCoordinator
import dev.riddle.magicpaper.settings.CredentialTransactionResult
import dev.riddle.magicpaper.settings.ProviderFactory
import dev.riddle.magicpaper.settings.ProviderProfileRepository
import dev.riddle.magicpaper.provider.optNullableString
import dev.riddle.magicpaper.settings.ProviderSettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
)

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val clock: AppClock = object : AppClock {
        override fun elapsedRealtimeMillis() = SystemClock.elapsedRealtime()
        override fun wallClockMillis() = System.currentTimeMillis()
    }
    val dispatchers = AppDispatchers()
    val httpClient: OkHttpClient = OkHttpModelTransport.defaultClient()
    val database: RiddleDatabase = Room.databaseBuilder(appContext, RiddleDatabase::class.java, "riddle.db").build()
    val credentialStore = AndroidKeystoreCredentialStore(appContext, dispatchers.io)
    val memoryRepository = RoomMemoryRepository(database)
    val profileRepository = SharedPreferencesProfileRepository(appContext)
    private val applicationScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val credentialJournal = DurableCredentialTransactionJournalStore(
        SharedPreferencesCredentialTransactionJournalStorage(appContext),
    )
    val credentialTransactions = CredentialTransactionCoordinator(
        profileRepository,
        credentialStore,
        credentialJournal,
    )
    val credentialRecoveryState = MutableStateFlow<CredentialTransactionResult<Unit>?>(null)

    init {
        applicationScope.launch {
            credentialRecoveryState.value = credentialTransactions.recover()
        }
    }
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
    internal val fakeProvider = FakeModelProvider(flow {
        delay(500)
        emit(ModelEvent.TextDelta("The paper remembers."))
        delay(500)
        emit(ModelEvent.Completed(FinishReason.STOP))
    })
    private val modelSelection = ModelSelection {
        profileRepository.selectedConfiguration()?.let { configuration ->
            SelectedModel(
                providerFactory.create(configuration),
                checkNotNull(configuration.defaultModelId),
                configuration.capabilities,
            )
        } ?: SelectedModel(
            fakeProvider,
            "fake",
            ModelCapabilities(streaming = true, vision = true),
        )
    }
    private val stateMachine = ConversationStateMachine()
    private val pageRasterizer = PageRasterizer(
        appContext.cacheDir,
        dispatcher = dispatchers.default,
    )
    val pageGeometry = AtomicPageGeometryPort()
    private val turnInputRouter = TurnInputRouter(
        pageRasterizer,
        MlKitHandwritingRecognizer(applicationScope = applicationScope, dispatcher = dispatchers.default),
    )
    val paperPreferences: PaperPreferences = SharedPaperPreferences(appContext)
    private val paperPersistence: PaperPersistence = RoomPaperPersistence(memoryRepository, appContext, clock)

    val paperViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(PaperViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return createPaperViewModel(extras.createSavedStateHandle()) as T
        }
    }
    val providerSettingsViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(ProviderSettingsViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ProviderSettingsViewModel(
                profileRepository,
                credentialStore,
                providerFactory,
                credentialTransactions,
            ) as T
        }
    }

    private fun createPaperViewModel(savedStateHandle: SavedStateHandle) = PaperViewModel(
        modelSelection = modelSelection,
        persistence = paperPersistence,
        preferences = paperPreferences,
        turnInputRouter = turnInputRouter,
        stateMachine = stateMachine,
        orchestratorFactory = ::ConversationOrchestrator,
        savedStateHandle = savedStateHandle,
        workerDispatcher = dispatchers.default,
        clock = clock,
        pageGeometry = pageGeometry,
    )
}

private class RoomPaperPersistence(
    private val memory: RoomMemoryRepository,
    context: Context,
    private val clock: AppClock,
) : PaperPersistence {
    private val preferences = context.getSharedPreferences("paper-session-v1", Context.MODE_PRIVATE)
    override suspend fun load(): PaperRecovery {
        val draft = memory.loadDraft()
        return PaperRecovery(draft?.strokes.orEmpty(), preferences.getBoolean("streaming", false))
    }
    override suspend fun saveDraft(recovery: PaperRecovery) {
        memory.replaceDraft(DraftPage(clock.wallClockMillis(), recovery.strokes))
        preferences.edit().putBoolean("streaming", recovery.interrupted).commit()
    }
    override suspend fun markStreaming(strokes: List<dev.riddle.magicpaper.model.PaperStroke>) {
        memory.replaceDraft(DraftPage(clock.wallClockMillis(), strokes))
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
    override val settingsEntryMode = MutableStateFlow(
        SettingsEntryMode.fromPersistedId(preferences.getString("settings_entry_mode", null)),
    )
    override suspend fun setPortraitLocked(locked: Boolean) {
        if (preferences.edit().putBoolean("portrait_locked", locked).commit()) portraitLocked.value = locked
    }
    override suspend fun setSettingsEntryMode(mode: SettingsEntryMode) {
        if (preferences.edit().putString("settings_entry_mode", mode.persistedId).commit()) {
            settingsEntryMode.value = mode
        }
    }
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
            value.getString("url"), value.getString("alias"), value.optNullableString("model"),
            value.getBoolean("enabled"), ModelCapabilities(
                capabilities.getBoolean("streaming"), capabilities.getBoolean("vision"), capabilities.getBoolean("toolCalling"),
                capabilities.getBoolean("structuredOutput"), capabilities.getBoolean("reasoning"), capabilities.getBoolean("systemMessages"),
            ),
        )
    }
}
