package com.counterweight.agent

import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.print.DocFlavor
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc
import javax.print.attribute.HashPrintRequestAttributeSet

/**
 * How the bytes reach the printer.
 *
 * Three of them, because a shop's second printer is rarely the same shape as
 * its first: the one on the counter is USB, the one in the store room is on the
 * network, and the one on a developer's desk does not exist at all.
 */
interface Transport {
    val describe: String
    fun send(bytes: ByteArray)
}

/**
 * USB, through the Windows spooler, in raw mode.
 *
 * The Syncotek ships a Windows driver and appears as an ordinary print queue,
 * so this hands the queue ESC/POS bytes with `AUTOSENSE` and the spooler passes
 * them through untouched. Going at the USB endpoint directly would mean a
 * driver of our own and a device that Windows has already claimed.
 *
 * The failure worth knowing about is not in this code: a queue installed for
 * one Windows user is invisible to a service running as SYSTEM, so an agent
 * that prints when launched by hand and never as a service is almost always a
 * per-user printer. Install the queue for all users, or run the agent as the
 * till's own account.
 */
class SpoolerTransport(private val queue: String) : Transport {

    override val describe = if (queue.isBlank()) "windows spooler (default printer)" else "windows spooler '$queue'"

    override fun send(bytes: ByteArray) {
        val service = resolve() ?: error(
            if (queue.isBlank()) "No default printer is set on this machine."
            else "No printer queue called '$queue'. /v1/printers lists what this machine has."
        )
        val job = service.createPrintJob()
        job.print(SimpleDoc(bytes, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), HashPrintRequestAttributeSet())
    }

    private fun resolve() =
        if (queue.isBlank()) PrintServiceLookup.lookupDefaultPrintService()
        else PrintServiceLookup.lookupPrintServices(null, null).firstOrNull { it.name.equals(queue, true) }

    companion object {
        fun available(): List<String> =
            PrintServiceLookup.lookupPrintServices(null, null).map { it.name }
    }
}

/** A network printer listening on raw TCP 9100. */
class TcpTransport(private val host: String, private val port: Int) : Transport {

    override val describe = "network $host:$port"

    override fun send(bytes: ByteArray) {
        Socket().use { socket ->
            // Bounded, because the alternative is a till whose print relay
            // stops for as long as the OS is willing to wait for a printer
            // somebody unplugged.
            socket.connect(InetSocketAddress(host, port), 4000)
            socket.soTimeout = 4000
            socket.getOutputStream().apply {
                write(bytes)
                flush()
            }
        }
    }
}

/**
 * Straight to a device or a file — `\.\COM3` for serial and Bluetooth SPP,
 * or an ordinary path while developing, where the bytes can be read back and
 * the encoder checked without owning a printer.
 */
class FileTransport(private val path: String) : Transport {

    override val describe = "file $path"

    override fun send(bytes: ByteArray) {
        require(path.isNotBlank()) { "The file transport needs a `path`." }
        FileOutputStream(path, true).use { out ->
            out.write(bytes)
            out.flush()
        }
    }
}

fun transportFor(config: Config): Transport = when (config.transport) {
    "spooler" -> SpoolerTransport(config.printer)
    "tcp" -> TcpTransport(config.host, config.printerPort)
    "file" -> FileTransport(config.path)
    else -> error("Unknown transport '${config.transport}'. Use spooler, tcp or file.")
}
