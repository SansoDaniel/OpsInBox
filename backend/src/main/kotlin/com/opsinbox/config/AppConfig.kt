package com.opsinbox.config

data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val openAiApiKey: String?,
    val openAiModel: String,
    val storageDir: String,
    val devCompanyId: String,
    /** Se impostato, il webhook inbound richiede questo token (Basic Auth, header o query param). */
    val inboundWebhookToken: String?,
    /** Dominio Auth0 (es. dev-xxx.eu.auth0.com). Se assente insieme all'audience, l'auth è disattivata (dev). */
    val auth0Domain: String?,
    val auth0Audience: String?,
    /** Dominio degli indirizzi di inoltro generati in onboarding (es. inbox.opsinbox.app). */
    val inboundDomain: String,
    // SMTP per le notifiche email (default: Mailpit locale, dev)
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUser: String?,
    val smtpPassword: String?,
    val smtpStartTls: Boolean,
    val smtpFrom: String,
    /** URL dell'app usato nei link delle notifiche. */
    val appUrl: String,
) {
    val authEnabled: Boolean get() = auth0Domain != null && auth0Audience != null

    companion object {
        fun fromEnv(): AppConfig = AppConfig(
            port = env("PORT", "8080").toInt(),
            dbUrl = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/opsinbox"),
            dbUser = env("DATABASE_USER", "opsinbox"),
            dbPassword = env("DATABASE_PASSWORD", "opsinbox"),
            openAiApiKey = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() },
            openAiModel = env("OPENAI_MODEL", "gpt-4o"),
            storageDir = env("STORAGE_DIR", "var/storage"),
            devCompanyId = env("DEV_COMPANY_ID", "00000000-0000-0000-0000-000000000001"),
            inboundWebhookToken = System.getenv("INBOUND_WEBHOOK_TOKEN")?.takeIf { it.isNotBlank() },
            auth0Domain = System.getenv("AUTH0_DOMAIN")?.takeIf { it.isNotBlank() },
            auth0Audience = System.getenv("AUTH0_AUDIENCE")?.takeIf { it.isNotBlank() },
            inboundDomain = env("INBOUND_DOMAIN", "inbox.local"),
            smtpHost = env("SMTP_HOST", "localhost"),
            smtpPort = env("SMTP_PORT", "1025").toInt(),
            smtpUser = System.getenv("SMTP_USER")?.takeIf { it.isNotBlank() },
            smtpPassword = System.getenv("SMTP_PASSWORD")?.takeIf { it.isNotBlank() },
            smtpStartTls = env("SMTP_STARTTLS", "false").toBoolean(),
            smtpFrom = env("SMTP_FROM", "OpsInbox <notifiche@opsinbox.local>"),
            appUrl = env("APP_URL", "http://localhost:3000"),
        )

        private fun env(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}
