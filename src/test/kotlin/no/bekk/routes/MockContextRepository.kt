package no.bekk.routes

import no.bekk.database.ContextRepository
import no.bekk.database.DatabaseContext
import no.bekk.database.DatabaseContextMetrics
import no.bekk.database.DatabaseContextRequest

interface MockContextRepository : ContextRepository {
    override fun deleteContext(id: String): Boolean {
        TODO("Not yet implemented")
    }

    override fun getContext(id: String): DatabaseContext {
        TODO("Not yet implemented")
    }

    override fun getContextsByTeamId(teamId: String): List<DatabaseContext> {
        TODO("Not yet implemented")
    }

    override fun getContextsByFormId(formId: String): List<DatabaseContext> {
        TODO("Not yet implemented")
    }

    override fun getContextsByName(name: String): List<DatabaseContext> {
        TODO("Not yet implemented")
    }

    override fun insertContext(context: DatabaseContextRequest): DatabaseContext {
        TODO("Not yet implemented")
    }

    override fun changeTeam(contextId: String, newTeamId: String): Boolean {
        TODO("Not yet implemented")
    }

    override fun changeName(contextId: String, newName: String): Boolean {
        TODO("Not yet implemented")
    }

    override fun getContextByTeamIdAndFormId(teamId: String, formId: String): List<DatabaseContext> {
        TODO("Not yet implemented")
    }

    override fun getContextMetrics(): List<DatabaseContextMetrics> {
        TODO("Not yet implemented")
    }
}
