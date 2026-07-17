package com.opsinbox.notify

import com.opsinbox.config.AppConfig
import com.opsinbox.db.Companies
import com.opsinbox.db.Notifications
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID

private data class PendingNotification(
    val id: UUID,
    val channel: String,
    val payload: JsonObject,
    val status: String,
    val notificationEmail: String?,
    val slackWebhookUrl: String?,
    val teamsWebhookUrl: String?,
    val whatsappNumber: String?,
)

/**
 * Consegna delle notifiche sui canali configurati per azienda:
 * email (SMTP), Slack e Teams (incoming webhook), log (fallback dev).
 * Gli indirizzi si leggono al momento dell'invio, così le impostazioni
 * aggiornate valgono anche per le notifiche già in coda.
 */
class NotificationService(private val config: AppConfig) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    suspend fun dispatch(notificationId: UUID) {
        val pending = transaction {
            val row = Notifications.selectAll()
                .where { Notifications.id eq notificationId }
                .singleOrNull() ?: return@transaction null
            val companyRow = Companies.selectAll()
                .where { Companies.id eq row[Notifications.companyId].value }
                .single()
            PendingNotification(
                id = row[Notifications.id].value,
                channel = row[Notifications.channel],
                payload = row[Notifications.payload] as? JsonObject ?: buildJsonObject {},
                status = row[Notifications.status],
                notificationEmail = companyRow[Companies.notificationEmail],
                slackWebhookUrl = companyRow[Companies.slackWebhookUrl],
                teamsWebhookUrl = companyRow[Companies.teamsWebhookUrl],
                whatsappNumber = companyRow[Companies.whatsappNumber],
            )
        } ?: return

        if (pending.status == "sent") return // idempotente sui retry

        val text = buildText(pending.payload)
        when (pending.channel) {
            "email" -> {
                val to = pending.notificationEmail
                if (to == null) return markFailed(pending.id, "notification_email non configurata")
                sendEmail(to, buildSubject(pending.payload), text)
            }
            "slack" -> {
                val url = pending.slackWebhookUrl
                if (url == null) return markFailed(pending.id, "slack_webhook_url non configurato")
                postWebhook(url, text)
            }
            "teams" -> {
                val url = pending.teamsWebhookUrl
                if (url == null) return markFailed(pending.id, "teams_webhook_url non configurato")
                postWebhook(url, text)
            }
            "whatsapp" -> {
                val number = pending.whatsappNumber
                if (number == null) return markFailed(pending.id, "whatsapp_number non configurato")
                sendWhatsApp(number, text)
            }
            else -> log.info("NOTIFICA [log] {}", pending.payload)
        }

        transaction {
            Notifications.update({ Notifications.id eq pending.id }) {
                it[status] = "sent"
                it[sentAt] = OffsetDateTime.now()
            }
        }
        log.info("Notifica {} inviata via {}", pending.id, pending.channel)
    }

    private fun markFailed(id: UUID, reason: String) {
        // configurazione mancante: inutile far ritentare il job
        log.warn("Notifica {} non inviabile: {}", id, reason)
        transaction {
            Notifications.update({ Notifications.id eq id }) { it[status] = "failed" }
        }
    }

    private fun field(payload: JsonObject, key: String): String? =
        payload[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun buildSubject(payload: JsonObject): String =
        "Nuova attività da approvare: ${field(payload, "taskTitle") ?: "attività"}"

    private fun buildText(payload: JsonObject): String = buildString {
        appendLine("📥 Nuova attività proposta da OpsInbox")
        appendLine()
        appendLine(field(payload, "taskTitle") ?: "(senza titolo)")
        val meta = listOfNotNull(
            field(payload, "priority")?.let {
                "Priorità: " + when (it) { "high" -> "alta"; "low" -> "bassa"; else -> "media" }
            },
            field(payload, "dueDate")?.let { "Scadenza: $it" },
        )
        if (meta.isNotEmpty()) appendLine(meta.joinToString(" — "))
        field(payload, "summary")?.let {
            appendLine()
            appendLine(it)
        }
        appendLine()
        appendLine("Approva o ignora: ${config.appUrl}/tasks")
    }

    private suspend fun sendEmail(to: String, subject: String, body: String) {
        val props = Properties().apply {
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            if (config.smtpUser != null) put("mail.smtp.auth", "true")
            if (config.smtpStartTls) put("mail.smtp.starttls.enable", "true")
        }
        val session = if (config.smtpUser != null) {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(config.smtpUser, config.smtpPassword ?: "")
            })
        } else {
            Session.getInstance(props)
        }
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.smtpFrom))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
        }
        // jakarta.mail è bloccante: fuori dal dispatcher di default
        withContext(Dispatchers.IO) { Transport.send(message) }
    }

    /**
     * WhatsApp via open-wa EasyAPI (https://github.com/open-wa/wa-automate-nodejs).
     * NB: automazione non ufficiale di WhatsApp Web — adatta a MVP/demo;
     * per la produzione passare alla WhatsApp Business Cloud API (stesso canale,
     * basta sostituire questo sender).
     */
    private suspend fun sendWhatsApp(number: String, text: String) {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) error("Numero WhatsApp non valido: '$number'")
        val chatId = "$digits@c.us"
        val response = http.post("${config.openWaApiUrl}/sendText") {
            contentType(ContentType.Application.Json)
            config.openWaApiKey?.let { header("api_key", it) }
            setBody(buildJsonObject {
                put("args", buildJsonObject {
                    put("to", chatId)
                    put("content", text)
                })
            }.toString())
        }
        val body = response.bodyAsText()
        // open-wa può rispondere 200 con {"success":false,...} in caso di errore
        if (!response.status.isSuccess() || body.contains("\"success\":false")) {
            error("OpenWA ${response.status.value}: ${body.take(300)}")
        }
    }

    private suspend fun postWebhook(url: String, text: String) {
        // Slack e Teams (connector) accettano entrambi {"text": "..."}
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", text) }.toString())
        }
        if (!response.status.isSuccess()) {
            error("Webhook ${response.status.value}: ${response.bodyAsText().take(300)}")
        }
    }
}
