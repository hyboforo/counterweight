package com.counterweight.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Everything about this one till that the agent needs and cannot discover.
 *
 * A properties file rather than anything cleverer: it is edited once, on
 * install day, by whoever is standing at the counter with the printer in a box,
 * and it has to be readable by that person a year later when the printer is
 * replaced.
 *
 * Note what is *not* here: the paper width. The server sends it with every job
 * (§10), so the receipt is laid out to what the shop configured centrally
 * rather than to a number typed on twelve tills, eleven of which would be
 * corrected the day the twelfth was.
 */
data class Config(
    val port: Int = 9110,
    val transport: String = "spooler",
    /** Windows queue name for the spooler transport. Empty means the default printer. */
    val printer: String = "",
    val host: String = "127.0.0.1",
    val printerPort: Int = 9100,
    /** Device or file for the `file` transport — `\.\COM3`, or a path to watch while developing. */
    val path: String = "",
    val spoolDir: Path = defaultSpool(),
    /**
     * Origins allowed to drive this printer, beyond loopback.
     *
     * The threat is mundane and real: the agent listens on 127.0.0.1, so any
     * web page open in the cashier's browser can post to it. Loopback origins
     * are allowed because that is the developer's till and the agent's own
     * test page; the shop server's origin — `http://192.168.1.10:8080` — has
     * to be named here, and a refusal logs the origin it refused so the fix is
     * one line away.
     */
    val allowedOrigins: List<String> = emptyList(),
    /**
     * Optional shared secret, sent as `X-Counterweight-Agent-Token`.
     *
     * Off by default, and deliberately not required yet: nothing on the till
     * page can obtain a token today, so switching it on without the server
     * handing it to the browser would stop every receipt in the shop. Wired
     * here so that work is a config change and a header, not a release.
     */
    val token: String = "",
) {
    companion object {

        private fun defaultSpool(): Path =
            Paths.get(System.getenv("ProgramData") ?: System.getProperty("user.home"), "Counterweight", "spool")

        /** Reads the file if it is there; every key falls back to a working default. */
        fun load(file: Path?): Config {
            val props = Properties()
            file?.takeIf { Files.isRegularFile(it) }?.let { path ->
                Files.newBufferedReader(path).use(props::load)
            }
            fun str(key: String, fallback: String) = props.getProperty(key)?.trim().orEmpty().ifEmpty { fallback }
            fun int(key: String, fallback: Int) = str(key, "").toIntOrNull() ?: fallback

            return Config(
                port = int("port", 9110),
                transport = str("transport", "spooler").lowercase(),
                printer = str("printer", ""),
                host = str("host", "127.0.0.1"),
                printerPort = int("printerPort", 9100),
                path = str("path", ""),
                spoolDir = str("spoolDir", "").ifEmpty { null }?.let(Paths::get) ?: defaultSpool(),
                allowedOrigins = str("allowedOrigins", "")
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotEmpty),
                token = str("token", ""),
            )
        }
    }
}
