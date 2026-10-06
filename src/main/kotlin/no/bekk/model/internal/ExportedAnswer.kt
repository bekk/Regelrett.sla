package no.bekk.model.internal

import kotlinx.serialization.Serializable

@Serializable
data class ExportedAnswer(
    val questionId: String,
    val description: String,
    val answer: String?,
    val answerType: String,
    val answerUnit: String?,
)

@Serializable
data class ExportedTeamFunction(
    val teamId: String,
    val formName: String,
    val functionName: String,
    val answers: List<ExportedAnswer>,
)
