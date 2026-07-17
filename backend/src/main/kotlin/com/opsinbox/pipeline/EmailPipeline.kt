package com.opsinbox.pipeline

import com.opsinbox.db.Attachments
import com.opsinbox.db.Companies
import com.opsinbox.db.Documents
import com.opsinbox.db.Emails
import com.opsinbox.db.Notifications
import com.opsinbox.db.Tasks
import com.opsinbox.jobs.JobQueue
import com.opsinbox.storage.StorageService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class EmailPipeline(
    private val ai: AiClient,
    private val storage: StorageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun process(emailId: UUID) {
        val emailRow: ResultRow = transaction {
            Emails.update({ Emails.id eq emailId }) { it[status] = "processing" }
            Emails.selectAll().where { Emails.id eq emailId }.single()
        }
        val attachmentRows = transaction {
            Attachments.selectAll().where { Attachments.emailId eq emailId }.toList()
        }
        val attachmentInputs = attachmentRows.map { row ->
            AttachmentInput(
                filename = row[Attachments.filename],
                contentType = row[Attachments.contentType],
                bytes = storage.get(row[Attachments.storageKey]),
            )
        }

        val analysis = try {
            ai.analyze(
                EmailForAnalysis(
                    fromAddress = emailRow[Emails.fromAddress],
                    fromName = emailRow[Emails.fromName],
                    subject = emailRow[Emails.subject],
                    bodyText = emailRow[Emails.bodyText],
                    attachments = attachmentInputs,
                )
            )
        } catch (e: Exception) {
            transaction {
                Emails.update({ Emails.id eq emailId }) {
                    it[status] = "failed"
                    it[error] = (e.message ?: e.toString()).take(2000)
                }
            }
            throw e
        }

        val companyId = emailRow[Emails.companyId].value
        val firstAttachmentId = attachmentRows.firstOrNull()?.get(Attachments.id)?.value

        transaction {
            val now = OffsetDateTime.now()

            Emails.update({ Emails.id eq emailId }) {
                it[category] = normalizeCategory(analysis.category)
                it[summary] = analysis.summary.take(1000)
                it[language] = analysis.language
                it[status] = "processed"
                it[error] = null
            }

            // Documenti con dedup: la stessa fattura ricevuta due volte non viene duplicata.
            var insertedDocs = 0
            var lastDocId: UUID? = null
            analysis.documents.forEachIndexed { index, doc ->
                val docId = UUID.randomUUID()
                val inserted = Documents.insertIgnore {
                    it[id] = docId
                    it[Documents.companyId] = companyId
                    it[Documents.emailId] = emailId
                    it[attachmentId] = firstAttachmentId
                    it[docType] = doc.docType.take(50)
                    it[supplierName] = doc.supplierName
                    it[customerName] = doc.customerName
                    it[documentNumber] = doc.documentNumber
                    it[docDate] = parseDate(doc.docDate)
                    it[dueDate] = parseDate(doc.dueDate)
                    it[amount] = doc.amount?.let(BigDecimal::valueOf)
                    it[currency] = doc.currency ?: "EUR"
                    it[lineItems] = json.encodeToJsonElement(ListSerializer(AiLineItem.serializer()), doc.lineItems)
                    it[rawExtraction] = json.encodeToJsonElement(AiDocument.serializer(), doc)
                    it[confidence] = doc.confidence?.let { c -> BigDecimal.valueOf(c.coerceIn(0.0, 1.0)) }
                    it[dedupKey] = dedupKeyFor(emailRow[Emails.fromAddress], doc, emailId, index)
                    it[createdAt] = now
                }.insertedCount
                if (inserted > 0) {
                    insertedDocs++
                    lastDocId = docId
                }
            }

            // Se tutti i documenti erano duplicati, è un re-invio: niente nuovi task.
            val isResend = analysis.documents.isNotEmpty() && insertedDocs == 0
            if (isResend) {
                log.info("Email $emailId: documenti già noti (re-invio), nessun task creato")
                return@transaction
            }

            // Canali di notifica configurati per l'azienda; fallback: solo log.
            val companyRow = Companies.selectAll().where { Companies.id eq companyId }.single()
            val channels = listOfNotNull(
                "email".takeIf { companyRow[Companies.notificationEmail] != null },
                "slack".takeIf { companyRow[Companies.slackWebhookUrl] != null },
                "teams".takeIf { companyRow[Companies.teamsWebhookUrl] != null },
            ).ifEmpty { listOf("log") }

            analysis.tasks.forEach { aiTask ->
                val taskId = UUID.randomUUID()
                Tasks.insert {
                    it[id] = taskId
                    it[Tasks.companyId] = companyId
                    it[Tasks.emailId] = emailId
                    it[documentId] = lastDocId
                    it[title] = aiTask.title.take(500)
                    it[description] = aiTask.description
                    it[type] = aiTask.type ?: "generic"
                    it[priority] = normalizePriority(aiTask.priority)
                    it[dueDate] = parseDate(aiTask.dueDate)
                    it[status] = "pending_approval"
                    it[createdAt] = now
                }

                val notificationPayload = buildJsonObject {
                    put("taskId", taskId.toString())
                    put("taskTitle", aiTask.title)
                    put("category", analysis.category)
                    put("summary", analysis.summary)
                    put("priority", normalizePriority(aiTask.priority))
                    aiTask.dueDate?.let { d -> put("dueDate", d) }
                }
                channels.forEach { ch ->
                    val notificationId = UUID.randomUUID()
                    Notifications.insert {
                        it[id] = notificationId
                        it[Notifications.companyId] = companyId
                        it[Notifications.taskId] = taskId
                        it[channel] = ch
                        it[payload] = notificationPayload
                        it[status] = "pending"
                        it[createdAt] = now
                    }
                    JobQueue.enqueue("send_notification", buildJsonObject {
                        put("notificationId", notificationId.toString())
                    })
                }
            }
        }
        log.info("Email $emailId processata: categoria=${analysis.category}, documenti=${analysis.documents.size}, task=${analysis.tasks.size}")
    }

    private fun normalizeCategory(raw: String): String {
        val valid = setOf(
            "invoice", "quote", "order", "complaint", "customer_request",
            "appointment", "contract", "delivery_note", "other",
        )
        return if (raw in valid) raw else "other"
    }

    private fun normalizePriority(raw: String?): String =
        if (raw in setOf("low", "medium", "high")) raw!! else "medium"

    private fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        runCatching { return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }
        runCatching { return LocalDate.parse(raw, DateTimeFormatter.ofPattern("dd/MM/yyyy")) }
        return null
    }

    private fun dedupKeyFor(fromAddress: String, doc: AiDocument, emailId: UUID, index: Int): String {
        val hasIdentity = !doc.documentNumber.isNullOrBlank() || doc.amount != null
        val base = if (hasIdentity) {
            listOf(fromAddress, doc.docType, doc.documentNumber ?: "", doc.amount?.toString() ?: "")
        } else {
            listOf(emailId.toString(), doc.docType, index.toString())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(base.joinToString("|").lowercase().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
