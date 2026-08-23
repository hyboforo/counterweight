package com.counterweight.alerting.service

import com.counterweight.alerting.domain.NotificationOutbox
import com.counterweight.alerting.repo.NotificationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class OutboxStatus(val pending: Long, val sent: Long, val failed: Long, val abandoned: Long)

/**
 * Outbound notifications, queue-and-forward.
 *
 * The shop has no internet on the sale path, so **nothing ever waits on this**.
 * A till that blocks because an SMS gateway is unreachable has stopped selling,
 * which is a worse outcome than a message arriving late or never.
 *
 * There is no gateway wired in yet, and that is a deliberate stopping point
 * rather than an oversight: choosing one is a commercial decision about a
 * Ghanaian aggregator, and the destination it would send to is configuration
 * `platform` will own. What exists here is the queue, the retry accounting and
 * the seam a gateway drops into — so that adding one is implementing
 * [NotificationGateway] and nothing else.
 */
interface NotificationGateway {
    /** Channel this gateway handles: SMS or EMAIL. */
    fun channel(): String

    /** Delivers, or throws. Must not retry internally — the outbox counts attempts. */
    fun send(destination: String, payload: String)
}

@Service
class NotificationOutboxService(
    private val outbox: NotificationOutboxRepository,
    private val gateways: List<NotificationGateway>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val byChannel: Map<String, NotificationGateway> by lazy {
        gateways.associateBy { it.channel() }
    }

    /**
     * Attempts every pending message.
     *
     * Called when a connection is known to exist rather than on a timer, since
     * the shop's connection is intermittent and a scheduler would spend most of
     * its life failing. Each message is counted and left for the next drain;
     * past [MAX_ATTEMPTS] it is abandoned rather than retried for ever.
     *
     * A message is kept once abandoned. A notification nobody managed to send
     * is worth knowing about — silently deleting it is how "I never got told"
     * becomes unanswerable.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ALERT_MANAGE')")
    fun drain(): OutboxStatus {
        val pending = outbox.pending()
        if (pending.isEmpty()) return status()

        pending.forEach { message ->
            val gateway = byChannel[message.channel]
            if (gateway == null) {
                // No gateway for this channel. Not a failure of the message —
                // there is nothing to attempt — so it stays pending rather than
                // burning an attempt it never had.
                log.debug("no gateway configured for {}; leaving message {} queued", message.channel, message.id)
                return@forEach
            }
            if (message.destination.isBlank()) {
                message.attempts = (message.attempts + 1).toShort()
                message.lastError = "No destination configured for this channel."
                if (message.attempts >= MAX_ATTEMPTS) message.status = NotificationOutbox.ABANDONED
                outbox.save(message)
                return@forEach
            }

            runCatching { gateway.send(message.destination, message.payload) }
                .onSuccess {
                    message.status = NotificationOutbox.SENT
                    message.sentAt = Instant.now()
                    message.lastError = null
                    outbox.save(message)
                }
                .onFailure { failure ->
                    message.attempts = (message.attempts + 1).toShort()
                    message.lastError = failure.message?.take(500)
                    message.status = if (message.attempts >= MAX_ATTEMPTS) {
                        log.warn("giving up on notification {} after {} attempts", message.id, message.attempts)
                        NotificationOutbox.ABANDONED
                    } else {
                        NotificationOutbox.PENDING
                    }
                    outbox.save(message)
                }
        }
        return status()
    }

    @Transactional(readOnly = true)
    fun status() = OutboxStatus(
        pending = outbox.countByStatus(NotificationOutbox.PENDING),
        sent = outbox.countByStatus(NotificationOutbox.SENT),
        failed = outbox.countByStatus(NotificationOutbox.FAILED),
        abandoned = outbox.countByStatus(NotificationOutbox.ABANDONED),
    )

    private companion object { const val MAX_ATTEMPTS = 5 }
}
