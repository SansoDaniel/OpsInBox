package com.opsinbox.pipeline

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AttachmentInput(
    val filename: String,
    val contentType: String?,
    val bytes: ByteArray,
)

class EmailForAnalysis(
    val fromAddress: String,
    val fromName: String?,
    val subject: String?,
    val bodyText: String?,
    val attachments: List<AttachmentInput>,
)

@Serializable
data class AiLineItem(
    val description: String? = null,
    val quantity: Double? = null,
    val unitPrice: Double? = null,
    val total: Double? = null,
)

@Serializable
data class AiDocument(
    val docType: String,
    val supplierName: String? = null,
    val customerName: String? = null,
    val documentNumber: String? = null,
    val docDate: String? = null,
    val dueDate: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val lineItems: List<AiLineItem> = emptyList(),
    val confidence: Double? = null,
)

@Serializable
data class AiTask(
    val title: String,
    val description: String? = null,
    val type: String? = null,
    val priority: String? = null,
    val dueDate: String? = null,
)

@Serializable
data class AiAnalysis(
    val category: String,
    val summary: String,
    val language: String? = null,
    val confidence: Double? = null,
    val documents: List<AiDocument> = emptyList(),
    val tasks: List<AiTask> = emptyList(),
)

/**
 * Filtro strutturato prodotto dalla traduzione di una query in linguaggio naturale.
 * L'LLM non genera mai SQL: produce questo JSON, il backend costruisce la query.
 */
@Serializable
data class SearchFilter(
    val target: String? = null,               // documents | emails | tasks
    val docType: String? = null,              // invoice, quote, order, ... (o categoria email)
    val counterpartyContains: String? = null, // nome fornitore/cliente
    val amountMin: Double? = null,
    val amountMax: Double? = null,
    val dueFrom: String? = null,              // ISO date
    val dueTo: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val openTasksOnly: Boolean? = null,       // "non pagate", "in sospeso", "da gestire"
    val textContains: String? = null,
)

interface AiClient {
    suspend fun analyze(email: EmailForAnalysis): AiAnalysis
    suspend fun parseSearchQuery(query: String, today: LocalDate): SearchFilter
}

/**
 * Client demo: nessuna chiamata esterna, euristiche su parole chiave.
 * Permette di provare l'intera pipeline (webhook -> job -> task -> dashboard)
 * senza una API key OpenAI. Con la chiave configurata si usa OpenAiClient.
 */
class MockAiClient : AiClient {

