package no.bekk.model.internal

import kotlinx.serialization.Serializable

@Serializable
data class ExportedAnswer(
    val teamId: String,
    val formId: String,
    val formName: String,
    val contextId: String,
    val contextName: String,
    val recordId: String,
    val questionId: String,
    val answer: String?,
    val answerType: String,
    val answerUnit: String?,
    val updated: String,
    val actor: String,
)
