package com.counterweight.alerting.service

import com.counterweight.alerting.domain.Alert
import com.counterweight.alerting.domain.AlertRule
import com.counterweight.alerting.repo.AlertQueryRepository
import com.counterweight.identity.security.Auth
import com.counterweight.platform.service.BranchService
import com.counterweight.pricing.service.Money
import com.counterweight.printing.domain.QueuedPrintJob
import com.counterweight.printing.model.Align
import com.counterweight.printing.model.DocumentBuilder
import com.counterweight.printing.model.PrintDocument
import com.counterweight.printing.service.PrintQueueService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The printed morning briefing, and the cash-variance listener.
 *
 * §11.3 is blunt about why the briefing exists: in a shop where the owner is on
 * the floor rather than at a screen, a thermal slip at open of business is the
 * channel that actually gets read. An in-app notification centre that nobody
 * opens is not a channel, however complete it is.
 *
 * Note the direction: alerting depends on printing, never the reverse. Printing
 * knows nothing about alerts — it is handed a document like any other.
 */
@Service
class MorningBriefingService(
    private val alerts: AlertService,
    private val queries: AlertQueryRepository,
    private val evaluator: ScheduledAlertEvaluator,
    private val branches: BranchService,
    private val queue: PrintQueueService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Builds and queues the briefing slip.
     *
     * Everything on it is something that happened while nobody was watching, or
     * that needs a decision before the doors open: what expired overnight, what
     * fell below reorder, yesterday's variance, who is overdue. Deliberately
     * short — a slip that runs to a foot of paper gets glanced at and binned.
     */
    @Transactional
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun printBriefing(tillCode: String): QueuedPrintJob {
        val branchId = Auth.current().branchId
        return queue.enqueue(
            tillCode = tillCode,
            template = QueuedPrintJob.MORNING_BRIEFING,
            document = build(branchId),
        )
    }

    fun build(branchId: Long): PrintDocument {
        val doc = DocumentBuilder(queue.widthChars)
        val since = Instant.now().minus(1, ChronoUnit.DAYS)

        doc.title("MORNING BRIEFING")
        doc.centre(branches.get(branchId).name)
        doc.centre(DocumentBuilder.format(LocalDate.now()))
        doc.rule()

        val overnight = alerts.raisedSince(since)
        val open = alerts.open()

        // ── What must not be sold ──
        val expired = open.filter { it.severity == AlertRule.CRITICAL }
        section(doc, "NEEDS ATTENTION", expired) { "${it.title} — ${it.body}" }

        // ── What to order ──
        val short = queries.belowReorderPoint(branchId)
        if (short.isEmpty()) {
            doc.centre("Nothing below reorder point")
        } else {
            doc.line()
            doc.centre("TO ORDER", bold = true)
            short.take(MAX_LINES).forEach { row ->
                doc.columns(
                    row.name.take(24),
                    "${row.onHand.stripTrailingZeros().toPlainString()}/${row.reorderPoint.stripTrailingZeros().toPlainString()}",
                )
            }
            if (short.size > MAX_LINES) doc.line("  ...and ${short.size - MAX_LINES} more")
        }

        // ── Who owes ──
        val overdue = queries.overdueCustomers(branchId, LocalDate.now())
        if (overdue.isNotEmpty()) {
            doc.line()
            doc.centre("OVERDUE ACCOUNTS", bold = true)
            overdue.take(MAX_LINES).forEach { row ->
                doc.columns(
                    "${row.name.take(20)} (${row.daysOverdue}d)",
                    Money.round(row.overdueAmount).toPlainString(),
                )
            }
            val total = overdue.fold(java.math.BigDecimal.ZERO) { acc, r -> acc.add(r.overdueAmount) }
            doc.rule()
            doc.columns("Total overdue", Money.round(total).toPlainString())
        }

        // ── Raised overnight ──
        val newSinceYesterday = overnight.filter { it.severity != AlertRule.CRITICAL }
        section(doc, "RAISED OVERNIGHT", newSinceYesterday) { it.title }

        doc.rule()
        doc.centre("${open.size} open, ${overnight.size} raised in 24h")
        doc.cut()
        return doc.build()
    }

    private fun section(
        doc: DocumentBuilder,
        heading: String,
        items: List<Alert>,
        render: (Alert) -> String,
    ) {
        if (items.isEmpty()) return
        doc.line()
        doc.centre(heading, bold = true)
        items.take(MAX_LINES).forEach { doc.wrapped("- ${render(it)}", Align.LEFT) }
        if (items.size > MAX_LINES) doc.line("  ...and ${items.size - MAX_LINES} more")
    }

    private companion object {
        /** A slip a foot long gets binned. Detail lives in the notification centre. */
        const val MAX_LINES = 8
    }
}
