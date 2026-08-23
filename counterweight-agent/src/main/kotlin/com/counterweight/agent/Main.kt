package com.counterweight.agent

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Timestamped stdout. The service wrapper redirects it; nothing here rotates a log file. */
fun log(message: String) = println("${LocalDateTime.now().format(CLOCK)}  $message")

/**
 * The agent's whole lifecycle.
 *
 * ```
 *   counterweight-agent [--config <file>] [--test-print]
 * ```
 *
 * `--test-print` is the install-day command: it prints a page from the machine
 * that will actually be printing, through the configured transport, without
 * needing the server, the till or a sale. If that page comes out, everything
 * from here to the paper is right and any later problem is on the network side.
 */
fun main(args: Array<String>) {
    val configPath = args.indexOf("--config").takeIf { it >= 0 }?.let { Paths.get(args[it + 1]) }
        ?: Paths.get(System.getenv("ProgramData") ?: System.getProperty("user.home"), "Counterweight", "agent.properties")

    val config = Config.load(configPath)
    val json = ObjectMapper()
        .registerKotlinModule()
        // A newer server printing to an older agent must fail loudly. A receipt
        // silently missing its total is worse than one that did not print: the
        // second gets fixed, the first gets handed to a customer.
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val transport = transportFor(config)
    log("counterweight-agent - $configPath")
    log("transport: ${transport.describe}")
    log("spool: ${config.spoolDir}")

    if (args.contains("--test-print")) {
        runCatching { transport.send(EscPos.encode(selfTest(config))) }
            .onSuccess { log("test page sent. If nothing came out, the transport is wrong, not the wiring.") }
            .onFailure { log("test print failed: ${it.message}") }
        return
    }

    val spool = Spool(config.spoolDir, json)
    val server = Api(config, spool, transport, json).start()
    log("listening on http://127.0.0.1:${config.port}")
    if (config.allowedOrigins.isEmpty()) {
        log("no allowedOrigins set — only pages served from localhost may print. Add the shop server's origin.")
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(2) })
}

/**
 * The page that proves the chain.
 *
 * Deliberately exercises the parts that go wrong rather than printing "hello":
 * a double-height heading, a dotted column pair, a rule at the configured
 * width, an em dash that CP437 has never heard of, a barcode the shop can try
 * its scanner on, and a cut. If the dash prints as `-` and the cut lands below
 * the last line, the encoder is right about this printer.
 */
private fun selfTest(config: Config) = PrintJob(
    id = "self-test",
    template = "self-test",
    widthChars = 48,
    elements = listOf(
        PrintElement.Line("COUNTERWEIGHT", align = Align.CENTER, bold = true, doubleHeight = true),
        PrintElement.Line("print agent test page", align = Align.CENTER),
        PrintElement.Rule(),
        PrintElement.Columns("Transport", config.transport),
        PrintElement.Columns("Printer", config.printer.ifBlank { "(default)" }),
        PrintElement.Columns("Receipt", "—"),
        PrintElement.Line("Accents and dashes — check these read"),
        PrintElement.Rule(),
        PrintElement.Barcode("6001234500011"),
        PrintElement.Feed(1),
        PrintElement.Line("If the cut is below this line, you are done.", align = Align.CENTER),
        PrintElement.Cut(),
    ),
)
