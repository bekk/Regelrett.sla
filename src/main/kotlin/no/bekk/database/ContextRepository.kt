package no.bekk.database

import io.ktor.server.plugins.*
import no.bekk.configuration.Database
import no.bekk.exception.ConflictException
import no.bekk.exception.DatabaseException
import no.bekk.exception.NotFoundException
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.*

interface ContextRepository {
    fun insertContext(context: DatabaseContextRequest): DatabaseContext
    fun getContextsByTeamId(teamId: String): List<DatabaseContext>
    fun getContextsByFormId(formId: String): List<DatabaseContext>
    fun getContextByTeamIdAndFormId(teamId: String, formId: String): List<DatabaseContext>
    fun getContext(id: String): DatabaseContext
    fun deleteContext(id: String): Boolean
    fun changeTeam(contextId: String, newTeamId: String): Boolean
    fun changeName(contextId: String, newName: String): Boolean
    fun getContextMetrics(): List<DatabaseContextMetrics>
    fun getContextsByName(name: String): List<DatabaseContext>
}

class ContextRepositoryImpl(private val database: Database) : ContextRepository {
    private val logger = LoggerFactory.getLogger(ContextRepositoryImpl::class.java)
    override fun insertContext(context: DatabaseContextRequest): DatabaseContext {
        logger.debug("Inserting context: {}", context)
        val sqlStatement =
            "INSERT INTO contexts (team_id, form_id, name) VALUES(?, ?, ?) returning *"

        try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatement).use { statement ->
                    statement.setString(1, context.teamId)
                    statement.setString(2, context.formId)
                    statement.setString(3, context.name)

                    val result = statement.executeQuery()
                    if (result.next()) {
                        logger.debug("Successfully inserted context into database")
                        return DatabaseContext(
                            id = result.getString("id"),
                            teamId = result.getString("team_id"),
                            formId = result.getString("form_id"),
                            name = result.getString("name"),
                        )
                    } else {
                        throw RuntimeException("Error inserting context into database")
                    }
                }
            }
        } catch (e: SQLException) {
            if (e.sqlState == "23505") { // PostgreSQL unique_violation
                logger.warn("Unique constraint violation when inserting context: ${e.message}")
                throw ConflictException("A context with the same team_id, form_id and name already exists.")
            } else {
                logger.error("Database error when inserting context: ${e.message}", e)
                throw DatabaseException("Failed to insert context", "insertContext", e)
            }
        }
    }

    override fun getContextsByTeamId(teamId: String): List<DatabaseContext> {
        logger.debug("Fetching contexts for team: $teamId")
        val sqlStatement = "SELECT * FROM contexts WHERE team_id = ?"

        return try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatement).use { statement ->
                    statement.setString(1, teamId)

                    val result = statement.executeQuery()

                    buildList {
                        while (result.next()) {
                            add(
                                DatabaseContext(
                                    id = result.getString("id"),
                                    teamId = result.getString("team_id"),
                                    formId = result.getString("form_id"),
                                    name = result.getString("name"),
                                ),
                            )
                        }
                    }.also {
                        logger.debug("Successfully fetched contexts for team: $teamId")
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Error fetching contexts for team: $teamId", e)
            throw RuntimeException("Error fetching contexts for team: $teamId from database", e)
        }
    }

    override fun getContextsByFormId(formId: String): List<DatabaseContext> {
        logger.debug("Fetching contexts for form: $formId")
        val sqlStatement = "SELECT * FROM contexts WHERE form_id = ?"

        return try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatement).use { statement ->
                    statement.setString(1, formId)

                    val result = statement.executeQuery()

                    buildList {
                        while (result.next()) {
                            add(
                                DatabaseContext(
                                    id = result.getString("id"),
                                    teamId = result.getString("team_id"),
                                    formId = result.getString("form_id"),
                                    name = result.getString("name"),
                                ),
                            )
                        }
                    }.also {
                        logger.debug("Successfully fetched contexts for form: $formId")
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Error fetching contexts for form: $formId", e)
            throw RuntimeException("Error fetching contexts for form: $formId from database", e)
        }
    }

    override fun getContextsByName(name: String): List<DatabaseContext> {
        logger.debug("Fetching contexts for: $name")
        val sqlStatement = "SELECT * FROM contexts WHERE name = ?"

        return try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatement).use { statement ->
                    statement.setString(1, name)

                    val result = statement.executeQuery()

                    buildList {
                        while (result.next()) {
                            add(
                                DatabaseContext(
                                    id = result.getString("id"),
                                    teamId = result.getString("team_id"),
                                    formId = result.getString("form_id"),
                                    name = result.getString("name"),
                                ),
                            )
                        }
                    }.also {
                        logger.debug("Successfully fetched contexts for: $name")
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Error fetching contexts for: $name", e)
            throw RuntimeException("Error fetching contexts for: $name from database", e)
        }
    }

    override fun getContextByTeamIdAndFormId(teamId: String, formId: String): List<DatabaseContext> {
        logger.debug("Fetching contexts for team: $teamId and form: $formId")
        val sqlStatement = "SELECT * FROM contexts WHERE team_id = ? AND form_id = ?"

        return try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatement).use { statement ->
                    statement.setString(1, teamId)
                    statement.setString(2, formId)

                    val result = statement.executeQuery()

                    buildList {
                        while (result.next()) {
                            add(
                                DatabaseContext(
                                    id = result.getString("id"),
                                    teamId = result.getString("team_id"),
                                    formId = result.getString("form_id"),
                                    name = result.getString("name"),
                                ),
                            )
                        }
                    }
                }.also {
                    logger.debug("Successfully fetched contexts for team: $teamId and form: $formId")
                }
            }
        } catch (e: SQLException) {
            logger.error("Database error fetching contexts for team: $teamId and form: $formId", e)
            throw DatabaseException("Failed to fetch contexts for team and form", "getContextByTeamIdAndFormId", e)
        }
    }

    override fun getContext(id: String): DatabaseContext {
        val sqlStatement = "SELECT * FROM contexts WHERE id = ?"
        logger.debug("Fetching context: $id")
        try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatement).use { statement ->
                    statement.setObject(1, UUID.fromString(id))
                    val result = statement.executeQuery()
                    if (result.next()) {
                        logger.debug("Successfully fetched context: $id")
                        return DatabaseContext(
                            id = result.getString("id"),
                            teamId = result.getString("team_id"),
                            formId = result.getString("form_id"),
                            name = result.getString("name"),
                        )
                    } else {
                        throw NotFoundException("Context with id $id not found")
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Error fetching context $id: ${e.message}")
            throw RuntimeException("Error fetching context: $id from database", e)
        }
    }

    override fun deleteContext(id: String): Boolean {
        logger.debug("Deleting context: $id")
        val sqlStatementContext = "DELETE FROM contexts WHERE id = ?"
        try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatementContext).use { statement ->
                    statement.setObject(1, UUID.fromString(id))
                    return statement.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            logger.error("Database error deleting context: $id", e)
            throw DatabaseException("Failed to delete context: $id", "deleteContext", e)
        }
    }

    override fun changeTeam(contextId: String, newTeamId: String): Boolean {
        logger.debug("Changing team for context $contextId")
        val sqlStatement = "UPDATE contexts SET team_id = ? WHERE id = ?"
        try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatement).use { statement ->
                    statement.setObject(1, UUID.fromString(newTeamId))
                    statement.setObject(2, UUID.fromString(contextId))
                    return statement.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            if (e.sqlState == "23505") { // PostgreSQL unique_violation
                logger.warn("Unique constraint violation when updating contextName: ${e.message}")
                throw ConflictException("A context with the same team_id, form_id and name already exists.")
            } else {
                logger.error("Database error updating team for context $contextId with teamId $newTeamId", e)
                throw DatabaseException("Failed to update team for context $contextId", "changeTeam", e)
            }
        }
    }

    override fun changeName(contextId: String, newName: String): Boolean {
        logger.debug("Changing name for context $contextId")
        val sqlStatement = "UPDATE contexts SET name = ? WHERE id = ?"
        try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatement).use { statement ->
                    statement.setString(1, newName)
                    statement.setObject(2, UUID.fromString(contextId))
                    return statement.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            if (e.sqlState == "23505") { // PostgreSQL unique_violation
                logger.warn("Unique constraint violation when updating contextName: ${e.message}")
                throw ConflictException("A context with the same team_id and form_id and name already exists.")
            } else {
                logger.error("Database error updating name for context $contextId with name $newName", e)
                throw DatabaseException("Failed to update name for context $contextId", "changeName", e)
            }
        }
    }

    override fun getContextMetrics(): List<DatabaseContextMetrics> {
        logger.debug("Fetching context-stats for team")

        val sqlStatement = """
        SELECT c.id,
               c.team_id,
               c.form_id,
               c.name,
               COUNT(a.id) AS answer_count,
               MIN(a.updated) AS oldest_update
        FROM contexts c
        LEFT JOIN answers a ON a.context_id = c.id
        GROUP BY c.id, c.team_id, c.form_id, c.name
    """
        return try {
            database.getConnection().use { conn ->
                conn.prepareStatement(sqlStatement).use { statement ->

                    val result = statement.executeQuery()
                    buildList {
                        while (result.next()) {
                            add(
                                DatabaseContextMetrics(
                                    id = result.getString("id"),
                                    teamId = result.getString("team_id"),
                                    formId = result.getString("form_id"),
                                    name = result.getString("name"),
                                    answerCount = result.getInt("answer_count"),
                                    oldestUpdate = result.getString("oldest_update"),
                                ),
                            )
                        }
                    }
                }.also {
                    logger.debug("Successfully fetched context metrics")
                }
            }
        } catch (e: SQLException) {
            logger.error("Database error fetching context metrics", e)
            throw DatabaseException("Failed to fetch context metrics", "getContextMetrics", e)
        }
    }
}
