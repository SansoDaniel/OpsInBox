package com.opsinbox.routes

import com.opsinbox.auth.requireCompanyId
import com.opsinbox.config.AppConfig
import com.opsinbox.db.Attachments
import com.opsinbox.db.Documents
import com.opsinbox.db.Emails
import com.opsinbox.db.Tasks
import com.opsinbox.storage.StorageService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

@Serializable
data class EmailDto(
    val id: String,
    val fromAddress: String,
    val fromName: String?,
    val subject: String?,
    val receivedAt: String,
    val status: String,
    val category: String?,
    val summary: String?,
)

@Serializable
data class AttachmentDto(val id: String, val filename: String, val contentType: String?, val sizeBytes: Long)

@Serializable
data class DocumentDto(
    val id: String,
    val docType: String,
    val supplierName: String?,
    val customerName: String?,
    val documentNumber: String?,
    val docDate: String?,
    val dueDate: String?,
    val amount: Double?,
    val currency: String,
    val confidence: Double?,
)

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val description: String?,
    val type: String,
    val priority: String,
    val dueDate: String?,
    val status: String,
    val createdAt: String,
    val emailId: String?,
    val documentId: String?,
)

@Serializable
data class EmailDetailDto(
    val email: EmailDto,
    val bodyText: String?,
    val attachments: List<AttachmentDto>,
    val documents: List<DocumentDto>,
    val tasks: List<TaskDto>,
)

@Serializable
data class DashboardDto(
    val emailsToday: Long,
    val byCategory: Map<String, Long>,
    val pendingTasks: Long,
    val overdueTasks: Long,
)

internal fun emailDto(row: ResultRow) = EmailDto(
    id = row[Emails.id].value.toString(),
    fromAddress = row[Emails.fromAddress],
    fromName = row[Emails.fromName],
    subject = row[Emails.subject],
    receivedAt = row[Emails.receivedAt].toString(),
    status = row[Emails.status],
    category = row[Emails.category],
    summary = row[Emails.summary],
)

internal fun taskDto(row: ResultRow) = TaskDto(
    id = row[Tasks.id].value.toString(),
    title = row[Tasks.title],
    description = row[Tasks.description],
    type = row[Tasks.type],
    priority = row[Tasks.priority],
    dueDate = row[Tasks.dueDate]?.toString(),
    status = row[Tasks.status],
    createdAt = row[Tasks.createdAt].toString(),
    emailId = row[Tasks.emailId]?.value?.toString(),
    documentId = row[Tasks.documentId]?.value?.toString(),
)

internal fun documentDto(row: ResultRow) = DocumentDto(
    id = row[Documents.id].value.toString(),
    docType = row[Documents.docType],
    supplierName = row[Documents.supplierName],
    customerName = row[Documents.customerName],
    documentNumber = row[Documents.documentNumber],
    docDate = row[Documents.docDate]?.toString(),
    dueDate = row[Documents.dueDate]?.toString(),
    amount = row[Documents.amount]?.toDouble(),
    currency = row[Documents.currency],
    confidence = row[Documents.confidence]?.toDouble(),
)

