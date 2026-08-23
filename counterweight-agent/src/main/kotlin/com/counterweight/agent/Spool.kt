package com.counterweight.agent

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Every job that arrived, on disk, before anything is sent to the printer.
 *
 * The requirement is one sentence long and the reason is the whole feature: a
 * jam or an empty roll must not lose a receipt somebody asked for. Because the
 * job survives, "print it again" is a lookup rather than a feature the server
 * has to support, and a shop that reboots a till mid-jam still has the paper it
 * owes a customer.
 *
 * Written before printing and never deleted by the agent. A spool directory is
 * cheap — a busy day is a few hundred kilobytes — and pruning it is the sort of
 * housekeeping that silently deletes the one receipt that mattered.
 */
class Spool(private val dir: Path, private val json: ObjectMapper) {

    private val statuses = ConcurrentHashMap<String, JobStatus>()

    init {
        Files.createDirectories(dir)
        reload()
    }

    /** Statuses survive a restart, so a failed job is still there to retry after a reboot. */
    private fun reload() {
        Files.list(dir).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".status") }.forEach { file ->
                runCatching { json.readValue(Files.readString(file), JobStatus::class.java) }
                    .onSuccess { statuses[it.jobId] = it }
            }
        }
    }

    fun accept(job: PrintJob): JobStatus {
        Files.writeString(dir.resolve("${job.id}.json"), json.writeValueAsString(job))
        val status = JobStatus(
            jobId = job.id,
            state = JobState.QUEUED,
            template = job.template,
            receivedAt = Instant.now().toString(),
            attempts = 0,
        )
        return record(status)
    }

    fun record(status: JobStatus): JobStatus {
        statuses[status.jobId] = status
        /*
         * Written through a temporary file and moved into place. A till loses
         * power exactly as often as the shop does, and a half-written status
         * file is one the agent cannot read on the way back up — which would
         * lose the record of the job it is meant to protect.
         */
        val target = dir.resolve("${status.jobId}.status")
        val tmp = dir.resolve("${status.jobId}.status.tmp")
        Files.writeString(tmp, json.writeValueAsString(status))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        return status
    }

    fun status(jobId: String): JobStatus? = statuses[jobId]

    fun job(jobId: String): PrintJob? {
        val file = dir.resolve("$jobId.json")
        if (!Files.isRegularFile(file)) return null
        return json.readValue(Files.readString(file), PrintJob::class.java)
    }

    fun depth(): Int = statuses.values.count { it.state == JobState.QUEUED || it.state == JobState.PRINTING }
}
