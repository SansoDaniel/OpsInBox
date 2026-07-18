package com.opsinbox.config

/** Ambiente di esecuzione. In [PRODUCTION] il sistema è fail-closed e pretende i segreti. */
enum class AppEnv {
    DEVELOPMENT,
    PRODUCTION;

    val isProduction: Boolean get() = this == PRODUCTION

    companion object {
        /** Parsing tollerante: "prod"/"production" → PRODUCTION, tutto il resto (default) → DEVELOPMENT. */
        fun parse(raw: String?): AppEnv = when (raw?.trim()?.lowercase()) {
            "production", "prod" -> PRODUCTION
            else -> DEVELOPMENT
        }
    }
}

data class AppConfig(
    /** Ambiente: development (default, config-zero) o production (fail-closed). */
    val appEnv: AppEnv,
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
    // open-wa EasyAPI per le notifiche WhatsApp (non ufficiale; in produzione: Business Cloud API)
    val openWaApiUrl: String,
    val openWaApiKey: String?,
    /** Origini CORS ammesse (host[:porta], senza schema). In dev include localhost:3000. */
    val corsAllowedHosts: List<String>,
) {
    val authEnabled: Boolean get() = auth0Domain != null && auth0Audience != null

    val isProduction: Boolean get() = appEnv.isProduction

    companion object {
        /** Host CORS di default in sviluppo (frontend Next.js locale). */
        private val DEV_CORS_HOSTS = listOf("localhost:3000", "127.0.0.1:3000")

        fun fromEnv(): AppConfig {
            val appEnv = AppEnv.parse(System.getenv("APP_ENV"))
            return AppConfig(
                appEnv = appEnv,
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
                openWaApiUrl = env("OPENWA_API_URL", "http://localhost:8002"),
                openWaApiKey = System.getenv("OPENWA_API_KEY")?.takeIf { it.isNotBlank() },
                corsAllowedHosts = parseCorsHosts(System.getenv("CORS_ALLOWED_ORIGINS"), appEnv),
            )
        }

        /**
         * Valida la configurazione per l'ambiente corrente. In produzione (fail-closed)
         * i segreti di sicurezza sono obbligatori: ritorna la lista dei problemi bloccanti
         * (vuota se la config è valida). Il chiamante (boot) decide se terminare.
         */
        fun AppConfig.validateForBoot(): List<String> {
            if (!isProduction) return emptyList()
            val errors = mutableListOf<String>()
            if (auth0Domain == null || auth0Audience == null) {
                errors += "AUTH0_DOMAIN e AUTH0_AUDIENCE sono obbligatori in produzione " +
                    "(altrimenti l'autenticazione sarebbe disattivata)."
            }
            if (inboundWebhookToken == null) {
                errors += "INBOUND_WEBHOOK_TOKEN è obbligatorio in produzione " +
                    "(altrimenti il webhook inbound sarebbe aperto a chiunque)."
            }
            return errors
        }

        /**
         * Origini CORS: in produzione vanno esplicitate via CORS_ALLOWED_ORIGINS (lista
         * separata da virgole, host[:porta] senza schema). In sviluppo si usa localhost:3000
         * più eventuali host extra dall'env.
         */
        private fun parseCorsHosts(raw: String?, appEnv: AppEnv): List<String> {
            val extra = raw?.split(",")
                ?.map { it.trim().removePrefix("http://").removePrefix("https://").trimEnd('/') }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            return if (appEnv.isProduction) extra.distinct()
            else (DEV_CORS_HOSTS + extra).distinct()
        }

        private fun env(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}
