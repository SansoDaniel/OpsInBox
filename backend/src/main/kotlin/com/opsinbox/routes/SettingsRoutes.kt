package com.opsinbox.routes

import com.opsinbox.auth.requireCompanyId
import com.opsinbox.config.AppConfig
import com.opsinbox.db.Companies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

@Serializable
data class SettingsDto(
    val inboundAddress: String?,
    val notificationEmail: String?,
    val slackWebhookUrl: String?,
    val teamsWebhookUrl: String?,
    val whatsappNumber: String?,
)

@Serializable
data class UpdateSettingsRequest(
    val notificationEmail: String? = null,
    val slackWebhookUrl: String? = null,
    val teamsWebhookUrl: String? = null,
    val whatsappNumber: String? = null,
)

fun Route.settingsRoutes(config: AppConfig) {
    route("/api/settings") {

        get {
            val companyId = call.requireCompanyId(config) ?: return@get
            val dto = transaction {
                val row = Companies.selectAll().where { Companies.id eq companyId }.single()
                SettingsDto(
                    inboundAddress = row[Companies.inboundAddress],
                    notificationEmail = row[Companies.notificationEmail],
                    slackWebhookUrl = row[Companies.slackWebhookUrl],
                    teamsWebhookUrl = row[Companies.teamsWebhookUrl],
                    whatsappNumber = row[Companies.whatsappNumber],
                )
            }
            call.respond(dto)
        }

        post {
            val companyId = call.requireCompanyId(config) ?: return@post
            val request = call.receive<UpdateSettingsRequest>()
            fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }

            val dto = transaction {
                Companies.update({ Companies.id eq companyId }) {
                    it[notificationEmail] = clean(request.notificationEmail)
                    it[slackWebhookUrl] = clean(request.slackWebhookUrl)
                    it[teamsWebhookUrl] = clean(request.teamsWebhookUrl)
                    it[whatsappNumber] = clean(request.whatsappNumber)
                }
                val row = Companies.selectAll().where { Companies.id eq companyId }.single()
                SettingsDto(
                    inboundAddress = row[Companies.inboundAddress],
                    notificationEmail = row[Companies.notificationEmail],
                    slackWebhookUrl = row[Companies.slackWebhookUrl],
                    teamsWebhookUrl = row[Companies.teamsWebhookUrl],
                    whatsappNumber = row[Companies.whatsappNumber],
                )
            }
            call.respond(dto)
        }
    }
}
