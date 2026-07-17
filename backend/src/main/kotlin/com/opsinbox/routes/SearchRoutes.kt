package com.opsinbox.routes

import com.opsinbox.auth.requireCompanyId
import com.opsinbox.config.AppConfig
import com.opsinbox.db.Documents
import com.opsinbox.db.Emails
import com.opsinbox.db.Tasks
import com.opsinbox.pipeline.AiClient
import com.opsinbox.pipeline.SearchFilter
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Serializable
data class SearchDocumentDto(
    val document: DocumentDto,
    val emailId: String?,
    val emailSubject: String?,
    val fromAddress: String?,
    val hasOpenTask: Boolean,
)

@Serializable
data class SearchResponse(
    val query: String,
    val filter: SearchFilter,
    val documents: List<SearchDocumentDto> = emptyList(),
    val emails: List<EmailDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
)

private fun parseIsoDate(raw: String?): LocalDate? =
    raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

fun Route.searchRoutes(config: AppConfig, ai: AiClient) {
    get("/api/search") {
        val companyId = call.requireCompanyId(config) ?: return@get
        val query = call.request.queryParameters["q"]?.trim()
        if (query.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "parametro q obbligatorio"))
            return@get
        }

        val filter = ai.parseSearchQuery(query, LocalDate.now())
        val response = transaction {
            when (filter.target) {
                "emails" -> SearchResponse(query, filter, emails = searchEmails(companyId, filter))
                "tasks" -> SearchResponse(query, filter, tasks = searchTasks(companyId, filter))
                else -> SearchResponse(query, filter, documents = searchDocuments(companyId, filter))
            }
        }
        call.respond(response)
    }
}

private fun openTaskDocumentIds(companyId: UUID): Set<UUID> =
    Tasks.select(Tasks.documentId)
        .where {
            (Tasks.companyId eq companyId) and
                (Tasks.status inList listOf("pending_approval", "approved")) and
                Tasks.documentId.isNotNull()
        }
        .mapNotNull { it[Tasks.documentId]?.value }
        .toSet()

private fun searchDocuments(companyId: UUID, filter: SearchFilter): List<SearchDocumentDto> {
    val openDocIds = openTaskDocumentIds(companyId)

    val conditions = mutableListOf<Op<Boolean>>(Op.build { Documents.companyId eq companyId })
    filter.docType?.let { conditions += Op.build { Documents.docType eq it } }
    filter.counterpartyContains?.let { c ->
        val pattern = "%${c.lowercase()}%"
        conditions += Op.build {
            (Documents.supplierName.lowerCase() like pattern) or
                (Documents.customerName.lowerCase() like pattern)
        }
    }
    filter.amountMin?.let { conditions += Op.build { Documents.amount greaterEq BigDecimal.valueOf(it) } }
    filter.amountMax?.let { conditions += Op.build { Documents.amount lessEq BigDecimal.valueOf(it) } }
    parseIsoDate(filter.dueFrom)?.let { conditions += Op.build { Documents.dueDate greaterEq it } }
    parseIsoDate(filter.dueTo)?.let { conditions += Op.build { Documents.dueDate lessEq it } }
    parseIsoDate(filter.dateFrom)?.let { conditions += Op.build { Documents.docDate greaterEq it } }
    parseIsoDate(filter.dateTo)?.let { conditions += Op.build { Documents.docDate lessEq it } }
    filter.textContains?.let { t ->
        val pattern = "%${t.lowercase()}%"
        conditions += Op.build {
            (Documents.documentNumber.lowerCase() like pattern) or
                (Documents.supplierName.lowerCase() like pattern) or
                (Documents.customerName.lowerCase() like pattern)
        }
    }
    if (filter.openTasksOnly == true) {
        if (openDocIds.isEmpty()) return emptyList()
        conditions += Op.build { Documents.id inList openDocIds }
    }

    val rows = Documents.selectAll()
        .where { conditions.reduce { a, b -> a and b } }
        .orderBy(Documents.createdAt, SortOrder.DESC)
        .limit(100)
        .toList()

    val emailIds = rows.mapNotNull { it[Documents.emailId]?.value }.distinct()
    val emailById = if (emailIds.isEmpty()) emptyMap() else
        Emails.selectAll().where { Emails.id inList emailIds }
            .associateBy { it[Emails.id].value }

    return rows.map { row ->
        val emailRow = row[Documents.emailId]?.value?.let(emailById::get)
        SearchDocumentDto(
            document = documentDto(row),
            emailId = row[Documents.emailId]?.value?.toString(),
            emailSubject = emailRow?.get(Emails.subject),
            fromAddress = emailRow?.get(Emails.fromAddress),
            hasOpenTask = row[Documents.id].value in openDocIds,
        )
    }
}

private fun searchEmails(companyId: UUID, filter: SearchFilter): List<EmailDto> {
    val conditions = mutableListOf<Op<Boolean>>(Op.build { Emails.companyId eq companyId })
    filter.docType?.let { conditions += Op.build { Emails.category eq it } }
    filter.counterpartyContains?.let { c ->
        val pattern = "%${c.lowercase()}%"
        conditions += Op.build {
            (Emails.fromAddress.lowerCase() like pattern) or
                (Emails.fromName.lowerCase() like pattern)
        }
    }
    filter.textContains?.let { t ->
        val pattern = "%${t.lowercase()}%"
        conditions += Op.build {
            (Emails.subject.lowerCase() like pattern) or
                (Emails.bodyText.lowerCase() like pattern) or
                (Emails.summary.lowerCase() like pattern)
        }
    }
    parseIsoDate(filter.dateFrom)?.let { from ->
        val start = from.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime()
        conditions += Op.build { Emails.receivedAt greaterEq start }
    }
    parseIsoDate(filter.dateTo)?.let { to ->
        val end = to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime()
        conditions += Op.build { Emails.receivedAt less end }
    }

    return Emails.selectAll()
        .where { conditions.reduce { a, b -> a and b } }
        .orderBy(Emails.receivedAt, SortOrder.DESC)
        .limit(100)
        .map(::emailDto)
}

private fun searchTasks(companyId: UUID, filter: SearchFilter): List<TaskDto> {
    val conditions = mutableListOf<Op<Boolean>>(Op.build { Tasks.companyId eq companyId })
    if (filter.openTasksOnly != false) {
        conditions += Op.build { Tasks.status inList listOf("pending_approval", "approved") }
    }
    filter.textContains?.let { t ->
        val pattern = "%${t.lowercase()}%"
        conditions += Op.build {
            (Tasks.title.lowerCase() like pattern) or (Tasks.description.lowerCase() like pattern)
        }
    }
    filter.counterpartyContains?.let { c ->
        conditions += Op.build { Tasks.title.lowerCase() like "%${c.lowercase()}%" }
    }
    parseIsoDate(filter.dueFrom)?.let { conditions += Op.build { Tasks.dueDate greaterEq it } }
    parseIsoDate(filter.dueTo)?.let { conditions += Op.build { Tasks.dueDate lessEq it } }

    return Tasks.selectAll()
        .where { conditions.reduce { a, b -> a and b } }
        .orderBy(Tasks.createdAt, SortOrder.DESC)
        .limit(100)
        .map(::taskDto)
}
