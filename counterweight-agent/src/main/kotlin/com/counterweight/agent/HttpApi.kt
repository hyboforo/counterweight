package com.counterweight.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The five routes the till talks to, on loopback only.
 *
 * Bound to 127.0.0.1 rather than every interface: a print agent reachable from
 * the shop LAN is a printer anybody on the wifi can drive, and the till that
 * needs it is the machine it runs on.
 *
 * The browser is what makes this awkward and what makes it work. Nothing on the
 * network can route to a service behind a till's loopback, so the page in front
 * of the cashier is the only thing that can reach both it and the server —
 * which also means any *other* page in that browser can reach the agent. Hence
 * the origin allow-list below, checked before anything is printed rather than
 * left to the browser's own CORS enforcement.
 */
class Api(
    private val config: Config,
    private val spool: Spool,
    private val transport: Transport,
    private val json: ObjectMapper,
) {

    /**
     * One thread, so two jobs never interleave on one printer.
     *
     * Interleaved ESC/POS is not a race that produces a wrong number — it
     * produces half of one receipt inside another, which is the sort of thing
     * a shop photographs and sends to whoever built the system.
     */
    private val printer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "counterweight-printer").apply { isDaemon = false }
    }

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port), 0)
        server.createContext("/") { exchange -> exchange.use { route(it) } }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
        return server
    }

    private fun route(exchange: HttpExchange) {
        val origin = exchange.requestHeaders.getFirst("Origin")
        if (!originAllowed(origin)) {
            log("refused ${exchange.requestURI.path} from origin $origin — add it to allowedOrigins if this is the shop server")
            respond(exchange, 403, mapOf("error" to "This origin is not allowed to use the printer."))
            return
        }
        cors(exchange, origin)

        if (exchange.requestMethod == "OPTIONS") {
            exchange.sendResponseHeaders(204, -1)
            return
        }
        if (!authorised(exchange)) {
            respond(exchange, 401, mapOf("error" to "Pairing token missing or wrong."))
            return
        }

        val path = exchange.requestURI.path.trimEnd('/')
        val method = exchange.requestMethod

        try {
            when {
                method == "GET" && path == "/v1/status" -> status(exchange)
                method == "GET" && path == "/v1/printers" -> printers(exchange)
                method == "POST" && path == "/v1/print" -> print(exchange)
                method == "GET" && path.startsWith("/v1/jobs/") && !path.endsWith("/retry") ->
                    jobStatus(exchange, path.removePrefix("/v1/jobs/"))
                method == "POST" && path.startsWith("/v1/jobs/") && path.endsWith("/retry") ->
                    retry(exchange, path.removePrefix("/v1/jobs/").removeSuffix("/retry"))
                else -> respond(exchange, 404, mapOf("error" to "No such route."))
            }
        } catch (e: Exception) {
            log("unhandled on $method $path: ${e.message}")
            respond(exchange, 500, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    /* ── Routes ─────────────────────────────────────────────────────────── */

    /**
     * `paperOut` and `coverOpen` are null through the Windows spooler, and that
     * is reported rather than guessed.
     *
     * Reading them needs `DLE EOT` and a transport that answers back; the
     * spooler is write-only, so the agent cannot know. §14.1 already has the
     * answer for that — the sale completes, the job spools, the till offers a
     * reprint — and a hopeful `paperOut: false` would only tell the counter
     * something nobody checked.
     */
    private fun status(exchange: HttpExchange) = respond(
        exchange, 200,
        mapOf(
            "paired" to config.token.isNotBlank(),
            "transport" to transport.describe,
            "printers" to runCatching { SpoolerTransport.available() }.getOrDefault(emptyList()),
            "paperOut" to null,
            "coverOpen" to null,
            "statusReadback" to false,
            "queueDepth" to spool.depth(),
        ),
    )

    private fun printers(exchange: HttpExchange) = respond(
        exchange, 200,
        mapOf(
            "spooler" to runCatching { SpoolerTransport.available() }.getOrDefault(emptyList()),
            "configured" to transport.describe,
        ),
    )

    /**
     * Prints, and answers only when it is done.
     *
     * The till marks the server's copy of the job DONE on any 2xx, so
     * answering early would report a receipt as printed while it was still on
     * its way to a printer with no paper in it — and the reprint the shop is
     * owed would never be offered. Bounded, because the other failure is a
     * relay tick that never returns.
     */
    private fun print(exchange: HttpExchange) {
        val job = json.readValue(exchange.requestBody, PrintJob::class.java)
        var status = spool.accept(job)

        val task = printer.submit {
            status = spool.record(status.copy(state = JobState.PRINTING, attempts = status.attempts + 1))
            val bytes = EscPos.encode(job)
            repeat(job.copies.coerceIn(1, 5)) { transport.send(bytes) }
        }

        status = try {
            task.get(20, TimeUnit.SECONDS)
            spool.record(status.copy(state = JobState.DONE, error = null))
        } catch (e: TimeoutException) {
            task.cancel(true)
            spool.record(status.copy(state = JobState.FAILED, error = "the printer did not answer within 20s"))
        } catch (e: Exception) {
            val cause = e.cause ?: e
            spool.record(status.copy(state = JobState.FAILED, error = cause.message ?: cause.javaClass.simpleName))
        }

        if (status.state == JobState.DONE) {
            log("printed ${job.template} ${job.id}")
            respond(exchange, 200, status)
        } else {
            log("failed ${job.template} ${job.id}: ${status.error}")
            respond(exchange, 502, status)
        }
    }

    private fun jobStatus(exchange: HttpExchange, jobId: String) {
        val status = spool.status(jobId)
        if (status == null) respond(exchange, 404, mapOf("error" to "No job $jobId on this till."))
        else respond(exchange, 200, status)
    }

    /** Re-sends a spooled job. What "print it again" is, and why the spool exists. */
    private fun retry(exchange: HttpExchange, jobId: String) {
        val job = spool.job(jobId)
        if (job == null) {
            respond(exchange, 404, mapOf("error" to "No job $jobId on this till."))
            return
        }
        var status = spool.status(jobId) ?: spool.accept(job)
        status = try {
            printer.submit {
                spool.record(status.copy(state = JobState.PRINTING, attempts = status.attempts + 1))
                transport.send(EscPos.encode(job))
            }.get(20, TimeUnit.SECONDS)
            spool.record(status.copy(state = JobState.DONE, attempts = status.attempts + 1, error = null))
        } catch (e: Exception) {
            val cause = e.cause ?: e
            spool.record(
                status.copy(
                    state = JobState.FAILED,
                    attempts = status.attempts + 1,
                    error = cause.message ?: cause.javaClass.simpleName,
                )
            )
        }
        respond(exchange, if (status.state == JobState.DONE) 200 else 502, status)
    }

    /* ── Access ─────────────────────────────────────────────────────────── */

    /**
     * A request with no `Origin` is not from a page — curl, the install
     * check, a scheduled test print — and is allowed. That is not a hole the
     * allow-list could close anyway: anything running locally can read the
     * config file this token lives in.
     */
    private fun originAllowed(origin: String?): Boolean {
        if (origin == null) return true
        if (config.allowedOrigins.any { it.equals(origin, ignoreCase = true) }) return true
        return Regex("""^https?://(localhost|127\.0\.0\.1)(:\d+)?$""").matches(origin)
    }

    private fun authorised(exchange: HttpExchange): Boolean {
        if (config.token.isBlank()) return true
        return exchange.requestHeaders.getFirst("X-Counterweight-Agent-Token") == config.token
    }

    private fun cors(exchange: HttpExchange, origin: String?) {
        val headers = exchange.responseHeaders
        headers.add("Access-Control-Allow-Origin", origin ?: "*")
        headers.add("Vary", "Origin")
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        headers.add("Access-Control-Allow-Headers", "Content-Type, X-Counterweight-Agent-Token")
        headers.add("Access-Control-Max-Age", "600")
    }

    private fun respond(exchange: HttpExchange, code: Int, body: Any) {
        val bytes = json.writeValueAsBytes(body)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
