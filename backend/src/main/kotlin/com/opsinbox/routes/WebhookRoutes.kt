package com.opsinbox.routes

import com.opsinbox.config.AppConfig
import com.opsinbox.db.Attachments
import com.opsinbox.db.Companies
import com.opsinbox.db.Emails
import com.opsinbox.jobs.JobQueue
import com.opsinbox.storage.StorageService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

private val log = LoggerFactory.getLogger("com.opsinbox.routes.WebhookRoutes")

/** Nome del rate limiter applicato all'endpoint pubblico del webhook (vedi Application.kt). */
const val WEBHOOK_RATE_LIMIT = "webhook"

/** Limite dimensione singolo allegato (Postmark accetta fino a 35MB totali). */
private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

/**
 * S9 — limite dimensione del body della richiesta webhook. Postmark inbound può portare
 * fino a ~35MB di allegati totali (base64 → ~48MB): teniamo un margine ragionevole.
 */
private const val MAX_WEBHOOK_BODY_BYTES = 50L * 1024 * 1024

@Serializable
data class PostmarkAddress(
    @SerialName("Email") val email: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class PostmarkAttachment(
    @SerialName("Name") val name: String,
    @SerialName("Content") val content: String,
    @SerialName("ContentType") val contentType: String? = null,
    @SerialName("ContentLength") val contentLength: Long? = null,
)

/**
 * Payload Postmark Inbound (i campi non mappati sono ignorati dalla deserializzazione).
 * https://postmarkapp.com/developer/webhooks/inbound-webhook
 */
@Serializable
data class PostmarkInbound(
    @SerialName("From") val from: String,
    @SerialName("FromName") val fromName: String? = null,
    @SerialName("To") val to: String? = null,
    @SerialName("ToFull") val toFull: List<PostmarkAddress> = emptyList(),
    // Postmark mette qui l'indirizzo inbound effettivo (hash@inbound.postmarkapp.com o dominio custom)
    @SerialName("OriginalRecipient") val originalRecipient: String? = null,
    @SerialName("Subject") val subject: String? = null,
    @SerialName("TextBody") val textBody: String? = null,
    @SerialName("HtmlBody") val htmlBody: String? = null,
    @SerialName("MessageID") val messageId: String? = null,
    @SerialName("Date") val date: String? = null,
    @SerialName("Attachments") val attachments: List<PostmarkAttachment> = emptyList(),
)

/**
 * Postmark non firma i webhook inbound con HMAC: la protezione raccomandata è
 * Basic Auth nell'URL del webhook (https://user:token@dominio/webhooks/inbound-email).
 * Accettiamo il token anche via header X-Webhook-Token o query param ?token= .
 */
private fun ApplicationCall.isWebhookAuthorized(config: AppConfig): Boolean {
    val expected = config.inboundWebhookToken ?: return true // dev: nessun token configurato
    request.headers["X-Webhook-Token"]?.let { if (it == expected) return true }
    request.queryParameters["token"]?.let { if (it == expected) return true }
    request.headers[HttpHeaders.Authorization]?.let { auth ->
        if (auth.startsWith("Basic ", ignoreCase = true)) {
            val decoded = runCatching {
                String(Base64.getDecoder().decode(auth.substring(6).trim()))
            }.getOrNull() ?: return@let
            val password = decoded.substringAfter(':', missingDelimiterValue = "")
            if (password == expected || decoded.substringBefore(':') == expected) return true
        }
    }
    return false
}

private fun parseEmailDate(raw: String?): OffsetDateTime =
    raw?.let {
        runCatching { OffsetDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(it) }.getOrNull()
    } ?: OffsetDateTime.now()

fun Route.webhookRoutes(config: AppConfig, storage: StorageService) {
    rateLimit(RateLimitName(WEBHOOK_RATE_LIMIT)) {
    post("/webhooks/inbound-email") {
        if (!call.isWebhookAuthorized(config)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "token webhook mancante o errato"))
            return@post
        }

        // S9 — rifiuta i body sovradimensionati prima di deserializzarli in memoria.
        val declaredLength = call.request.contentLength()
        if (declaredLength != null && declaredLength > MAX_WEBHOOK_BODY_BYTES) {
            log.warn("Webhook rifiutato: body {} byte oltre il limite di {}", declaredLength, MAX_WEBHOOK_BODY_BYTES)
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "richiesta troppo grande"))
            return@post
        }

        val inbound = call.receive<PostmarkInbound>()
        val messageId = inbound.messageId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

        // La company si risolve dall'indirizzo di inoltro dedicato.
        // OriginalRecipient è il più affidabile (è l'indirizzo inbound che ha ricevuto il messaggio).
        val candidates = buildList {
            inbound.originalRecipient?.let { add(it) }
            inbound.toFull.forEach { add(it.email) }
            inbound.to?.let { add(it) }
        }.map { it.lowercase().trim() }.distinct()

        val resolvedCompanyId = transaction {
            candidates.firstNotNullOfOrNull { address ->
                Companies.selectAll()
                    .where { Companies.inboundAddress eq address }
                    .singleOrNull()?.get(Companies.id)?.value
            }
        }
        // S8 — in produzione niente fallback sulla company seed: un'email a un indirizzo
        // sconosciuto NON deve finire in un tenant arbitrario. In dev il fallback resta
        // (config-zero: send-test-email.ps1 non conosce l'indirizzo generato).
        val companyId = resolvedCompanyId
            ?: if (config.isProduction) null else UUID.fromString(config.devCompanyId)
        if (companyId == null) {
            log.warn("Webhook: nessuna company per gli indirizzi {} (email scartata)", candidates)
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "indirizzo di inoltro sconosciuto"))
            return@post
        }

        val emailId = UUID.randomUUID()
        val inserted = transaction {
            val now = OffsetDateTime.now()
            val count = Emails.insertIgnore {
                it[id] = emailId
                it[Emails.companyId] = companyId
                it[Emails.messageId] = messageId
                it[fromAddress] = inbound.from
                it[fromName] = inbound.fromName
                it[toAddress] = inbound.originalRecipient ?: inbound.to
                it[subject] = inbound.subject
                it[bodyText] = inbound.textBody
                it[bodyHtml] = inbound.htmlBody
                it[receivedAt] = parseEmailDate(inbound.date)
                it[status] = "received"
                it[createdAt] = now
            }.insertedCount

            if (count > 0) {
                inbound.attachments.forEach { att ->
                    val bytes = runCatching { Base64.getMimeDecoder().decode(att.content) }.getOrNull()
                        ?: return@forEach
                    if (bytes.size > MAX_ATTACHMENT_BYTES) {
                        log.warn("Allegato '{}' scartato: {} byte oltre il limite", att.name, bytes.size)
                        return@forEach
                    }
                    val storageKey = storage.put(bytes, att.name)
                    Attachments.insert {
                        it[Attachments.emailId] = emailId
                        it[filename] = att.name
                        it[contentType] = att.contentType
                        it[sizeBytes] = bytes.size.toLong()
                        it[Attachments.storageKey] = storageKey
                        it[createdAt] = now
                    }
                }
            }
            count
        }

        if (inserted == 0) {
            // stesso MessageID già ricevuto: idempotente (Postmark fa retry sui non-2xx)
            call.respond(HttpStatusCode.OK, mapOf("status" to "duplicate"))
            return@post
        }

        JobQueue.enqueue("process_email", buildJsonObject { put("emailId", emailId.toString()) })
        call.respond(HttpStatusCode.OK, mapOf("status" to "queued", "emailId" to emailId.toString()))
    }
    }
}
