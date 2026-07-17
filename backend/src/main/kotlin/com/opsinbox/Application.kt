package com.opsinbox

import com.opsinbox.config.AppConfig
import com.opsinbox.db.DatabaseFactory
import com.opsinbox.jobs.JobWorker
import com.opsinbox.pipeline.AiClient
import com.opsinbox.pipeline.EmailPipeline
import com.opsinbox.pipeline.MockAiClient
import com.opsinbox.pipeline.OpenAiClient
import com.auth0.jwk.JwkProviderBuilder
import com.opsinbox.notify.NotificationService
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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("com.opsinbox.Application")
    val config = AppConfig.fromEnv()

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
        install(CORS) {
            allowHost("localhost:3000")
            allowHost("127.0.0.1:3000")
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowHeader("X-Company-Id")
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
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
            exception<Throwable> { call, cause ->
                LoggerFactory.getLogger("com.opsinbox.Application").error("Errore non gestito", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (cause.message ?: "Errore interno")),
                )
            }
        }
        routing {
            get("/health") { call.respond(mapOf("status" to "ok")) }
            webhookRoutes(config, storage) // protetto dal token webhook, non da Auth0
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
