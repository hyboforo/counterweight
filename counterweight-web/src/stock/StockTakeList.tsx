import { useCallback, useEffect, useState } from "react";
import { api } from "../api/client";
import type { CategoryView, StockTakeView } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { describe } from "../till/useSale";
import { Button, Callout, Field, Panel, inputClass, useToast } from "../ui/components";

/**
 * Every count the shop has run, newest first.
 *
 * The open one is the subject — there is at most one covering any given stock,
 * because two would each snapshot the same lots and each post their variance.
 * The rest is history, and history is the point: a shop that cannot see last
 * month's shrinkage beside this month's has a number, not a trend.
 */
export function StockTakeList({
  onOpenSheet,
  onReview,
}: {
  onOpenSheet: (take: StockTakeView) => void;
  onReview: (take: StockTakeView) => void;
}) {
  const { can } = useAuth();
  const toast = useToast();
  const [takes, setTakes] = useState<StockTakeView[] | null>(null);
  const [starting, setStarting] = useState(false);

  const load = useCallback(async () => {
    setTakes(await api.get<StockTakeView[]>("/api/stock-takes"));
  }, []);

  useEffect(() => {
    void load().catch((err) => toast(describe(err)));
  }, [load, toast]);

  const mayPost = can("STOCK_ADJUST");

  if (!takes) return <div className="p-6 text-inksoft">Loading…</div>;

  const live = takes.filter((t) => t.status === "OPEN" || t.status === "COUNTED");
  const past = takes.filter((t) => t.status === "POSTED" || t.status === "CANCELLED");

  return (
    <div className="overflow-y-auto h-full min-h-0">
      <div className="max-w-4xl mx-auto p-6 grid gap-6">
        <div className="flex items-baseline gap-3 flex-wrap">
          <h1 className="m-0 text-2xl font-semibold">Stock takes</h1>
          <span className="ml-auto">
            <Button variant="primary" onClick={() => setStarting(true)} disabled={live.length > 0}>
              Start a count
            </Button>
          </span>
        </div>

        {live.length > 0 ? (
          <section className="grid gap-2">
            <h2 className="m-0 text-xs uppercase tracking-[.06em] text-inkfaint">Under way</h2>
            {live.map((take) => (
              <Card
                key={take.id}
                take={take}
                mayPost={mayPost}
                onOpenSheet={() => onOpenSheet(take)}
                onReview={() => onReview(take)}
                onCancelled={() => void load()}
              />
            ))}
          </section>
        ) : (
          <Callout tone="info">
            Nothing is being counted. A count freezes what the books say the moment it opens, so the
            shop can keep trading while somebody walks the shelf.
          </Callout>
        )}

        {past.length > 0 && (
          <section className="grid gap-2">
            <h2 className="m-0 text-xs uppercase tracking-[.06em] text-inkfaint">Finished</h2>
            {past.map((take) => (
              <Card
                key={take.id}
                take={take}
                mayPost={mayPost}
                onOpenSheet={() => onOpenSheet(take)}
                onReview={() => onReview(take)}
                onCancelled={() => void load()}
              />
            ))}
          </section>
        )}
      </div>

      {starting && (
        <StartPanel
          onClose={() => setStarting(false)}
          onStarted={(take) => {
            setStarting(false);
            void load();
            onOpenSheet(take);
          }}
        />
      )}
    </div>
  );
}

/* ── Starting one ───────────────────────────────────────────────────────── */

function StartPanel({
  onClose,
  onStarted,
}: {
  onClose: () => void;
  onStarted: (take: StockTakeView) => void;
}) {
  const toast = useToast();
  const [categories, setCategories] = useState<CategoryView[]>([]);
  const [categoryId, setCategoryId] = useState<string>("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .get<CategoryView[]>("/api/categories")
      .then(setCategories)
      .catch(() => undefined);
  }, []);

  async function start() {
    setBusy(true);
    try {
      onStarted(
        await api.post<StockTakeView>("/api/stock-takes", {
          categoryId: categoryId === "" ? null : Number(categoryId),
        }),
      );
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Start a count" hint="Esc to cancel" onClose={onClose}>
      <Callout tone="info">
        Counting a section rather than the whole shop is the point of this. The agro-chemical shelf
        can be counted on a Tuesday morning while the hardware side keeps selling.
      </Callout>

      <Field label="What is being counted">
        <select
          className={inputClass}
          value={categoryId}
          autoFocus
          onChange={(e) => setCategoryId(e.target.value)}
        >
          <option value="">The whole shop</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </Field>

      <p className="m-0 text-[13px] text-inksoft">
        What the system expects is frozen now, and never shown to whoever counts. That is the whole
        control — a count made against a figure you have already been given is not a count.
      </p>

      <Button variant="primary" disabled={busy} onClick={() => void start()}>
        {busy ? "Opening…" : "Open the count"}
      </Button>
    </Panel>
  );
}

