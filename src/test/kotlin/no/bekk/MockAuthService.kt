package no.bekk

import io.ktor.server.application.*
import no.bekk.authentication.AuthService
import no.bekk.domain.MicrosoftGraphGroup
import no.bekk.domain.MicrosoftGraphUser

interface MockAuthService : AuthService {
    override suspend fun getGroupsOrEmptyList(call: ApplicationCall, filter: String?): List<MicrosoftGraphGroup> = TODO("Not yet implemented")
    override suspend fun getCurrentUser(call: ApplicationCall): MicrosoftGraphUser = TODO("Not yet implemented")
    override suspend fun getUserByUserId(call: ApplicationCall, userId: String): MicrosoftGraphUser = TODO("Not yet implemented")
    override suspend fun searchUsers(call: ApplicationCall, usernameQuery: String, limit: Int): List<MicrosoftGraphUser> = TODO("Not yet implemented")
    override suspend fun hasTeamAccess(call: ApplicationCall, teamId: String?): Boolean = TODO("Not yet implemented")
    override fun hasAppReadAccess(call: ApplicationCall): Boolean = false
    override suspend fun hasContextAccess(call: ApplicationCall, contextId: String): Boolean = TODO("Not yet implemented")
    override suspend fun hasWriteContextAccess(call: ApplicationCall, contextId: String): Boolean = TODO("Not yet implemented")
    override suspend fun hasReadContextAccess(call: ApplicationCall, contextId: String): Boolean = TODO("Not yet implemented")
    override suspend fun hasSuperUserAccess(call: ApplicationCall): Boolean = TODO("Not yet implemented")
    override suspend fun hasReportingUserAccess(call: ApplicationCall): Boolean = TODO("Not yet implemented")
    override suspend fun getTeamIdFromName(call: ApplicationCall, teamName: String): String? = TODO("Not yet implemented")
    override suspend fun getTeamNameFromId(call: ApplicationCall, teamId: String): String? = TODO("Not yet implemented")
}
