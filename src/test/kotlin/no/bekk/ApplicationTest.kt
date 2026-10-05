package no.bekk

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import net.mamoe.yamlkt.Yaml
import no.bekk.configuration.*
import no.bekk.database.AnswerRepositoryImpl
import no.bekk.database.CommentRepositoryImpl
import no.bekk.database.ContextRepositoryImpl
import no.bekk.database.ReadGrantRepositoryImpl
import no.bekk.di.Dependencies
import no.bekk.di.Redirects
import no.bekk.plugins.configureRouting
import no.bekk.services.FormServiceImpl
import no.bekk.services.provisioning.provideProvisioningService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.fail
import java.sql.Connection
import kotlin.collections.emptyList

class ApplicationTest {
    val exampleConfig = Config(
        homePath = "",
        mode = "development",
        paths = PathsConfig(""),
        microsoftGraph = MicrosoftGraphConfig("", "", ""),
        oAuth = OAuthConfig("https://test.com", "test", "", "", "", "", "", "", "", ""),
        server = ServerConfig("", "", "", 0, false, false, emptyList(), 4096, 8192, 8192),
        database = DatabaseConfig("", "", "", "", ""),
        answerHistoryCleanup = AnswerHistoryCleanupConfig(""),
        frontendDevServer = FrontendDevServerConfig("", 0, "", ""),
        githubActionsOidc = GithubActionsOidcConfig(
            issuer = "https://token.actions.githubusercontent.com",
            jwksUrl = "https://token.actions.githubusercontent.com/.well-known/jwks",
            audience = "test-audience",
            expectedRepository = "bekk/Regelrett.sla",
            expectedRef = null,
        ),
        raw = YamlConfig(Yaml.decodeYamlMapFromString("value: null")),
    )
    private val mockDatabase = object : Database {
        override fun getConnection(): Connection {
            TODO("Not yet implemented")
        }
    }

    @Test
    fun `Verify that authentication is enabled on non-public endpoints`() = testApplication {
        application {
            configureAPILayer(
                exampleConfig,
                Dependencies(
                    mockDatabase,
                    FormServiceImpl(),
                    AnswerRepositoryImpl(mockDatabase),
                    provideProvisioningService(exampleConfig, FormServiceImpl()),
                    CommentRepositoryImpl(mockDatabase),
                    ContextRepositoryImpl(mockDatabase),
                    ReadGrantRepositoryImpl(mockDatabase),
                    object : MockAuthService {},
                    HttpClient(CIO),
                    Redirects(mutableMapOf()),
                ),
            )

            val routingRoot = configureRouting(
                exampleConfig,
                Dependencies(
                    mockDatabase,
                    FormServiceImpl(),
                    AnswerRepositoryImpl(mockDatabase),
                    provideProvisioningService(exampleConfig, FormServiceImpl()),
                    CommentRepositoryImpl(mockDatabase),
                    ContextRepositoryImpl(mockDatabase),
                    ReadGrantRepositoryImpl(mockDatabase),
                    object : MockAuthService {},
                    HttpClient(CIO),
                    Redirects(mutableMapOf()),
                ),
            )

            // Get all registered routes and filter out those that match any of the public endpoint regex patterns

            val nonPublicRoutes = routingRoot.getAllRoutes().filter { route ->
                route.toString().startsWith("/api") && !route.toString().contains("schemas")
            }

            assertAll(
                "Authentication in routes",
                nonPublicRoutes.map { route ->
                    // The `assertionForRoute@` is a label to enable us to return from the function if we find
                    // the Authenticate plugin.
                    assertionForRoute@{
                        var currRoute: RoutingNode? = route
                        // The Authenticate plugin that we are looking for is possibly defined earlier in
                        // the route hierarchy, so we traverse upwards via the parent property.
                        while (currRoute != null) {
                            // Checks if the Authenticate plugin is enabled in the current routes pipeline
                            if (currRoute.items.any { it.name == "Authenticate" }) {
                                return@assertionForRoute
                            }
                            currRoute = currRoute.parent
                        }

                        fail("$route does not have authentication enabled")
                    }
                },
            )
        }
    }

    @Test
    fun `Verify that CORS is enabled`() = testApplication {
        application {
            val formService = FormServiceImpl()
            configureAPILayer(
                exampleConfig.copy(server = exampleConfig.server.copy(allowedOrigins = listOf("test.com"))),

                Dependencies(
                    mockDatabase,
                    formService,
                    AnswerRepositoryImpl(mockDatabase),
                    provideProvisioningService(exampleConfig, formService),
                    CommentRepositoryImpl(mockDatabase),
                    ContextRepositoryImpl(mockDatabase),
                    ReadGrantRepositoryImpl(mockDatabase),
                    object : MockAuthService {},
                    HttpClient(CIO),
                    Redirects(mutableMapOf()),
                ),
            )
        }

        val failedCors = client.get("/health") {
            header(HttpHeaders.Origin, "https://test1234.com")
        }

        assertEquals(HttpStatusCode.Forbidden, failedCors.status)

        val successCors = client.get("/health") {
            header(HttpHeaders.Origin, "https://test.com")
        }

        assertEquals(HttpStatusCode.OK, successCors.status)
    }
}