/* ── One count ──────────────────────────────────────────────────────────── */

function Card({
  take,
  mayPost,
  onOpenSheet,
  onReview,
  onCancelled,
}: {
  take: StockTakeView;
  mayPost: boolean;
  onOpenSheet: () => void;
  onReview: () => void;
  onCancelled: () => void;
}) {
  const toast = useToast();
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState("");

  async function cancel() {
    try {
      await api.post(`/api/stock-takes/${take.id}/cancel`, { reason });
      setCancelling(false);
      onCancelled();
    } catch (err) {
      toast(describe(err));
    }
  }

  const opened = new Date(take.openedAt);

  return (
    <div className="bg-surface border border-line rounded px-4 py-3.5 grid grid-cols-[1fr_auto] gap-x-4 gap-y-2 items-center">
      <div className="min-w-0">
        <div className="flex items-baseline gap-2.5 flex-wrap">
          <span className="font-mono font-semibold">{take.reference}</span>
          <StatusChip status={take.status} />
          <span className="text-inksoft text-[13.5px]">{take.scopeName}</span>
        </div>
        <div className="text-[12.5px] text-inkfaint mt-0.5">
          opened {opened.toLocaleDateString()} {opened.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
          {" · "}
          <span className="tnum">
            {take.countedCount} of {take.lineCount} counted
          </span>
        </div>
      </div>

      <div className="flex gap-2 flex-wrap justify-end">
        {(take.status === "OPEN" || take.status === "COUNTED") && (
          <>
            <Button variant="quiet" onClick={onOpenSheet}>
              {take.status === "OPEN" ? "Count" : "View the sheet"}
            </Button>
            <Button variant="quiet" onClick={() => setCancelling(true)}>Abandon</Button>
          </>
        )}
        {take.status === "COUNTED" && mayPost && (
          <Button variant="default" className="py-1.5 px-3 text-[13px]" onClick={onReview}>
            Review and post
          </Button>
        )}
        {take.status === "POSTED" && mayPost && (
          <Button variant="quiet" onClick={onReview}>Variances</Button>
        )}
      </div>

      {cancelling && (
        <Panel title={`Abandon ${take.reference}?`} hint="Esc to cancel" onClose={() => setCancelling(false)}>
          <Callout tone="warn">
            Nothing is posted and no stock moves. The sheet stays on the record as evidence the count
            happened, which is why a reason is required.
          </Callout>
          <Field label="Why is it being abandoned?">
            <input
              className={inputClass}
              value={reason}
              autoFocus
              onChange={(e) => setReason(e.target.value)}
              placeholder="Counted the wrong shelf"
            />
          </Field>
          <Button variant="danger" disabled={!reason.trim()} onClick={() => void cancel()}>
            Abandon the count
          </Button>
        </Panel>
      )}
    </div>
  );
}

function StatusChip({ status }: { status: StockTakeView["status"] }) {
  const styles: Record<string, string> = {
    OPEN: "bg-accentwash text-accent",
    COUNTED: "bg-warnwash text-warn",
    POSTED: "bg-goodwash text-good",
    CANCELLED: "bg-surface2 text-inkfaint",
  };
  const words: Record<string, string> = {
    OPEN: "counting",
    COUNTED: "awaiting sign-off",
    POSTED: "posted",
    CANCELLED: "abandoned",
  };
  return (
    <span className={`text-[11.5px] font-semibold uppercase tracking-[.05em] px-2 py-0.5 rounded ${styles[status]}`}>
      {words[status]}
    </span>
  );
}
