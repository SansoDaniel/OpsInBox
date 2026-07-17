package com.opsinbox.jobs

import com.opsinbox.db.Jobs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

data class ClaimedJob(
    val id: Long,
    val type: String,
    val payload: JsonObject,
    val attempts: Int,
    val maxAttempts: Int,
)

/**
 * Coda di lavoro basata su Postgres (FOR UPDATE SKIP LOCKED).
 * Per i volumi dell'MVP non serve Redis; l'interfaccia resta la stessa
 * se in futuro si vorrà cambiare backend.
 */
object JobQueue {

    fun enqueue(type: String, payload: JsonObject) {
        transaction {
            val now = OffsetDateTime.now()
            Jobs.insert {
                it[Jobs.type] = type
                it[Jobs.payload] = payload
                it[status] = "queued"
                it[attempts] = 0
                it[maxAttempts] = 3
                it[runAt] = now
                it[createdAt] = now
            }
        }
    }

    fun claimNext(workerId: String): ClaimedJob? = transaction {
        val sql = """
            UPDATE jobs
            SET status = 'running', locked_at = now(), locked_by = '$workerId', attempts = attempts + 1
            WHERE id = (
                SELECT id FROM jobs
                WHERE status = 'queued' AND run_at <= now()
                ORDER BY id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, type, payload, attempts, max_attempts
        """.trimIndent()
        // explicitStatementType SELECT: la query inizia con UPDATE ma restituisce righe (RETURNING)
        exec(sql, explicitStatementType = StatementType.SELECT) { rs ->
            if (rs.next()) {
                ClaimedJob(
                    id = rs.getLong("id"),
                    type = rs.getString("type"),
                    payload = Json.parseToJsonElement(rs.getString("payload")).jsonObject,
                    attempts = rs.getInt("attempts"),
                    maxAttempts = rs.getInt("max_attempts"),
                )
            } else null
        }
    }

    fun markDone(jobId: Long) {
        transaction {
            Jobs.update({ Jobs.id eq jobId }) { it[status] = "done" }
        }
    }

    fun markFailed(job: ClaimedJob, errorMessage: String) {
        transaction {
            if (job.attempts < job.maxAttempts) {
                // retry con backoff lineare
                Jobs.update({ Jobs.id eq job.id }) {
                    it[status] = "queued"
                    it[runAt] = OffsetDateTime.now().plusSeconds(30L * job.attempts)
                    it[lastError] = errorMessage.take(2000)
                }
            } else {
                Jobs.update({ Jobs.id eq job.id }) {
                    it[status] = "failed"
                    it[lastError] = errorMessage.take(2000)
                }
            }
        }
    }
}
