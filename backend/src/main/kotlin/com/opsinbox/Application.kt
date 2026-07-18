package com.opsinbox

import com.opsinbox.config.AppConfig
import com.opsinbox.config.AppConfig.Companion.validateForBoot
import com.opsinbox.db.DatabaseFactory
import com.opsinbox.jobs.JobWorker
import com.opsinbox.pipeline.AiClient
import com.opsinbox.pipeline.EmailPipeline
import com.opsinbox.pipeline.MockAiClient
import com.opsinbox.pipeline.OpenAiClient
import com.auth0.jwk.JwkProviderBuilder
import com.opsinbox.notify.NotificationService
import com.opsinbox.routes.WEBHOOK_RATE_LIMIT
import com.opsinbox.routes.apiRoutes
import com.opsinbox.routes.onboardingRoutes
import com.opsinbox.routes.searchRoutes
import com.opsinbox.routes.settingsRoutes
import com.opsinbox.routes.webhookRoutes
import com.opsinbox.storage.LocalDiskStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("com.opsinbox.Application")
    val config = AppConfig.fromEnv()
    log.info("Ambiente: {}", config.appEnv)

    // S3 — guard di boot: in produzione (fail-closed) i segreti sono obbligatori.
    // Se mancano, logga l'errore e termina invece di partire in modalità insicura.
    val bootErrors = config.validateForBoot()
    if (bootErrors.isNotEmpty()) {
        log.error("Boot interrotto: configurazione di sicurezza incompleta per APP_ENV=production.")
        bootErrors.forEach { log.error("  - {}", it) }
        log.error("Imposta i segreti richiesti oppure avvia in APP_ENV=development per la modalità demo.")
        exitProcess(1)
    }

    DatabaseFactory.init(config)

    val storage = LocalDiskStorage(config.storageDir)
    val ai: AiClient = if (config.openAiApiKey != null) {
        log.info("AI: OpenAI Responses API attiva (model={})", config.openAiModel)
        OpenAiClient(config.openAiApiKey, config.openAiModel)
    } else {
        log.warn("AI: OPENAI_API_KEY assente -> modalità demo (MockAiClient)")
        MockAiClient()
    }
    val pipeline = EmailPipeline(ai, storage)
    val notifications = NotificationService(config)

    JobWorker(pipeline, notifications, CoroutineScope(SupervisorJob() + Dispatchers.IO)).start()

    if (config.authEnabled) {
        log.info("Auth: Auth0 attiva (domain={}, audience={})", config.auth0Domain, config.auth0Audience)
    } else {
        log.warn("Auth: AUTH0_DOMAIN/AUTH0_AUDIENCE assenti -> modalità dev senza autenticazione")
    }

    embeddedServer(Netty, port = config.port) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }
        install(CallLogging)
        // S7 (lato backend) — header di sicurezza sulle risposte API. La CSP/HSTS del
        // sito è responsabilità del frontend/reverse-proxy; qui blindiamo le risposte API.
        install(DefaultHeaders) {
            header("X-Content-Type-Options", "nosniff")
            header("Referrer-Policy", "no-referrer")
            header("X-Frame-Options", "DENY")
            if (config.isProduction) {
                header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            }
        }
        install(CORS) {
            // S6/S7 — origini configurabili per ambiente. In dev: localhost:3000.
            config.corsAllowedHosts.forEach { host ->
                if (config.isProduction) allowHost(host, schemes = listOf("https"))
                else allowHost(host)
            }
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            // X-Company-Id serve solo al ramo dev (senza JWT non c'è altro modo di scegliere
            // il tenant). Con auth attiva il server lo ignora comunque: non esporlo in produzione.
            if (!config.isProduction) allowHeader("X-Company-Id")
            allowHeader("X-Webhook-Token")
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
        }
        // S5 — rate limiting. Registriamo un limiter dedicato all'endpoint pubblico del webhook,
        // con chiave sull'IP di origine così un mittente rumoroso non satura il servizio.
        install(RateLimit) {
            register(RateLimitName(WEBHOOK_RATE_LIMIT)) {
                rateLimiter(limit = 60, refillPeriod = 1.minutes)
                requestKey { call -> call.request.origin.remoteHost }
            }
        }
        if (config.authEnabled) {
            val jwkProvider = JwkProviderBuilder(config.auth0Domain)
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build()
            install(Authentication) {
                jwt("auth0") {
                    verifier(jwkProvider, "https://${config.auth0Domain}/") {
                        withAudience(config.auth0Audience)
                        acceptLeeway(3)
                    }
                    validate { credential -> JWTPrincipal(credential.payload) }
                    challenge { _, _ ->
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "token mancante o non valido"),
                        )
                    }
                }
            }
        }
        install(StatusPages) {
            // S4 — niente info disclosure: al client va un messaggio generico + id di
            // correlazione; il dettaglio completo (con lo stesso id) resta nei log server.
            exception<Throwable> { call, cause ->
                val correlationId = UUID.randomUUID().toString()
                LoggerFactory.getLogger("com.opsinbox.Application")
                    .error("Errore non gestito [correlationId={}]", correlationId, cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "error" to "Errore interno del server",
                        "correlationId" to correlationId,
                    ),
                )
            }
        }
        routing {
            get("/health") { call.respond(mapOf("status" to "ok")) }
            webhookRoutes(config, storage) // protetto dal token webhook + rate limit, non da Auth0
            if (config.authEnabled) {
                authenticate("auth0") {
                    apiRoutes(config, storage)
                    onboardingRoutes(config)
                    searchRoutes(config, ai)
                    settingsRoutes(config)
                }
            } else {
                apiRoutes(config, storage)
                onboardingRoutes(config)
                searchRoutes(config, ai)
                settingsRoutes(config)
            }
        }
    }.start(wait = true)
}
