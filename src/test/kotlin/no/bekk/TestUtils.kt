package no.bekk

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import net.mamoe.yamlkt.Yaml
import no.bekk.authentication.AuthService
import no.bekk.authentication.UserSession
import no.bekk.configuration.*
import no.bekk.configuration.Database
import no.bekk.configuration.ServerConfig
import no.bekk.database.*
import no.bekk.di.Dependencies
import no.bekk.di.Redirects
import no.bekk.plugins.configureRouting
import no.bekk.services.FormService
import no.bekk.services.provisioning.ProvisioningService

object TestUtils {
    fun Application.testModule(
        testDatabase: Database = object : MockDatabase {},
        formService: FormService = object : MockFormService {},
        authService: AuthService = object : MockAuthService {},
        provisioningService: ProvisioningService = object : MockProvisioningService {},
        answerRepository: AnswerRepository = AnswerRepositoryImpl(testDatabase),
        commentRepository: CommentRepository = CommentRepositoryImpl(testDatabase),
        contextRepository: ContextRepository = ContextRepositoryImpl(testDatabase),
        readGrantRepository: ReadGrantRepository = ReadGrantRepositoryImpl(testDatabase),
    ) {
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
                expectedRepository = "bekk/grafana-sla",
                expectedRef = null,
            ),
            raw = YamlConfig(Yaml.decodeYamlMapFromString("value: null")),
        )

        install(Authentication) {
            jwt("auth-jwt") {
                realm = "test"
                verifier(
                    JWT
                        .require(Algorithm.HMAC256("test-secret"))
                        .withAudience("test-audience")
                        .withIssuer("test-issuer")
                        .build(),
                )
                validate { credentials -> JWTPrincipal(credentials.payload) }
            }

            basic("auth-oauth-azure") {
                validate { credentials ->
                    if (credentials.name == "regelrett" && credentials.password == "pwd") {
                        UserIdPrincipal(credentials.name)
                    } else {
                        null
                    }
                }
            }

            session<UserSession>("auth-session") {
                validate { session ->
                    if (session.state != "" && session.token != "") {
                        session
                    } else {
                        null
                    }
                }
            }
        }

        install(ContentNegotiation) {
            json()
        }

        configureRouting(
            exampleConfig,
            Dependencies(
                testDatabase,
                formService,
                answerRepository,
                provisioningService,
                commentRepository,
                contextRepository,
                readGrantRepository,
                authService,
                HttpClient(CIO),
                Redirects(mutableMapOf()),
            ),
        )
    }

    fun generateTestToken(): String = JWT.create()
        .withAudience("test-audience")
        .withIssuer("test-issuer")
        .withClaim("oid", "test-user-id")
        .sign(Algorithm.HMAC256("test-secret"))
}
