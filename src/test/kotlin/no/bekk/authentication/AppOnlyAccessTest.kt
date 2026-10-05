package no.bekk.authentication

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import no.bekk.MockMicrosoftService
import no.bekk.TestUtils.testModule
import no.bekk.configuration.OAuthConfig
import no.bekk.database.DatabaseAnswer
import no.bekk.database.DatabaseComment
import no.bekk.database.DatabaseContext
import no.bekk.database.DatabaseReadGrant
import no.bekk.database.DatabaseReadGrantRequest
import no.bekk.database.ReadGrantRepository
import no.bekk.domain.MicrosoftGraphGroup
import no.bekk.domain.MicrosoftGraphUser
import no.bekk.routes.MockAnswerRepository
import no.bekk.routes.MockCommentRepository
import no.bekk.routes.MockContextRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppOnlyAccessTest {
    private val oAuthConfig = OAuthConfig("", "", "", "", "", "", "", "", "", "", appReadRole = "SLO.Read")

    private val readGrantRepository = object : ReadGrantRepository {
        override fun getReadGrantsByContext(contextId: String): List<DatabaseReadGrant> = emptyList()
        override fun getReadGrantsByUserId(userId: String): List<DatabaseReadGrant> = TODO("Not yet implemented")
        override fun insertReadGrantOnContext(contextId: String, readGrant: DatabaseReadGrantRequest): DatabaseReadGrant = TODO("Not yet implemented")
        override fun revokeReadGrantOnContext(readGrantId: String, contextId: String): Boolean = TODO("Not yet implemented")
    }

    private val contextRepository = object : MockContextRepository {
        override fun getContextsByTeamId(teamId: String) = emptyList<DatabaseContext>()
        override fun getContext(id: String) = DatabaseContext(id, "my-team", "formId", "name")
    }

    private val answerRepository = object : MockAnswerRepository {
        override fun getLatestAnswersByContextIdFromDatabase(contextId: String) = emptyList<DatabaseAnswer>()
    }

    private val commentRepository = object : MockCommentRepository {
        override fun getCommentsByContextIdFromDatabase(contextId: String) = emptyList<DatabaseComment>()
    }

    private class RecordingMicrosoftService(private val groupIds: List<String>) : MockMicrosoftService {
        var oboCalled = false

        override suspend fun requestTokenOnBehalfOf(jwtToken: String?): String {
            oboCalled = true
            return "obo-token"
        }

        override suspend fun fetchGroups(bearerToken: String, filter: String?): List<MicrosoftGraphGroup> = groupIds.map { MicrosoftGraphGroup(it, it) }

        override suspend fun fetchCurrentUser(bearerToken: String) = MicrosoftGraphUser("test-id", "Test", null)
    }

    private fun token(
        scp: String? = null,
        roles: List<String>? = null,
        idtyp: String? = if (scp == null) "app" else null,
    ): String = JWT.create()
        .withAudience("test-audience")
        .withIssuer("test-issuer")
        .withClaim("oid", "test-id")
        .apply { if (scp != null) withClaim("scp", scp) }
        .apply { if (roles != null) withClaim("roles", roles) }
        .apply { if (idtyp != null) withClaim("idtyp", idtyp) }
        .sign(Algorithm.HMAC256("test-secret"))

    private fun ApplicationTestBuilder.setup(microsoftService: RecordingMicrosoftService, config: OAuthConfig = oAuthConfig) {
        application {
            testModule(
                contextRepository = contextRepository,
                answerRepository = answerRepository,
                commentRepository = commentRepository,
                authService = AuthServiceImpl(microsoftService, contextRepository, readGrantRepository, config),
            )
        }
    }

    @Test
    fun `app-only token with SLO Read gets access to any team without OBO`() = testApplication {
        val microsoftService = RecordingMicrosoftService(groupIds = emptyList())
        setup(microsoftService)

        val response = client.get("/api/contexts?teamId=any-team") {
            header(HttpHeaders.Authorization, "Bearer ${token(roles = listOf("SLO.Read"))}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(microsoftService.oboCalled)
    }

    @Test
    fun `app-only token without SLO Read is forbidden`() = testApplication {
        setup(RecordingMicrosoftService(groupIds = emptyList()))

        val response = client.get("/api/contexts?teamId=any-team") {
            header(HttpHeaders.Authorization, "Bearer ${token(roles = listOf("Something.Else"))}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `user token with SLO Read role does not bypass group check`() = testApplication {
        val microsoftService = RecordingMicrosoftService(groupIds = emptyList())
        setup(microsoftService)

        val response = client.get("/api/contexts?teamId=any-team") {
            header(HttpHeaders.Authorization, "Bearer ${token(scp = "User.Read", roles = listOf("SLO.Read"))}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(microsoftService.oboCalled)
    }

    @Test
    fun `user token in team group still gets access`() = testApplication {
        setup(RecordingMicrosoftService(groupIds = listOf("my-team")))

        val response = client.get("/api/contexts?teamId=my-team") {
            header(HttpHeaders.Authorization, "Bearer ${token(scp = "User.Read")}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `app-only token with SLO Read can read answers and comments without OBO`() = testApplication {
        val microsoftService = RecordingMicrosoftService(groupIds = emptyList())
        setup(microsoftService)
        val appToken = token(roles = listOf("SLO.Read"))

        val answers = client.get("/api/answers?contextId=any-context") {
            header(HttpHeaders.Authorization, "Bearer $appToken")
        }
        val comments = client.get("/api/comments?contextId=any-context") {
            header(HttpHeaders.Authorization, "Bearer $appToken")
        }

        assertEquals(HttpStatusCode.OK, answers.status)
        assertEquals(HttpStatusCode.OK, comments.status)
        assertFalse(microsoftService.oboCalled)
    }

    @Test
    fun `app-only token without SLO Read cannot read answers or comments`() = testApplication {
        setup(RecordingMicrosoftService(groupIds = emptyList()))
        val appToken = token(roles = listOf("Something.Else"))

        val answers = client.get("/api/answers?contextId=any-context") {
            header(HttpHeaders.Authorization, "Bearer $appToken")
        }
        val comments = client.get("/api/comments?contextId=any-context") {
            header(HttpHeaders.Authorization, "Bearer $appToken")
        }

        assertEquals(HttpStatusCode.Forbidden, answers.status)
        assertEquals(HttpStatusCode.Forbidden, comments.status)
    }

    @Test
    fun `user token with SLO Read role does not bypass context access check`() = testApplication {
        setup(RecordingMicrosoftService(groupIds = emptyList()))

        val response = client.get("/api/answers?contextId=any-context") {
            header(HttpHeaders.Authorization, "Bearer ${token(scp = "User.Read", roles = listOf("SLO.Read"))}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `app-only access is disabled when app_read_role is not configured`() = testApplication {
        setup(RecordingMicrosoftService(groupIds = emptyList()), config = oAuthConfig.copy(appReadRole = ""))

        val response = client.get("/api/contexts?teamId=any-team") {
            header(HttpHeaders.Authorization, "Bearer ${token(roles = listOf("SLO.Read"))}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `token without idtyp app is not treated as app-only`() = testApplication {
        val microsoftService = RecordingMicrosoftService(groupIds = emptyList())
        setup(microsoftService)

        val response = client.get("/api/contexts?teamId=any-team") {
            header(HttpHeaders.Authorization, "Bearer ${token(roles = listOf("SLO.Read"), idtyp = null)}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(microsoftService.oboCalled)
    }
}