fun Route.apiRoutes(config: AppConfig, storage: StorageService) {
    route("/api") {

        get("/attachments/{id}") {
            val companyId = call.requireCompanyId(config) ?: return@get
            val attachmentId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (attachmentId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id non valido"))
                return@get
            }
            val attachment = transaction {
                val row = Attachments.selectAll()
                    .where { Attachments.id eq attachmentId }
                    .singleOrNull() ?: return@transaction null
                val emailRow = Emails.selectAll()
                    .where { Emails.id eq row[Attachments.emailId].value }
                    .single()
                // l'allegato è visibile solo alla company proprietaria dell'email
                if (emailRow[Emails.companyId].value != companyId) null
                else Triple(row[Attachments.filename], row[Attachments.contentType], row[Attachments.storageKey])
            }
            if (attachment == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "allegato non trovato"))
                return@get
            }
            val (filename, contentType, storageKey) = attachment
            val bytes = storage.get(storageKey)
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${filename.replace("\"", "")}\"",
            )
            call.respondBytes(
                bytes,
                contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                    ?: ContentType.Application.OctetStream,
            )
        }

        get("/dashboard/today") {
            val companyId = call.requireCompanyId(config) ?: return@get
            val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime()
            val today = LocalDate.now()

            val dto = transaction {
                val emailsToday = Emails.selectAll()
                    .where { (Emails.companyId eq companyId) and (Emails.receivedAt greaterEq startOfDay) }
                    .count()

                val categoryCount = Emails.id.count()
                val byCategory = Emails.select(Emails.category, categoryCount)
                    .where { (Emails.companyId eq companyId) and (Emails.receivedAt greaterEq startOfDay) }
                    .groupBy(Emails.category)
                    .associate { (it[Emails.category] ?: "unclassified") to it[categoryCount] }

                val pendingTasks = Tasks.selectAll()
                    .where { (Tasks.companyId eq companyId) and (Tasks.status eq "pending_approval") }
                    .count()

                val overdueTasks = Tasks.selectAll()
                    .where {
                        (Tasks.companyId eq companyId) and
                            (Tasks.status inList listOf("pending_approval", "approved")) and
                            (Tasks.dueDate less today)
                    }
                    .count()

                DashboardDto(emailsToday, byCategory, pendingTasks, overdueTasks)
            }
            call.respond(dto)
        }

        get("/emails") {
            val companyId = call.requireCompanyId(config) ?: return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val emails = transaction {
                Emails.selectAll()
                    .where { Emails.companyId eq companyId }
                    .orderBy(Emails.receivedAt, SortOrder.DESC)
                    .limit(limit)
                    .map(::emailDto)
            }
            call.respond(emails)
        }

        get("/emails/{id}") {
            val companyId = call.requireCompanyId(config) ?: return@get
            val emailId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (emailId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id non valido"))
                return@get
            }
            val detail = transaction {
                val row = Emails.selectAll()
                    .where { (Emails.id eq emailId) and (Emails.companyId eq companyId) }
                    .singleOrNull() ?: return@transaction null
                EmailDetailDto(
                    email = emailDto(row),
                    bodyText = row[Emails.bodyText],
                    attachments = Attachments.selectAll()
                        .where { Attachments.emailId eq emailId }
                        .map {
                            AttachmentDto(
                                id = it[Attachments.id].value.toString(),
                                filename = it[Attachments.filename],
                                contentType = it[Attachments.contentType],
                                sizeBytes = it[Attachments.sizeBytes],
                            )
                        },
                    documents = Documents.selectAll()
                        .where { Documents.emailId eq emailId }
                        .map(::documentDto),
                    tasks = Tasks.selectAll()
                        .where { Tasks.emailId eq emailId }
                        .map(::taskDto),
                )
            }
            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "email non trovata"))
            } else {
                call.respond(detail)
            }
        }

        get("/tasks") {
            val companyId = call.requireCompanyId(config) ?: return@get
            val statusFilter = call.request.queryParameters["status"] ?: "pending_approval"
            val tasks = transaction {
                Tasks.selectAll()
                    .where {
                        if (statusFilter == "all") Tasks.companyId eq companyId
                        else (Tasks.companyId eq companyId) and (Tasks.status eq statusFilter)
                    }
                    .orderBy(Tasks.createdAt, SortOrder.DESC)
                    .limit(200)
                    .map(::taskDto)
            }
            call.respond(tasks)
        }

        post("/tasks/{id}/approve") { call.updateTaskStatus(config, "approved", markResolved = false) }
        post("/tasks/{id}/dismiss") { call.updateTaskStatus(config, "dismissed", markResolved = true) }
        post("/tasks/{id}/done") { call.updateTaskStatus(config, "done", markResolved = true) }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.updateTaskStatus(
    config: AppConfig,
    newStatus: String,
    markResolved: Boolean,
) {
    val companyId = requireCompanyId(config) ?: return
    val taskId = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (taskId == null) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "id non valido"))
        return
    }
    val updated = transaction {
        Tasks.update({ (Tasks.id eq taskId) and (Tasks.companyId eq companyId) }) {
            it[status] = newStatus
            if (markResolved) it[resolvedAt] = OffsetDateTime.now()
        }
    }
    if (updated == 0) {
        respond(HttpStatusCode.NotFound, mapOf("error" to "task non trovato"))
    } else {
        respond(mapOf("status" to newStatus))
    }
}
