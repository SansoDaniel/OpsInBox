package com.opsinbox.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb

val dbJson = Json { ignoreUnknownKeys = true }

object Companies : UUIDTable("companies") {
    val name = text("name")
    val vatNumber = text("vat_number").nullable()
    val inboundAddress = text("inbound_address").nullable()
    val notificationEmail = text("notification_email").nullable()
    val slackWebhookUrl = text("slack_webhook_url").nullable()
    val teamsWebhookUrl = text("teams_webhook_url").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

object Users : UUIDTable("users") {
    val companyId = reference("company_id", Companies)
    val auth0Sub = text("auth0_sub").nullable()
    val email = text("email")
    val name = text("name").nullable()
    val role = text("role")
    val createdAt = timestampWithTimeZone("created_at")
}

object Contacts : UUIDTable("contacts") {
    val companyId = reference("company_id", Companies)
    val type = text("type")
    val name = text("name")
    val email = text("email").nullable()
    val vatNumber = text("vat_number").nullable()
    val phone = text("phone").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

object Emails : UUIDTable("emails") {
    val companyId = reference("company_id", Companies)
    val messageId = text("message_id")
    val fromAddress = text("from_address")
    val fromName = text("from_name").nullable()
    val toAddress = text("to_address").nullable()
    val subject = text("subject").nullable()
    val bodyText = text("body_text").nullable()
    val bodyHtml = text("body_html").nullable()
    val receivedAt = timestampWithTimeZone("received_at")
    val status = text("status")
    val category = text("category").nullable()
    val summary = text("summary").nullable()
    val language = text("language").nullable()
    val error = text("error").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

object Attachments : UUIDTable("attachments") {
    val emailId = reference("email_id", Emails)
    val filename = text("filename")
    val contentType = text("content_type").nullable()
    val sizeBytes = long("size_bytes")
    val storageKey = text("storage_key")
    val createdAt = timestampWithTimeZone("created_at")
}

object Documents : UUIDTable("documents") {
    val companyId = reference("company_id", Companies)
    val emailId = reference("email_id", Emails).nullable()
    val attachmentId = reference("attachment_id", Attachments).nullable()
    val contactId = reference("contact_id", Contacts).nullable()
    val docType = text("doc_type")
    val supplierName = text("supplier_name").nullable()
    val customerName = text("customer_name").nullable()
    val documentNumber = text("document_number").nullable()
    val docDate = date("doc_date").nullable()
    val dueDate = date("due_date").nullable()
    val amount = decimal("amount", 14, 2).nullable()
    val currency = text("currency")
    val lineItems = jsonb<JsonElement>("line_items", dbJson)
    val rawExtraction = jsonb<JsonElement>("raw_extraction", dbJson).nullable()
    val confidence = decimal("confidence", 4, 3).nullable()
    val dedupKey = text("dedup_key")
    val createdAt = timestampWithTimeZone("created_at")
}

object Tasks : UUIDTable("tasks") {
    val companyId = reference("company_id", Companies)
    val emailId = reference("email_id", Emails).nullable()
    val documentId = reference("document_id", Documents).nullable()
    val title = text("title")
    val description = text("description").nullable()
    val type = text("type")
    val priority = text("priority")
    val dueDate = date("due_date").nullable()
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val resolvedAt = timestampWithTimeZone("resolved_at").nullable()
}

object Notifications : UUIDTable("notifications") {
    val companyId = reference("company_id", Companies)
    val taskId = reference("task_id", Tasks).nullable()
    val channel = text("channel")
    val payload = jsonb<JsonElement>("payload", dbJson)
    val status = text("status")
    val sentAt = timestampWithTimeZone("sent_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

object Jobs : LongIdTable("jobs") {
    val type = text("type")
    val payload = jsonb<JsonElement>("payload", dbJson)
    val status = text("status")
    val attempts = integer("attempts")
    val maxAttempts = integer("max_attempts")
    val runAt = timestampWithTimeZone("run_at")
    val lockedAt = timestampWithTimeZone("locked_at").nullable()
    val lockedBy = text("locked_by").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}
