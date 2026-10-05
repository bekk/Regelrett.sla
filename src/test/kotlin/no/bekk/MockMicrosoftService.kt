package no.bekk

import no.bekk.domain.MicrosoftGraphGroup
import no.bekk.domain.MicrosoftGraphUser
import no.bekk.services.MicrosoftService

interface MockMicrosoftService : MicrosoftService {
    override suspend fun requestTokenOnBehalfOf(jwtToken: String?): String = TODO("Not yet implemented")
    override suspend fun fetchGroups(bearerToken: String, filter: String?): List<MicrosoftGraphGroup> = TODO("Not yet implemented")
    override suspend fun fetchGroupById(bearerToken: String, groupId: String): MicrosoftGraphGroup? = TODO("Not yet implemented")
    override suspend fun fetchGroupByDisplayName(bearerToken: String, displayName: String): MicrosoftGraphGroup? = TODO("Not yet implemented")
    override suspend fun fetchCurrentUser(bearerToken: String): MicrosoftGraphUser = TODO("Not yet implemented")
    override suspend fun fetchUserByUserId(bearerToken: String, userId: String): MicrosoftGraphUser = TODO("Not yet implemented")
    override suspend fun searchUsersbyName(bearerToken: String, query: String, limit: Int): List<MicrosoftGraphUser> = TODO("Not yet implemented")
}
