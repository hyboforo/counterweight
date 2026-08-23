import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { PrintJob } from "../api/types";

const AGENT = "http://127.0.0.1:9110";

export type AgentState = "ready" | "missing" | "unknown";

/**
 * Relays print jobs from the server to the agent on this till.
 *
 * The browser is the only thing that can reach both: the server has no route to
 * a service behind a till's own loopback, and the agent has no idea which sales
 * exist. So the till polls for work, hands each job to the agent, and reports
 * back what happened.
 *
 * Chrome and Edge treat `127.0.0.1` as a potentially trustworthy origin, so an
 * HTTPS page may call it without being blocked as mixed content. The module
 * README is right to say this needs verifying on the exact browser build the
 * shop runs — it is the one assumption here that a browser update could take
 * away.
 *
 * **Nothing on the sale path waits on any of this.** §14.1 puts a jammed
 * printer and a missing agent on the degradation ladder: the sale completes,
 * the job spools, and the till offers a reprint. A failure here is reported as
 * a state, never as an interruption.
 */
export function usePrintRelay(tillCode: string | null, enabled: boolean) {
  const [agent, setAgent] = useState<AgentState>("unknown");
  const running = useRef(false);

  useEffect(() => {
    if (!enabled || !tillCode) return;
    let stopped = false;

    async function tick() {
      if (running.current) return;
      running.current = true;
      try {
        const jobs = await api.get<PrintJob[]>(
          `/api/print-jobs/claim?tillCode=${encodeURIComponent(tillCode!)}`,
        );
        if (!jobs.length) return;

        for (const job of jobs) {
          if (stopped) break;
          try {
            const res = await fetch(`${AGENT}/v1/print`, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify(job),
            });
            if (!res.ok) throw new Error(`agent returned ${res.status}`);
            setAgent("ready");
            await api.put(`/api/print-jobs/${job.id}/status`, { status: "DONE" });
          } catch (err) {
            // Handed back so the server can requeue it. The receipt is not
            // lost — it is waiting for whoever fixes the printer.
            setAgent("missing");
            await api
              .put(`/api/print-jobs/${job.id}/status`, {
                status: "FAILED",
                error: err instanceof Error ? err.message.slice(0, 400) : "agent unreachable",
              })
              .catch(() => undefined);
          }
        }
      } catch {
        // The server is unreachable. The connection banner already says so.
      } finally {
        running.current = false;
      }
    }

    // Two seconds is well inside the time it takes a customer to put their
    // money away, and light enough on a wired LAN to be invisible.
    void tick();
    const timer = window.setInterval(tick, 2000);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }, [tillCode, enabled]);

  /** Probes once at startup so the status bar can be honest before anything prints. */
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    fetch(`${AGENT}/v1/status`)
      .then(() => live && setAgent("ready"))
      .catch(() => live && setAgent("missing"));
    return () => {
      live = false;
    };
  }, [enabled]);

  return agent;
}
