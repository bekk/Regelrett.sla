package no.bekk.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.bekk.authentication.GithubActionsOidcVerifier
import no.bekk.configuration.GithubActionsOidcConfig
import no.bekk.database.AnswerRepository
import no.bekk.database.ContextRepository
import no.bekk.exception.AuthenticationException
import no.bekk.model.internal.ExportedAnswer
import no.bekk.plugins.ErrorHandlers
import no.bekk.services.FormService
import no.bekk.util.RequestContext.getRequestInfo
import org.slf4j.LoggerFactory

private val EXPORTABLE_FORM_NAMES = setOf("Sikkerhetskontroller", "Driftskontinuitet")
private const val SLO_APP_ROLE = "SLO.Read"

fun Route.m2mSloRouting(
    formService: FormService,
    contextRepository: ContextRepository,
    answerRepository: AnswerRepository,
    githubActionsOidcConfig: GithubActionsOidcConfig,
) {
    val logger = LoggerFactory.getLogger("no.bekk.routes.M2mSloRouting")
    val githubActionsOidcVerifier = GithubActionsOidcVerifier(githubActionsOidcConfig)

    route("/m2m") {
        get("/slo") {
            try {
                val githubToken = call.request.headers["X-GitHub-OIDC-Token"]
                if (githubToken == null) {
                    logger.warn("${call.getRequestInfo()} SLO export denied - missing X-GitHub-OIDC-Token header")
                    call.respond(HttpStatusCode.Unauthorized)
                    return@get
                }

                try {
                    githubActionsOidcVerifier.verify(githubToken)
                } catch (e: AuthenticationException) {
                    logger.warn("${call.getRequestInfo()} SLO export denied - GitHub OIDC token verification failed: ${e.message}")
                    call.respond(HttpStatusCode.Unauthorized)
                    return@get
                }

                val principal = call.principal<JWTPrincipal>()
                val roles = principal?.payload?.getClaim("roles")?.asList(String::class.java) ?: emptyList()

                if (SLO_APP_ROLE !in roles) {
                    logger.warn("${call.getRequestInfo()} SLO export denied - token missing required app role: $SLO_APP_ROLE")
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                logger.info("${call.getRequestInfo()} Received GET /m2m/slo")

                val exportedAnswers = formService.getFormProviders()
                    .filter { it.name in EXPORTABLE_FORM_NAMES }
                    .flatMap { provider ->
                        contextRepository.getContextsByFormId(provider.id).flatMap { context ->
                            answerRepository.getLatestAnswersByContextIdFromDatabase(context.id).map { answer ->
                                ExportedAnswer(
                                    teamId = context.teamId,
                                    formId = provider.id,
                                    formName = provider.name,
                                    contextId = context.id,
                                    contextName = context.name,
                                    recordId = answer.recordId,
                                    questionId = answer.questionId,
                                    answer = answer.answer,
                                    answerType = answer.answerType,
                                    answerUnit = answer.answerUnit,
                                    updated = answer.updated,
                                    actor = answer.actor,
                                )
                            }
                        }
                    }

                logger.info("${call.getRequestInfo()} Returning ${exportedAnswers.size} exported answers")
                call.respond(HttpStatusCode.OK, exportedAnswers)
            } catch (e: Exception) {
                logger.error("${call.getRequestInfo()} Unexpected error in GET /m2m/slo", e)
                ErrorHandlers.handleGenericException(call, e)
            }
        }
    }
}
