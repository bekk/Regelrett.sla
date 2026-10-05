package no.bekk.routes

import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.bekk.authentication.AuthService
import no.bekk.database.*
import no.bekk.exception.ConflictException
import no.bekk.model.internal.Question
import no.bekk.plugins.ErrorHandlers
import no.bekk.services.FormService
import no.bekk.util.RequestContext.getRequestInfo
import org.slf4j.LoggerFactory
import kotlin.collections.get

fun Route.contextRouting(
    authService: AuthService,
    answerRepository: AnswerRepository,
    contextRepository: ContextRepository,
    commentRepository: CommentRepository,
    formService: FormService,
) {
    val logger = LoggerFactory.getLogger("no.bekk.routes.ContextRouting")
    route("/contexts") {
        post {
            try {
                val contextRequestJson = call.receiveText()
                logger.info("Received POST /context request with body: $contextRequestJson")
                val contextRequest = Json.decodeFromString<DatabaseContextRequest>(contextRequestJson)

                if (!authService.hasTeamAccess(call, contextRequest.teamId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }

                val insertedContext = contextRepository.insertContext(contextRequest)

                val copyContext = contextRequest.copyContext
                if (copyContext != null) {
                    if (!authService.hasWriteContextAccess(call, copyContext)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@post
                    }
                    answerRepository.copyAnswersFromOtherContext(insertedContext.id, copyContext)

                    if (contextRequest.copyComments == "yes") {
                        commentRepository.copyCommentsFromOtherContext(insertedContext.id, copyContext)
                    }
                }
                call.respond(HttpStatusCode.Created, Json.encodeToString(insertedContext))
                return@post
            } catch (e: ConflictException) {
                ErrorHandlers.handleConflictException(call, e)
                return@post
            } catch (e: Exception) {
                logger.error("${call.getRequestInfo()} Unexpected error in POST /contexts", e)
                ErrorHandlers.handleGenericException(call, e)
                return@post
            }
        }

        get {
            val teamId = call.request.queryParameters["teamId"] ?: throw BadRequestException("Missing teamId parameter")
            val formId = call.request.queryParameters["formId"]
            val includeMetrics = call.request.queryParameters["includeMetrics"] == "true"
            logger.info("Received GET /contexts with teamId $teamId with formId $formId (includeMetrics=$includeMetrics)")
            if (!authService.hasAppReadAccess(call) && !authService.hasTeamAccess(call, teamId)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }

            if (formId != null) {
                val context = contextRepository.getContextByTeamIdAndFormId(teamId, formId)
                call.respond(HttpStatusCode.OK, Json.encodeToString(context))
                return@get
            } else {
                val contexts = contextRepository.getContextsByTeamId(teamId)
                if (includeMetrics) {
                    val latestAnswersByContextId = contexts.associate { ctx ->
                        ctx.id to answerRepository.getLatestAnswersByContextIdFromDatabase(ctx.id).associateBy { it.recordId }
                    }
                    val allMetrics = contexts.groupBy { it.formId }.flatMap { (formId, contextsForForm) ->
                        val questions = try {
                            formService.getFormProvider(formId).getForm().records
                        } catch (e: Exception) {
                            emptyList()
                        }
                        buildContextMetrics(contextsForForm, questions, latestAnswersByContextId)
                    }
                    call.respond(HttpStatusCode.OK, Json.encodeToString(allMetrics))
                } else {
                    call.respond(HttpStatusCode.OK, Json.encodeToString(contexts))
                    return@get
                }
            }
        }

        get("/name") {
            val name = call.request.queryParameters["name"] ?: throw BadRequestException("Missing name")
            val includeMetrics = call.request.queryParameters["includeMetrics"] == "true"
            logger.info("Received GET /name with $name (includeMetrics=$includeMetrics)")
            val contexts = contextRepository.getContextsByName(name)
            for (context in contexts) {
                if (!authService.hasContextAccess(call, context.id)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
            }
            if (includeMetrics) {
                val latestAnswersByContextId = contexts.associate { ctx ->
                    ctx.id to answerRepository.getLatestAnswersByContextIdFromDatabase(ctx.id).associateBy { it.recordId }
                }
                val allMetrics = contexts.groupBy { it.formId }.flatMap { (formId, contextsForForm) ->
                    val questions = try {
                        formService.getFormProvider(formId).getForm().records
                    } catch (e: Exception) {
                        emptyList()
                    }
                    buildContextMetrics(contextsForForm, questions, latestAnswersByContextId)
                }
                call.respond(HttpStatusCode.OK, Json.encodeToString(allMetrics))
            } else {
                call.respond(HttpStatusCode.OK, Json.encodeToString(contexts))
            }
            return@get
        }

        route("/{contextId}") {
            get {
                logger.info("Received GET /context with id: ${call.parameters["contextId"]}")
                val contextId = call.parameters["contextId"] ?: throw BadRequestException("Missing contextId")

                if (!authService.hasContextAccess(call, contextId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                val context = contextRepository.getContext(contextId)
                call.respond(HttpStatusCode.OK, Json.encodeToString(context))
                return@get
            }

            delete {
                logger.info("Received DELETE /context with id: ${call.parameters["contextId"]}")
                val contextId = call.parameters["contextId"] ?: throw BadRequestException("Missing contextId")
                if (!authService.hasWriteContextAccess(call, contextId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@delete
                }
                contextRepository.deleteContext(contextId)
                call.respondText("Context and its answers and comments were successfully deleted.")
            }

            get("/permissions") {
                logger.info("Received GET /context/permissions with context-id: ${call.parameters["contextId"]}")
                val contextId = call.parameters["contextId"] ?: throw BadRequestException("Missing contextId")

                val hasReadAccess = authService.hasReadContextAccess(call, contextId)
                val hasWriteAccess = authService.hasWriteContextAccess(call, contextId)

                call.respond(HttpStatusCode.OK, Json.encodeToString(ContextPremissionResponse(hasReadAccess, hasWriteAccess)))
                return@get
            }

            patch("/team") {
                try {
                    logger.info("Received PATCH /contexts with id: ${call.parameters["contextId"]}")
                    val contextId = call.parameters["contextId"] ?: throw BadRequestException("Missing contextId")

                    if (!authService.hasWriteContextAccess(call, contextId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@patch
                    }

                    val payload = call.receive<TeamUpdateRequest>()
                    val newTeam = when {
                        payload.teamId != null -> {
                            payload.teamId
                        }
                        payload.teamName != null -> {
                            authService.getTeamIdFromName(call, payload.teamName) ?: throw BadRequestException("TeamName: ${payload.teamName} not valid")
                        }
                        else -> {
                            throw BadRequestException("Request must contain either teamId or teamName")
                        }
                    }

                    val success = contextRepository.changeTeam(contextId, newTeam)
                    if (success) {
                        call.respond(HttpStatusCode.OK)
                        return@patch
                    } else {
                        call.respond(HttpStatusCode.InternalServerError)
                        return@patch
                    }
                } catch (e: BadRequestException) {
                    logger.error("Bad request: ${e.message}", e)
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad request")
                } catch (e: ConflictException) {
                    ErrorHandlers.handleConflictException(call, e)
                } catch (e: Exception) {
                    logger.error("Unexpected error when processing PATCH /contexts", e)
                    call.respond(HttpStatusCode.InternalServerError, "An unexpected error occurred.")
                }
            }
            patch("/answers") {
                try {
                    logger.info("Received PATCH /{contextId}/answers with id: ${call.parameters["contextId"]}")
                    val contextId = call.parameters["contextId"] ?: throw BadRequestException("Missing contextId")

                    val payload = call.receive<CopyContextRequest>()
                    val copyContextId = payload.copyContextId ?: throw BadRequestException("Missing copy contextId in request body")

                    if (!authService.hasWriteContextAccess(call, contextId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@patch
                    }
                    answerRepository.copyAnswersFromOtherContext(contextId, copyContextId)
                    call.respond(HttpStatusCode.OK)
                    return@patch
                } catch (e: BadRequestException) {
                    logger.error("Bad request: ${e.message}", e)
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad request")
                } catch (e: Exception) {
                    logger.error("Unexpected error when processing PATCH /contexts", e)
                    call.respond(HttpStatusCode.InternalServerError, "An unexpected error occurred.")
                }
            }

            patch("/comments") {
                try {
                    logger.info("Received PATCH /{contextId}/comments with id: ${call.parameters["contextId"]}")
                    val contextId = call.parameters["contextId"] ?: throw BadRequestException("Missing contextId")

                    val payload = call.receive<CopyContextRequest>()
                    val copyContextId = payload.copyContextId ?: throw BadRequestException("Missing copy contextId in request body")

                    if (!authService.hasWriteContextAccess(call, contextId) || !authService.hasWriteContextAccess(call, copyContextId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@patch
                    }
                    commentRepository.copyCommentsFromOtherContext(contextId, copyContextId)
                    call.respond(HttpStatusCode.OK)
                    return@patch
                } catch (e: BadRequestException) {
                    logger.error("Bad request: ${e.message}", e)
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad request")
                } catch (e: Exception) {
                    logger.error("Unexpected error when processing PATCH /contexts", e)
                    call.respond(HttpStatusCode.InternalServerError, "An unexpected error occurred.")
                }
            }

            patch("/name") {
                try {
                    logger.info("Received PATCH /{contextId}/name with id: ${call.parameters["contextId"]}")
                    val contextId = call.parameters["contextId"] ?: throw BadRequestException("Missing contextId")

                    if (!authService.hasWriteContextAccess(call, contextId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@patch
                    }

                    val payload = call.receive<NameUpdateRequest>()

                    contextRepository.changeName(contextId, payload.name)
                    call.respond(HttpStatusCode.OK)
                    return@patch
                } catch (e: BadRequestException) {
                    logger.error("Bad request: ${e.message}", e)
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad request")
                } catch (e: ConflictException) {
                    ErrorHandlers.handleConflictException(call, e)
                } catch (e: Exception) {
                    logger.error("Unexpected error when processing PATCH /contexts", e)
                    call.respond(HttpStatusCode.InternalServerError, "An unexpected error occurred.")
                }
            }
        }
    }
}

fun buildContextMetrics(
    contexts: List<DatabaseContext>,
    questions: List<Question>,
    latestAnswersByContextId: Map<String, Map<String, DatabaseAnswer>>,
): List<DatabaseContextWithMetrics> {
    val today = java.time.LocalDate.now()
    return contexts.map { ctx ->
        val latestAnswers = latestAnswersByContextId[ctx.id] ?: emptyMap()
        val expiredCount = questions.count { q ->
            val expiry = q.metadata.answerMetadata.expiry ?: return@count false
            val answer = latestAnswers[q.recordId] ?: return@count false
            val updatedDate = java.time.LocalDate.parse(answer.updated.substring(0, 10))
            updatedDate.plusWeeks(expiry.toLong()).isBefore(today)
        }
        val answeredCount = questions.count { q -> latestAnswers.containsKey(q.recordId) }
        DatabaseContextWithMetrics(
            id = ctx.id,
            teamId = ctx.teamId,
            formId = ctx.formId,
            name = ctx.name,
            expiredCount = expiredCount,
            answeredCount = answeredCount,
            totalCount = questions.size,
        )
    }
}

@Serializable
data class TeamUpdateRequest(val teamName: String? = null, val teamId: String? = null)

@Serializable
data class NameUpdateRequest(val name: String)

@Serializable
data class CopyContextRequest(val copyContextId: String?)

@Serializable
data class ContextPremissionResponse(
    val canRead: Boolean,
    val canWrite: Boolean,
)
