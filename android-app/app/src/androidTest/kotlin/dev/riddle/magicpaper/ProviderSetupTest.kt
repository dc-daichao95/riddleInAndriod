package dev.riddle.magicpaper

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.pressBack
import dev.riddle.magicpaper.model.ModelDescriptor
import dev.riddle.magicpaper.model.ModelDiscoveryResult
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelProvider
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.ProviderDescriptor
import dev.riddle.magicpaper.model.ValidationResult
import dev.riddle.magicpaper.security.CredentialError
import dev.riddle.magicpaper.security.CredentialResult
import dev.riddle.magicpaper.security.CredentialStore
import dev.riddle.magicpaper.security.CredentialTransactionJournal
import dev.riddle.magicpaper.security.CredentialTransactionJournalStore
import dev.riddle.magicpaper.settings.CredentialTransactionCoordinator
import dev.riddle.magicpaper.settings.PresetKind
import dev.riddle.magicpaper.settings.ProviderFactory
import dev.riddle.magicpaper.settings.ProviderProfileRepository
import dev.riddle.magicpaper.settings.ProviderSettingsRoute
import dev.riddle.magicpaper.settings.ProviderSettingsViewModel
import dev.riddle.magicpaper.settings.ProviderSettingsScreen
import dev.riddle.magicpaper.settings.ProviderSettingsUiState
import dev.riddle.magicpaper.settings.ProviderProfileUiModel
import dev.riddle.magicpaper.settings.ProviderSetupStage
import dev.riddle.magicpaper.settings.ProviderModelSelection
import dev.riddle.magicpaper.settings.ManualFallbackReason
import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class ProviderSetupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun flag_secure_tracks_only_credential_stage_and_clears_on_recreation_and_back() {
        openSettings()
        assertFalse(isSecure())

        openCredentialStage()
        assertTrue(isSecure())
        assertTrue(hasAutofillDisabled(compose.activity.window.decorView))

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("provider_api_key").assertDoesNotExist()
        assertFalse(isSecure())

        openCredentialStage()
        assertTrue(isSecure())
        pressBack()
        compose.onNodeWithTag("provider_api_key").assertDoesNotExist()
        assertFalse(isSecure())
    }

    @Test fun secure_key_has_no_copy_or_cut_actions_and_stop_abandons_editor() {
        openSettings()
        openCredentialStage()
        compose.onNodeWithTag("provider_api_key")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CopyText))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CutText))
            .performTextInput("sentinel-secret")

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithTag("provider_api_key").assertDoesNotExist()
        assertFalse(isSecure())
    }

    @Test fun narrow_large_font_provider_controls_wrap_and_keep_heading_semantics() {
        val profile = ProviderConfiguration(
            "profile", ProviderType.OPENAI_COMPATIBLE, "Provider", "https://api.example.com/v1",
            "credential", "model", true, ModelCapabilities(streaming = true),
        )
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    MaterialTheme {
                        Box(Modifier.width(320.dp).fillMaxHeight().testTag("provider_root")) {
                            ProviderSettingsScreen(
                                state = ProviderSettingsUiState(profiles = listOf(ProviderProfileUiModel(profile))),
                                onReviewEndpoint = {},
                                onValidateAndDiscover = { _, _, _, _ -> },
                                onValidateManualModel = {},
                                onSelectDiscoveredModel = {},
                                onSaveValidated = {},
                                onAbandon = {},
                                onValidate = { _, _ -> },
                                onEnabled = { _, _ -> },
                                onSelect = {},
                                onDelete = {},
                                onEditorChange = {},
                                onPreset = { _, _ -> },
                                onEdit = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("provider_title").assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        val root = compose.onNodeWithTag("provider_root").fetchSemanticsNode().boundsInRoot
        listOf(PresetKind.OPENAI, PresetKind.DEEPSEEK, PresetKind.CUSTOM_HTTPS).forEach { kind ->
            val bounds = compose.onNodeWithTag("provider_preset_${kind.name.lowercase()}").fetchSemanticsNode().boundsInRoot
            assertInside(bounds, root)
        }
        compose.onNodeWithTag("provider_continue").assertExists()
        compose.onNodeWithTag("provider_list").performScrollToNode(hasTestTag("provider_edit_profile"))
        assertInside(compose.onNodeWithTag("provider_edit_profile").fetchSemanticsNode().boundsInRoot, root)
        assertInside(compose.onNodeWithTag("provider_delete_profile").fetchSemanticsNode().boundsInRoot, root)
    }

    @Test fun manual_fallback_requires_visible_disclosure_and_dispatches_explicit_action() {
        var validatedModel: String? = null
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    ProviderSettingsScreen(
                        state = ProviderSettingsUiState(
                            setupStage = ProviderSetupStage.CREDENTIAL_VALIDATION,
                            confirmedHost = "api.example.com",
                            editor = dev.riddle.magicpaper.settings.ProviderEditorUiState(modelId = "preset-model"),
                            manualModelAllowed = true,
                            modelSelection = ProviderModelSelection.ManualFallback(
                                "preset-model",
                                ManualFallbackReason.UNSUPPORTED_DISCOVERY,
                            ),
                        ),
                        onReviewEndpoint = {},
                        onValidateAndDiscover = { _, _, _, _ -> },
                        onValidateManualModel = { validatedModel = it },
                        onSelectDiscoveredModel = {}, onSaveValidated = {}, onAbandon = {},
                        onValidate = { _, _ -> }, onEnabled = { _, _ -> }, onSelect = {}, onDelete = {},
                        onEditorChange = {}, onPreset = { _, _ -> }, onEdit = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("provider_manual_disclosure").assertExists()
        compose.onNodeWithTag("provider_manual_model").assertTextContains("preset-model")
        compose.onNodeWithTag("provider_manual_model").performTextReplacement("manual-model")
        compose.waitForIdle()
        compose.onNodeWithTag("provider_manual_model").assertTextContains("manual-model")
        compose.onNodeWithTag("provider_manual_validate").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals("manual-model", validatedModel) }
    }

    @Test fun production_callback_preserves_preset_into_catalog_selection() {
        val viewModel = providerViewModel(ModelDiscoveryResult.Success(listOf(
            ModelDescriptor("a-model", "A", ModelCapabilities(streaming = true)),
            ModelDescriptor("gpt-4.1-mini", "Preset", ModelCapabilities(streaming = true)),
        )))

        submitOpenAiPresetThroughProductionRoute(viewModel)

        compose.waitUntil(5_000) { viewModel.state.value.modelSelection is ProviderModelSelection.Catalog }
        compose.runOnIdle { assertEquals("gpt-4.1-mini", viewModel.state.value.selectedDiscoveredModelId) }
    }

    @Test fun production_callback_preserves_preset_into_manual_fallback() {
        val viewModel = providerViewModel(ModelDiscoveryResult.Unsupported)

        submitOpenAiPresetThroughProductionRoute(viewModel)

        compose.waitUntil(5_000) { viewModel.state.value.modelSelection is ProviderModelSelection.ManualFallback }
        compose.runOnIdle {
            assertEquals(
                ProviderModelSelection.ManualFallback(
                    "gpt-4.1-mini",
                    ManualFallbackReason.UNSUPPORTED_DISCOVERY,
                ),
                viewModel.state.value.modelSelection,
            )
        }
        compose.onNodeWithTag("provider_manual_model").assertTextContains("gpt-4.1-mini")
    }

    private fun submitOpenAiPresetThroughProductionRoute(viewModel: ProviderSettingsViewModel) {
        viewModel.applyPreset(PresetKind.OPENAI, "OpenAI")
        viewModel.reviewEndpoint()
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme { ProviderSettingsRoute(viewModel) }
            }
        }
        compose.onNodeWithTag("provider_api_key").performTextInput("test-secret")
        compose.onNodeWithTag("provider_validate_models").performClick()
    }

    private fun providerViewModel(discovery: ModelDiscoveryResult): ProviderSettingsViewModel {
        val profiles = InstrumentationProfiles()
        val credentials = InstrumentationCredentials()
        return ProviderSettingsViewModel(
            profiles,
            credentials,
            InstrumentationProviderFactory(discovery),
            CredentialTransactionCoordinator(
                profiles,
                credentials,
                InstrumentationJournal(),
                Dispatchers.Unconfined,
                nonce = { "test" },
            ),
        )
    }

    private fun openSettings() {
        compose.onNodeWithTag("magic_rune_touch").performClick()
        compose.waitForIdle()
    }

    private fun openCredentialStage() {
        compose.activity.runOnUiThread {
            compose.activity.providerSettingsViewModel.applyPreset(PresetKind.OPENAI, "OpenAI")
            compose.activity.providerSettingsViewModel.reviewEndpoint()
        }
        compose.onNodeWithTag("provider_confirmed_host").assertExists()
        compose.onNodeWithTag("provider_api_key").assertExists()
    }

    private fun isSecure(): Boolean =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun hasAutofillDisabled(view: View): Boolean {
        if (view.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS) return true
        return view is ViewGroup && (0 until view.childCount).any { hasAutofillDisabled(view.getChildAt(it)) }
    }

    private fun assertInside(actual: Rect, expected: Rect) {
        assertTrue("$actual not inside $expected", actual.left >= expected.left && actual.top >= expected.top && actual.right <= expected.right && actual.bottom <= expected.bottom)
    }
}

