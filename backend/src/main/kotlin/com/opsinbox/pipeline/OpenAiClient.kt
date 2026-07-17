package com.opsinbox.pipeline

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Client per la OpenAI Responses API con Structured Outputs.
 * Un'unica chiamata fa classificazione + estrazione + riassunto + generazione task.
 * I PDF e le immagini allegati vengono passati direttamente al modello (niente OCR separato).
 */
class OpenAiClient(
    private val apiKey: String,
    private val model: String,
) : AiClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    }

    override suspend fun analyze(email: EmailForAnalysis): AiAnalysis {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "input_text")
                put("text", Prompts.userText(email))
            })
            email.attachments.forEach { att ->
                val contentType = att.contentType ?: ""
                val base64 = { Base64.getEncoder().encodeToString(att.bytes) }
                when {
                    contentType == "application/pdf" || att.filename.endsWith(".pdf", ignoreCase = true) ->
                        add(buildJsonObject {
                            put("type", "input_file")
                            put("filename", att.filename)
                            put("file_data", "data:application/pdf;base64,${base64()}")
                        })
                    contentType.startsWith("image/") ->
                        add(buildJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:$contentType;base64,${base64()}")
                        })
                    // gli allegati testuali sono già inclusi in Prompts.userText
                }
            }
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("instructions", Prompts.SYSTEM)
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", content)
                })
            })
            put("text", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("name", "email_analysis")
                    put("strict", true)
                    put("schema", Prompts.schemaJson)
                })
            })
        }

        return json.decodeFromString(AiAnalysis.serializer(), postForOutputText(requestBody))
    }

    override suspend fun parseSearchQuery(query: String, today: java.time.LocalDate): SearchFilter {
        val requestBody = buildJsonObject {
            put("model", model)
            put("instructions", Prompts.SEARCH_SYSTEM)
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", "Today is $today.\nQuery: $query")
                        })
                    })
                })
            })
            put("text", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("name", "search_filter")
                    put("strict", true)
                    put("schema", Prompts.searchSchemaJson)
                })
            })
        }
        return json.decodeFromString(SearchFilter.serializer(), postForOutputText(requestBody))
    }

    private suspend fun postForOutputText(requestBody: kotlinx.serialization.json.JsonObject): String {
        val response = http.post("https://api.openai.com/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }
        val responseText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("OpenAI ${response.status.value}: ${responseText.take(500)}")
        }
        return json.parseToJsonElement(responseText).jsonObject["output"]?.jsonArray
            ?.flatMap { item -> item.jsonObject["content"]?.jsonArray ?: JsonArray(emptyList()) }
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: error("OpenAI: nessun output_text nella risposta")
    }
}
