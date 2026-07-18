package com.opsinbox.auth

import com.opsinbox.config.AppConfig
import com.opsinbox.db.Users
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** Il subject Auth0 (es. "auth0|abc123") dell'utente autenticato, null se auth disattivata. */
fun ApplicationCall.auth0Sub(): String? = principal<JWTPrincipal>()?.payload?.subject

/**
 * Company dell'utente corrente:
 * - auth attiva: lookup per auth0_sub (null se l'utente non ha ancora fatto onboarding).
 *   L'header X-Company-Id viene IGNORATO (difesa in profondità: nessuna scelta di tenant
 *   controllabile dal client quando l'identità è verificata via JWT);
 * - auth disattivata (dev): header X-Company-Id oppure la company seed.
 */
fun ApplicationCall.resolveCompanyId(config: AppConfig): UUID? {
    // S8 — quando l'auth è attiva la company deriva SOLO dall'identità JWT.
    if (config.authEnabled) {
        val sub = auth0Sub() ?: return null
        return transaction {
            Users.selectAll().where { Users.auth0Sub eq sub }
                .singleOrNull()?.get(Users.companyId)?.value
        }
    }
    // Modalità dev (nessun JWT): X-Company-Id opzionale, altrimenti company seed.
    return request.headers["X-Company-Id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: UUID.fromString(config.devCompanyId)
}

/** Come [resolveCompanyId], ma risponde 403 onboarding_required e restituisce null se manca la company. */
suspend fun ApplicationCall.requireCompanyId(config: AppConfig): UUID? {
    val companyId = resolveCompanyId(config)
    if (companyId == null) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "onboarding_required"))
    }
    return companyId
}
