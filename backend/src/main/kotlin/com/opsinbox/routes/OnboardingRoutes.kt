package com.opsinbox.routes

import com.opsinbox.auth.auth0Sub
import com.opsinbox.config.AppConfig
import com.opsinbox.db.Companies
import com.opsinbox.db.Users
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class OnboardingRequest(
    val companyName: String,
    val vatNumber: String? = null,
    // email e nome arrivano dall'ID token sul frontend (l'access token Auth0 non li contiene)
    val email: String? = null,
    val name: String? = null,
)

@Serializable
data class MeUser(val email: String, val name: String?, val role: String)

@Serializable
data class MeCompany(val id: String, val name: String, val vatNumber: String?, val inboundAddress: String?)

@Serializable
data class MeResponse(val onboarded: Boolean, val user: MeUser? = null, val company: MeCompany? = null)

private fun meResponse(userRow: ResultRow, companyRow: ResultRow) = MeResponse(
    onboarded = true,
    user = MeUser(userRow[Users.email], userRow[Users.name], userRow[Users.role]),
    company = MeCompany(
        id = companyRow[Companies.id].value.toString(),
        name = companyRow[Companies.name],
        vatNumber = companyRow[Companies.vatNumber],
        inboundAddress = companyRow[Companies.inboundAddress],
    ),
)

/** Indirizzo di inoltro dedicato, es. "rossi-impianti-a1b2c3@inbox.opsinbox.app". */
private fun generateInboundAddress(companyName: String, domain: String): String {
    val slug = companyName.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(20)
        .ifBlank { "azienda" }
    val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
    return "$slug-$suffix@$domain"
}

fun Route.onboardingRoutes(config: AppConfig) {
    route("/api") {

        get("/me") {
            val sub = call.auth0Sub()
            val response = transaction {
                val userRow = if (sub != null) {
                    Users.selectAll().where { Users.auth0Sub eq sub }.singleOrNull()
                } else {
                    // dev: il primo utente della company seed
                    Users.selectAll()
                        .where { Users.companyId eq UUID.fromString(config.devCompanyId) }
                        .limit(1).singleOrNull()
                }
                if (userRow == null) {
                    MeResponse(onboarded = false)
                } else {
                    val companyRow = Companies.selectAll()
                        .where { Companies.id eq userRow[Users.companyId].value }
                        .single()
                    meResponse(userRow, companyRow)
                }
            }
            call.respond(response)
        }

        post("/onboarding") {
            val sub = call.auth0Sub()
            if (sub == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "onboarding disponibile solo con autenticazione attiva"),
                )
                return@post
            }
            val request = call.receive<OnboardingRequest>()
            if (request.companyName.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyName obbligatorio"))
                return@post
            }

            val result: MeResponse? = transaction {
                val existing = Users.selectAll().where { Users.auth0Sub eq sub }.singleOrNull()
                if (existing != null) return@transaction null // già onboardato

                val now = OffsetDateTime.now()
                val companyId = UUID.randomUUID()
                Companies.insert {
                    it[id] = companyId
                    it[name] = request.companyName.trim().take(200)
                    it[vatNumber] = request.vatNumber?.trim()?.take(50)
                    it[inboundAddress] = generateInboundAddress(request.companyName, config.inboundDomain)
                    it[createdAt] = now
                }
                Users.insert {
                    it[Users.companyId] = companyId
                    it[auth0Sub] = sub
                    it[email] = request.email?.trim()?.take(320) ?: "sconosciuta"
                    it[name] = request.name?.trim()?.take(200)
                    it[role] = "owner"
                    it[createdAt] = now
                }
                val userRow = Users.selectAll().where { Users.auth0Sub eq sub }.single()
                val companyRow = Companies.selectAll().where { Companies.id eq companyId }.single()
                meResponse(userRow, companyRow)
            }

            if (result == null) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "utente già associato a un'azienda"))
            } else {
                call.respond(result)
            }
        }
    }
}
