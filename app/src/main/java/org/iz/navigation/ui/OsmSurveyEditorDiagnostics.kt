package org.iz.navigation.ui

import org.iz.navigation.BuildConfig
import org.iz.navigation.data.SurveyAction
import java.util.UUID

/** Private instance callback; normal callers never supply it. No storage, logging or activation API. */
internal fun interface OsmSurveyEditorDiagnosticObserver {
    fun record(event: OsmSurveyEditorDiagnosticEvent)
}

internal enum class SurveyDiagnosticStage {
    EDITOR_MOUNT, EDITOR_DISPOSE, FIELD_MOUNT, FIELD_DISPOSE, ADVANCED_CALLBACK_ENTER,
    UPDATE_ENTER, REJECT_FIELD_LIMIT, REJECT_ENCODING, ACCEPT, ACCEPT_NO_DATA_CHANGE,
    ERROR_WRITE, COMPOSITION_COMMIT, WARNING_ITEM_COMMIT,
}
internal enum class SurveyDiagnosticReason {
    FIELD_LIMIT, ENCODING, ACCEPTED_UPDATE, TARGET_READ, ACCOUNT_CHANGED, TARGET_REFRESH,
    FORM_LIMIT, REVIEW_FAILED,
}
internal enum class SurveyDiagnosticError { NONE, FIELD_LIMIT, OTHER }

internal fun surveyDiagnosticError(value: String?): SurveyDiagnosticError = when (value) {
    null -> SurveyDiagnosticError.NONE
    "Taslak alanı en fazla 8192 karakter olabilir. Son kabul edilen metin korunuyor." -> SurveyDiagnosticError.FIELD_LIMIT
    else -> SurveyDiagnosticError.OTHER
}

internal data class OsmSurveyEditorDiagnosticState(
    val hoursChars: Int?, val wheelchairChars: Int?, val action: SurveyAction?,
    val simple: Boolean, val locked: Boolean,
    val error: SurveyDiagnosticError, val displayedError: SurveyDiagnosticError,
)

internal data class OsmSurveyEditorDiagnosticEvent(
    val editorInstance: String, val sequence: Int, val stage: SurveyDiagnosticStage,
    val fieldInstance: String?, val incomingHoursChars: Int?,
    val before: OsmSurveyEditorDiagnosticState?, val state: OsmSurveyEditorDiagnosticState,
    val reason: SurveyDiagnosticReason?, val payloadChanged: Boolean?,
    val composedWarning: SurveyDiagnosticError?,
)

/** The QA namespace is also pinned by the test's exact expected-package admission. */
internal fun osmSurveyDiagnosticAllowed(): Boolean = BuildConfig.DEBUG &&
    BuildConfig.APPLICATION_ID.startsWith("org.iz.navigation.osmsurvey") &&
    BuildConfig.APPLICATION_ID.substringAfter("org.iz.navigation.osmsurvey").contains("qa")

internal class OsmSurveyEditorDiagnostics(private val observer: OsmSurveyEditorDiagnosticObserver) {
    val editorInstance: String = UUID.randomUUID().toString()
    private var sequence = 0

    fun record(stage: SurveyDiagnosticStage, state: OsmSurveyEditorDiagnosticState,
        field: String? = null, incoming: Int? = null, before: OsmSurveyEditorDiagnosticState? = null,
        reason: SurveyDiagnosticReason? = null, changed: Boolean? = null,
        composedWarning: SurveyDiagnosticError? = null) {
        // The test collector is passive/non-throwing; a failed diagnostic must never change validation.
        runCatching { observer.record(OsmSurveyEditorDiagnosticEvent(editorInstance, ++sequence, stage,
            field, incoming, before, state, reason, changed, composedWarning)) }
    }
}
