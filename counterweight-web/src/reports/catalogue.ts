/**
 * What reports exist, what each one needs, and who may see it.
 *
 * The permissions here mirror `ReportService`'s `@PreAuthorize` annotations
 * exactly, and they are not decoration in either place. Sales staff are given
 * neither `COST_VIEW` nor `AUDIT_VIEW` deliberately, so a report list that
 * offered everything to everybody would put a row in front of a cashier that
 * answers 403 when clicked — teaching the floor that the back office is broken
 * rather than that they are not allowed.
 *
 * The server remains the authority. This list decides what to *offer*; it
 * cannot grant anything, and a mismatch shows up as a refusal rather than as
 * leaked figures.
 *
 * `id` is both the path under `/api/reports/` and the `report` parameter the
 * export endpoint takes. Keeping them the same string is what lets one screen
 * run and export seventeen reports without a switch statement per report.
 */

export type ReportFamily = "Sales" | "Inventory" | "Money" | "Control" | "Compliance";

/** Extra inputs a report needs beyond a date range. */
export type ReportParam = "dates" | "limit" | "batch" | "onlyInStock";

export interface ReportDef {
  id: string;
  name: string;
  family: ReportFamily;
  /** Every permission the server demands. All of them, not any of them. */
  needs: string[];
  params: ReportParam[];
  /** One line on what the report is for, or what it deliberately does not say. */
  note?: string;
  /**
   * Reads a materialised view rebuilt at 02:00 rather than live tables, so the
   * screen has to say how stale the answer is instead of implying it is now.
   */
  stale?: boolean;
}

export const REPORTS: ReportDef[] = [
  /* ── Sales ────────────────────────────────────────────────────────────── */
  {
    id: "sales/daily",
    name: "Daily takings",
    family: "Sales",
    needs: ["REPORT_VIEW", "COST_VIEW"],
    params: ["dates"],
    note: "A day per row, with what it cost to earn it.",
  },
  {
    id: "sales/by-cashier",
    name: "Takings by cashier",
    family: "Sales",
    needs: ["REPORT_VIEW", "COST_VIEW"],
    params: ["dates"],
  },
  {
    id: "sales/hourly",
    name: "Hourly trade",
    family: "Sales",
    needs: ["REPORT_VIEW"],
    params: ["dates"],
    note: "Where the day's trade actually falls, for staffing it. Carries no cost.",
  },
  {
    id: "sales/by-product",
    name: "Sales by product",
    family: "Sales",
    needs: ["REPORT_VIEW", "COST_VIEW"],
    params: ["dates", "limit"],
  },
  {
    id: "sales/by-category",
    name: "Sales by category",
    family: "Sales",
    needs: ["REPORT_VIEW", "COST_VIEW"],
    params: ["dates"],
  },
  {
    id: "sales/by-customer",
    name: "Sales by customer",
    family: "Sales",
    needs: ["REPORT_VIEW"],
    params: ["dates", "limit"],
  },

  /* ── Inventory ────────────────────────────────────────────────────────── */
  {
    id: "inventory/valuation",
    name: "Stock valuation",
    family: "Inventory",
    needs: ["REPORT_VIEW", "COST_VIEW"],
    params: ["onlyInStock"],
    stale: true,
    note: "What is on the shelves, at what it cost to put there.",
  },
  {
    id: "inventory/abc",
    name: "ABC analysis",
    family: "Inventory",
    needs: ["REPORT_VIEW", "COST_VIEW"],
    params: ["dates"],
    note: "Which lines earn the turnover. A-class is the top 80%.",
  },
  {
    id: "inventory/expiry-ageing",
    name: "Expiry ageing",
    family: "Inventory",
    needs: ["REPORT_VIEW"],
    params: [],
    stale: true,
    note: "Agro-chemicals dated out of usefulness, bucketed by how soon.",
  },

  /* ── Money ────────────────────────────────────────────────────────────── */
  {
    id: "money/takings",
    name: "Takings by tender",
    family: "Money",
    needs: ["REPORT_VIEW"],
    params: ["dates"],
    note: "What was taken each day, split by till and how it was paid.",
  },
  {
    id: "money/discounts",
    name: "Discount register",
    family: "Money",
    needs: ["REPORT_VIEW"],
    params: ["dates"],
    note: "Every discount given, and who approved the ones needing approval.",
  },

  /* ── Control ──────────────────────────────────────────────────────────── */
  {
    id: "control/voids",
    name: "Voids and returns",
    family: "Control",
    needs: ["AUDIT_VIEW"],
    params: ["dates"],
  },
  {
    id: "control/price-overrides",
    name: "Price overrides",
    family: "Control",
    needs: ["AUDIT_VIEW"],
    params: ["dates"],
  },
  {
    id: "control/adjustments",
    name: "Adjustments and write-offs",
    family: "Control",
    needs: ["AUDIT_VIEW"],
    params: ["dates"],
    note: "Includes posted stock takes — posting a count writes stock off.",
  },
  {
    id: "control/overrides",
    name: "Supervisor overrides",
    family: "Control",
    needs: ["AUDIT_VIEW"],
    params: ["dates"],
    note: "Every time a PIN was used, accepted or not.",
  },

  /* ── Compliance ───────────────────────────────────────────────────────── */
  {
    id: "compliance/restricted-sales",
    name: "Restricted sales",
    family: "Compliance",
    needs: ["REPORT_VIEW"],
    params: ["dates"],
    note: "The buyer register for restricted agro-chemicals.",
  },
  {
    id: "compliance/trace",
    name: "Batch traceability",
    family: "Compliance",
    needs: ["REPORT_VIEW"],
    params: ["batch"],
    note:
      "Who received a batch, by the code printed on the drum. A walk-in shows " +
      "with a blank customer — the honest answer, and a recall has to be told it.",
  },
];

export const FAMILIES: ReportFamily[] = ["Sales", "Inventory", "Money", "Control", "Compliance"];

/** Only the reports this account can actually run. */
export const visibleTo = (can: (permission: string) => boolean): ReportDef[] =>
  REPORTS.filter((r) => r.needs.every(can));

export const needsDates = (r: ReportDef): boolean => r.params.includes("dates");
