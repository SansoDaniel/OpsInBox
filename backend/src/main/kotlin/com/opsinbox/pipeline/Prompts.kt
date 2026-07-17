package com.opsinbox.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object Prompts {

    val SYSTEM = """
        You are the AI engine of an "operations inbox" used by small companies
        (construction, manufacturing, installers, engineering, distribution).
        You receive one business email (subject, body, attachments) and must return
        a single JSON object that classifies it, extracts business documents,
        summarizes it and proposes concrete tasks.

        Rules:
        - Return ONLY JSON conforming to the provided schema. No prose.
        - NEVER invent data: if a field is not present in the email or attachments, use null.
        - category is exactly one of: invoice, quote, order, complaint, customer_request,
          appointment, contract, delivery_note, other.
        - summary: max 50 words, same language as the email, written for a busy business
          owner (what it is, who sent it, key amounts/dates, whether action is needed).
        - documents: one entry per business document found (invoice, quote, order,
          delivery note, contract). Dates in YYYY-MM-DD. Amounts as plain numbers
          (1285.90, not "€ 1.285,90"). confidence between 0 and 1.
        - tasks: concrete business actions in the email's language (e.g. "Approva pagamento
          fattura INV-234", "Rispondi al cliente"). priority is low, medium or high.
          dueDate in YYYY-MM-DD only when clearly derivable, otherwise null.
        - If the email is spam or requires no action, use category "other" and no tasks.
    """.trimIndent()

    fun userText(email: EmailForAnalysis): String = buildString {
        appendLine("From: ${email.fromName ?: ""} <${email.fromAddress}>")
        appendLine("Subject: ${email.subject ?: "(nessun oggetto)"}")
        appendLine()
        appendLine(email.bodyText?.take(8000) ?: "(corpo vuoto)")
        email.attachments.forEach { att ->
            val isText = att.contentType?.startsWith("text/") == true ||
                att.filename.endsWith(".txt", ignoreCase = true) ||
                att.filename.endsWith(".csv", ignoreCase = true)
            if (isText) {
                appendLine()
                appendLine("--- Attachment: ${att.filename} ---")
                appendLine(att.bytes.decodeToString().take(4000))
            }
        }
    }

    // Schema per Structured Outputs (strict): tutti i campi required, nullable dove opzionali.
    private val SCHEMA = """
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["category", "summary", "language", "confidence", "documents", "tasks"],
      "properties": {
        "category": {
          "type": "string",
          "enum": ["invoice", "quote", "order", "complaint", "customer_request",
                   "appointment", "contract", "delivery_note", "other"]
        },
        "summary": { "type": "string" },
        "language": { "type": ["string", "null"] },
        "confidence": { "type": "number" },
        "documents": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["docType", "supplierName", "customerName", "documentNumber",
                         "docDate", "dueDate", "amount", "currency", "lineItems", "confidence"],
            "properties": {
              "docType": { "type": "string" },
              "supplierName": { "type": ["string", "null"] },
              "customerName": { "type": ["string", "null"] },
              "documentNumber": { "type": ["string", "null"] },
              "docDate": { "type": ["string", "null"] },
              "dueDate": { "type": ["string", "null"] },
              "amount": { "type": ["number", "null"] },
              "currency": { "type": ["string", "null"] },
              "confidence": { "type": ["number", "null"] },
              "lineItems": {
                "type": "array",
                "items": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["description", "quantity", "unitPrice", "total"],
                  "properties": {
                    "description": { "type": ["string", "null"] },
                    "quantity": { "type": ["number", "null"] },
                    "unitPrice": { "type": ["number", "null"] },
                    "total": { "type": ["number", "null"] }
                  }
                }
              }
            }
          }
        },
        "tasks": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["title", "description", "type", "priority", "dueDate"],
            "properties": {
              "title": { "type": "string" },
              "description": { "type": ["string", "null"] },
              "type": { "type": ["string", "null"] },
              "priority": { "type": ["string", "null"], "enum": ["low", "medium", "high", null] },
              "dueDate": { "type": ["string", "null"] }
            }
          }
        }
      }
    }
    """.trimIndent()

    val schemaJson: JsonObject by lazy { Json.parseToJsonElement(SCHEMA).jsonObject }

    val SEARCH_SYSTEM = """
        You translate a natural-language search query from a small business owner
        (Italian or English) into a JSON filter for their operations inbox.

        Fields:
        - target: "documents" for invoices/quotes/orders/delivery notes/contracts;
          "emails" for complaints, customer requests, appointments or generic text search;
          "tasks" for activities/todos.
        - docType: invoice|quote|order|delivery_note|contract for documents; for emails
          the category: complaint|customer_request|appointment. Null if not implied.
        - counterpartyContains: supplier/customer/company name if mentioned.
        - amountMin/amountMax: plain numbers ("sopra 5000" -> amountMin 5000).
        - dueFrom/dueTo: ISO dates for deadline ranges ("in scadenza questo mese").
          dateFrom/dateTo: ISO dates for document/email dates. Resolve relative
          expressions using the provided today's date.
        - openTasksOnly: true when the user implies unhandled/unpaid/pending items
          ("non pagate", "in sospeso", "da gestire").
        - textContains: significant free text not captured by other fields.

        Return ONLY JSON conforming to the schema. Use null for anything not implied.
        Never invent filters the user did not ask for.
    """.trimIndent()

    private val SEARCH_SCHEMA = """
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["target", "docType", "counterpartyContains", "amountMin", "amountMax",
                   "dueFrom", "dueTo", "dateFrom", "dateTo", "openTasksOnly", "textContains"],
      "properties": {
        "target": { "type": ["string", "null"], "enum": ["documents", "emails", "tasks", null] },
        "docType": { "type": ["string", "null"] },
        "counterpartyContains": { "type": ["string", "null"] },
        "amountMin": { "type": ["number", "null"] },
        "amountMax": { "type": ["number", "null"] },
        "dueFrom": { "type": ["string", "null"] },
        "dueTo": { "type": ["string", "null"] },
        "dateFrom": { "type": ["string", "null"] },
        "dateTo": { "type": ["string", "null"] },
        "openTasksOnly": { "type": ["boolean", "null"] },
        "textContains": { "type": ["string", "null"] }
      }
    }
    """.trimIndent()

    val searchSchemaJson: JsonObject by lazy { Json.parseToJsonElement(SEARCH_SCHEMA).jsonObject }
}