    override suspend fun analyze(email: EmailForAnalysis): AiAnalysis {
        val text = buildString {
            appendLine(email.subject ?: "")
            appendLine(email.bodyText ?: "")
            email.attachments.forEach { att ->
                val isText = att.contentType?.startsWith("text/") == true ||
                    att.filename.endsWith(".txt", ignoreCase = true)
                if (isText) appendLine(att.bytes.decodeToString())
            }
        }
        val lower = text.lowercase()

        val category = when {
            Regex("fattur|invoice").containsMatchIn(lower) -> "invoice"
            Regex("preventiv|quotation|\\bquote\\b").containsMatchIn(lower) -> "quote"
            Regex("ordine|\\border\\b").containsMatchIn(lower) -> "order"
            Regex("reclam|complaint|disservizio").containsMatchIn(lower) -> "complaint"
            Regex("\\bddt\\b|bolla di consegna|delivery note").containsMatchIn(lower) -> "delivery_note"
            Regex("appuntament|appointment|sopralluogo|meeting").containsMatchIn(lower) -> "appointment"
            Regex("contratt|contract").containsMatchIn(lower) -> "contract"
            Regex("richiest|request|informazioni").containsMatchIn(lower) -> "customer_request"
            else -> "other"
        }

        val supplier = email.fromName ?: email.fromAddress.substringAfter('@').substringBefore('.')
        val number = Regex("(?:inv|fattura|preventivo|ordine|ddt)[\\s\\-n.°:]*([A-Za-z]*-?\\d[\\w-]*)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        // valuta prima o dopo il numero: "€ 1.285,90" oppure "1.285,90 EUR"
        // la cattura deve iniziare con una cifra (evita di agganciare "EUR," -> ",")
        val amount = (
            Regex("(?:€|eur[o]?)\\s*(\\d[\\d.,]*)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                ?: Regex("(\\d[\\d.,]*)\\s*(?:€|eur\\b)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            )?.let(::parseItalianAmount)
        val dueDate = findDate(text, "scadenza|due|entro")

        val documents = if (category in setOf("invoice", "quote", "order", "delivery_note") &&
            (number != null || amount != null)
        ) {
            listOf(
                AiDocument(
                    docType = category,
                    supplierName = supplier,
                    documentNumber = number,
                    dueDate = dueDate?.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    amount = amount,
                    currency = "EUR",
                    confidence = 0.5,
                )
            )
        } else emptyList()

        val amountLabel = amount?.let { " di €$it" } ?: ""
        val task = when (category) {
            "invoice" -> AiTask(
                title = "Approva pagamento fattura ${number ?: ""} – $supplier$amountLabel".trim(),
                description = dueDate?.let { "Scadenza: $it. Promemoria consigliato 3 giorni prima." },
                type = "approve_payment", priority = "high",
                dueDate = dueDate?.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
            "quote" -> AiTask("Valuta preventivo ${number ?: ""} da $supplier".trim(), type = "review_quote", priority = "medium")
            "order" -> AiTask("Conferma ordine ${number ?: ""} da $supplier".trim(), type = "confirm_order", priority = "high")
            "complaint" -> AiTask("Gestisci reclamo da $supplier", type = "handle_complaint", priority = "high")
            "delivery_note" -> AiTask("Verifica merce ricevuta (DDT ${number ?: ""})".trim(), type = "check_delivery", priority = "medium")
            "appointment" -> AiTask("Conferma appuntamento con $supplier", type = "confirm_appointment", priority = "medium")
            "contract" -> AiTask("Rivedi contratto da $supplier", type = "review_contract", priority = "medium")
            "customer_request" -> AiTask("Rispondi alla richiesta di $supplier", type = "reply_customer", priority = "medium")
            else -> null
        }

        val summary = buildString {
            append(
                when (category) {
                    "invoice" -> "Fattura ${number ?: ""} da $supplier".trim()
                    "quote" -> "Preventivo da $supplier"
                    "order" -> "Ordine da $supplier"
                    "complaint" -> "Reclamo da $supplier"
                    "delivery_note" -> "DDT da $supplier"
                    "appointment" -> "Richiesta appuntamento da $supplier"
                    "contract" -> "Contratto da $supplier"
                    "customer_request" -> "Richiesta cliente da $supplier"
                    else -> "Email da $supplier"
                }
            )
            amount?.let { append(", importo €$it") }
            dueDate?.let { append(", scadenza $it") }
            append(". [analisi demo senza OpenAI]")
        }

        return AiAnalysis(
            category = category,
            summary = summary,
            language = if (Regex("fattur|preventiv|ordine|gentile|cordiali").containsMatchIn(lower)) "it" else "en",
            confidence = 0.5,
            documents = documents,
            tasks = listOfNotNull(task),
        )
    }

    override suspend fun parseSearchQuery(query: String, today: LocalDate): SearchFilter {
        val q = query.lowercase()

        val docType = when {
            Regex("fattur|invoice").containsMatchIn(q) -> "invoice"
            Regex("preventiv|quot").containsMatchIn(q) -> "quote"
            Regex("ordin|order").containsMatchIn(q) -> "order"
            Regex("\\bddt\\b|bolla|delivery").containsMatchIn(q) -> "delivery_note"
            Regex("contratt|contract").containsMatchIn(q) -> "contract"
            else -> null
        }
        // reclami/richieste/appuntamenti non sono documenti: si cerca tra le email
        val emailCategory = when {
            Regex("reclam|complaint").containsMatchIn(q) -> "complaint"
            Regex("richiest|request").containsMatchIn(q) -> "customer_request"
            Regex("appuntament|appointment").containsMatchIn(q) -> "appointment"
            else -> null
        }
        val target = when {
            emailCategory != null -> "emails"
            Regex("attivit|task|da fare|cose da").containsMatchIn(q) -> "tasks"
            docType != null -> "documents"
            else -> "emails"
        }

        val amountMin = Regex("(?:sopra|oltre|più di|piu di|maggiori? di|>|above|over)\\s*€?\\s*([\\d.,]+)")
            .find(q)?.groupValues?.get(1)?.let(::parseItalianAmount)
        val amountMax = Regex("(?:sotto|meno di|inferiori? a|<|below|under)\\s*€?\\s*([\\d.,]+)")
            .find(q)?.groupValues?.get(1)?.let(::parseItalianAmount)

        val openOnly = Regex("non pagat|da pagar|unpaid|in sospeso|apert|da gestir").containsMatchIn(q)

        val stopwords = setOf("pagare", "pagamento", "questo", "questa", "oggi", "ieri", "domani", "gestire")
        val counterparty = Regex("\\b(?:da|di|from)\\s+([\\p{L}0-9&][\\p{L}0-9&. ]{1,40})")
            .find(query)?.groupValues?.get(1)?.trim()
            ?.takeIf { c -> c.isNotBlank() && !c.first().isDigit() && c.split(" ").first().lowercase() !in stopwords }

        var rangeFrom: LocalDate? = null
        var rangeTo: LocalDate? = null
        if (Regex("questo mese|this month").containsMatchIn(q)) {
            rangeFrom = today.withDayOfMonth(1)
            rangeTo = today.withDayOfMonth(today.lengthOfMonth())
        }
        if (Regex("questa settimana|this week").containsMatchIn(q)) {
            rangeFrom = today
            rangeTo = today.plusDays(7)
        }
        val isDueRange = Regex("scad|due").containsMatchIn(q)

        return SearchFilter(
            target = target,
            docType = docType ?: emailCategory,
            counterpartyContains = counterparty,
            amountMin = amountMin,
            amountMax = amountMax,
            dueFrom = if (isDueRange) rangeFrom?.toString() else null,
            dueTo = if (isDueRange) rangeTo?.toString() else null,
            dateFrom = if (!isDueRange) rangeFrom?.toString() else null,
            dateTo = if (!isDueRange) rangeTo?.toString() else null,
            openTasksOnly = if (openOnly) true else null,
            textContains = if (target == "emails" && emailCategory == null) query.trim() else null,
        )
    }

    private fun parseItalianAmount(raw: String): Double? {
        val cleaned = raw.trim().trimEnd('.', ',')
        return when {
            // formato italiano: 1.285,90
            cleaned.contains(',') -> cleaned.replace(".", "").replace(',', '.').toDoubleOrNull()
            else -> cleaned.replace(",", "").toDoubleOrNull()
        }
    }

    private fun findDate(text: String, keywords: String): LocalDate? {
        val after = Regex("(?:$keywords)[^0-9]{0,20}(\\d{1,2})/(\\d{1,2})/(\\d{4})", RegexOption.IGNORE_CASE).find(text)
        if (after != null) {
            val (d, m, y) = after.destructured
            return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
        }
        val iso = Regex("(?:$keywords)[^0-9]{0,20}(\\d{4})-(\\d{2})-(\\d{2})", RegexOption.IGNORE_CASE).find(text)
        if (iso != null) {
            val (y, m, d) = iso.destructured
            return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
        }
        return null
    }
}
