package com.opsinbox.jobs

import com.opsinbox.notify.NotificationService
import com.opsinbox.pipeline.EmailPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID

class JobWorker(
    private val pipeline: EmailPipeline,
    private val notifications: NotificationService,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 2000,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "worker-" + UUID.randomUUID().toString().take(8)

    fun start() {
        scope.launch {
            log.info("Job worker {} avviato", workerId)
            while (isActive) {
                val job = runCatching { JobQueue.claimNext(workerId) }
                    .onFailure { log.error("Errore nel poll della coda", it) }
                    .getOrNull()
                if (job == null) {
                    delay(pollIntervalMs)
                    continue
                }
                try {
                    handle(job)
                    JobQueue.markDone(job.id)
                } catch (e: Exception) {
                    log.error("Job ${job.id} (${job.type}) fallito al tentativo ${job.attempts}", e)
                    JobQueue.markFailed(job, e.message ?: e.toString())
                }
            }
        }
    }

    private suspend fun handle(job: ClaimedJob) {
        when (job.type) {
            "process_email" -> {
                val emailId = UUID.fromString(job.payload.getValue("emailId").jsonPrimitive.content)
                pipeline.process(emailId)
            }
            "send_notification" -> {
                val notificationId = UUID.fromString(job.payload.getValue("notificationId").jsonPrimitive.content)
                notifications.dispatch(notificationId)
            }
            else -> throw IllegalArgumentException("Tipo di job sconosciuto: ${job.type}")
        }
    }
}
