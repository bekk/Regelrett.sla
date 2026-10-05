package no.bekk.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import no.bekk.authentication.UserSession
import no.bekk.configuration.Config
import no.bekk.di.Dependencies
import no.bekk.model.internal.ContextMetrics
import no.bekk.routes.*

fun Application.configureRouting(
    config: Config,
    dependencies: Dependencies,
): RoutingRoot = routing {
    get("/health") {
        call.respondText("Health OK", ContentType.Text.Plain)
    }

    get("/api/schemas") {
        val schemas = dependencies.formService.getFormProviders().map {
            it.getSchema()
        }
        call.respond(HttpStatusCode.OK, schemas)
    }

    get("/contextMetrics") {
        val forms = dependencies.formService.getFormProviders()
            .map { it.getForm() }
            .associateBy { it.id }
        val contextMetrics = dependencies.contextRepository.getContextMetrics().mapNotNull { ctx ->
            val form = forms[ctx.formId] ?: return@mapNotNull null

            ContextMetrics(
                teamId = ctx.teamId,
                formName = form.name,
                contextName = ctx.name,
                answerCount = ctx.answerCount,
                questionCount = form.records.size,
                oldestUpdate = ctx.oldestUpdate,
            )
        }
        call.respond(HttpStatusCode.OK, contextMetrics)
    }

    authenticate("auth-oauth-azure") {
        get("/login") {
            // Redirects to 'authorizeUrl' automatically
        }

        get("/callback") {
            val currentPrincipal: OAuthAccessTokenResponse.OAuth2? = call.principal()

            currentPrincipal?.let { principal ->
                principal.state?.let { state ->
                    call.sessions.set(UserSession(state, principal.accessToken, System.currentTimeMillis() + principal.expiresIn * 1000))
                    dependencies.redirects.r.remove(state)?.let { redirect ->
                        call.respondRedirect(redirect)
                        return@get
                    }
                }
            }
            call.respondRedirect("/")
        }
    }

    authenticate("auth-session") {
        webRouting(config.frontendDevServer, config.homePath)

        get("/logout") {
            call.sessions.clear<UserSession>()
            call.respondRedirect("/")
            // TODO: logout auth provider
        }
    }

    authenticate("auth-jwt") {
        route("/api") {
            answerRouting(dependencies.authService, dependencies.answerRepository)
            commentRouting(dependencies.authService, dependencies.commentRepository)
            contextRouting(dependencies.authService, dependencies.answerRepository, dependencies.contextRepository, dependencies.commentRepository, dependencies.formService)
            m2mSloRouting(dependencies.formService, dependencies.contextRepository, dependencies.answerRepository, config.githubActionsOidc)
            formRouting(dependencies.formService)
            readGrantRouting(dependencies.authService, dependencies.readGrantRepository)
            userInfoRouting(dependencies.authService)
            uploadCSVRouting(dependencies.authService, dependencies.formService, dependencies.database)
        }
    }

    airTableWebhookRouting(dependencies.formService)
}