private class InstrumentationProfiles : ProviderProfileRepository {
    private val profiles = mutableListOf<ProviderConfiguration>()
    private var selected: String? = null
    override suspend fun list() = profiles.toList()
    override suspend fun upsert(profile: ProviderConfiguration) {
        profiles.removeAll { it.id == profile.id }
        profiles += profile
    }
    override suspend fun delete(id: String) { profiles.removeAll { it.id == id } }
    override suspend fun select(id: String?) { selected = id }
    override suspend fun selectedId() = selected
}

private class InstrumentationCredentials : CredentialStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun put(alias: String, secret: String): CredentialResult<Unit> {
        values[alias] = secret
        return CredentialResult.Success(Unit)
    }
    override suspend fun read(alias: String): CredentialResult<String> =
        values[alias]?.let { CredentialResult.Success(it) }
            ?: CredentialResult.Failure(CredentialError.Unavailable)
    override suspend fun delete(alias: String): CredentialResult<Unit> {
        values.remove(alias)
        return CredentialResult.Success(Unit)
    }
}

private class InstrumentationJournal : CredentialTransactionJournalStore {
    private var value: CredentialTransactionJournal? = null
    override fun read(): CredentialResult<CredentialTransactionJournal?> = CredentialResult.Success(value)
    override fun write(journal: CredentialTransactionJournal): CredentialResult<Unit> {
        value = journal
        return CredentialResult.Success(Unit)
    }
    override fun clear(): CredentialResult<Unit> {
        value = null
        return CredentialResult.Success(Unit)
    }
}

private class InstrumentationProviderFactory(
    private val discovery: ModelDiscoveryResult,
) : ProviderFactory {
    override fun create(configuration: ProviderConfiguration): ModelProvider = object : ModelProvider {
        override val descriptor = ProviderDescriptor(
            configuration.type,
            configuration.displayName,
            configuration.capabilities,
        )
        override fun stream(request: ModelRequest): Flow<ModelEvent> = emptyFlow()
        override suspend fun listModels() = discovery
        override suspend fun validate(configuration: ProviderConfiguration) = ValidationResult.Valid
    }
}
