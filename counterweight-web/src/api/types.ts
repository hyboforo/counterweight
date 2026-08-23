/**
 * The shapes the server actually returns.
 *
 * Written by hand against the controllers rather than generated. The till uses
 * a narrow slice of the API, and a generated client would pull in every
 * back-office type — including cost and margin fields that must never reach
 * this bundle. `COST_VIEW` is a real permission, and the module README is
 * explicit that cost is not sent and hidden with CSS; it is not sent.
 */

export interface ApiError {
  code: string;
  message: string;
  details?: Record<string, unknown>;
  traceId: string;
  path?: string;
}

/** Exactly what POST /api/auth/login returns — no nested user object. */
export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  mustChangePassword: boolean;
  username: string;
  roles: string[];
  permissions: string[];
}

/**
 * What GET /api/auth/me returns.
 *
 * No full name: the token carries the username, and that is what the audit log
 * keys on. The status bar shows the username rather than fetching a display
 * name the server would have to hit the database for on every load.
 */
export interface CurrentUser {
  id: number;
  username: string;
  branchId: number;
  roles: string[];
  permissions: string[];
  /**
   * Whether the password in use is still the temporary one.
   *
   * It arrives with the login response too, but a reload has only the stored
   * token to go on — so `/me` reports it as well, and this is the copy the app
   * trusts on load.
   */
  mustChangePassword: boolean;
  /**
   * Whether a till override PIN has been set.
   *
   * Read from the row on every `/me` rather than carried in the token, because
   * it changes while somebody is signed in.
   */
  hasOverridePin: boolean;
}

export interface ProductView {
  id: number;
  sku: string;
  name: string;
  localName: string | null;
  /** Only the catalogue screen reads this — it is sent back whole on an edit. */
  description: string | null;
  categoryId: number;
  attributes: Record<string, unknown>;
  isBatchTracked: boolean;
  pickingRule: string;
  isActive: boolean;
}

/**
 * A unit with what it costs, or nothing where no price has been set.
 *
 * Both nullable fields are **absent** rather than null on the wire — the
 * server runs `default-property-inclusion: non_null` — so they are optional
 * here and must be tested with `== null`. A strict `=== null` misses the
 * unpriced case entirely, and an unpriced unit rendered through a formatter
 * reads as 0.00, which is a price rather than the absence of one.
 */
export interface UnitPriceView {
  productUomId: number;
  uomCode: string;
  factor: string;
  unitPrice?: string | null;
  priceListId?: number | null;
}

export interface LotOnHandView {
  lotId: number;
  lotCode: string;
  expiresOn: string | null;
  qtyBase: string;
  status: string;
}

export interface SaleLineView {
  id: number;
  lineNo: number;
  productId: number;
  productUomId: number;
  qty: string;
  qtyBase: string;
  unitPrice: string;
  discountAmount: string;
  taxAmount: string;
  lineTotal: string;
  priceOverridden: boolean;
  /** §6.3: the buyer has to be written down before this line can be sold. */
  requiresBuyerRecord: boolean;
}

export interface SaleView {
  id: number;
  number: string | null;
  status: string;
  customerId: number | null;
  cashierId: number;
  tillCode: string | null;
  heldLabel: string | null;
  subtotal: string;
  discountTotal: string;
  taxTotal: string;
  roundingAdjustment: string;
  grandTotal: string;
  completedAt: string | null;
  lines: SaleLineView[];
}

export interface PaymentView {
  id: number;
  method: string;
  amount: string;
  tendered: string | null;
  changeGiven: string | null;
  momoNetwork: string | null;
  reference: string | null;
}

export interface CompletedSaleView {
  sale: SaleView;
  payments: PaymentView[];
  /** True when the request had already been honoured and nothing was re-posted. */
  replayed: boolean;
}

export interface CustomerView {
  id: number;
  code: string;
  name: string;
  phone: string | null;
  altPhone: string | null;
  address: string | null;
  creditLimit: string | null;
  paymentTermsDays: number;
  priceListId: number | null;
  isActive: boolean;
  notes: string | null;
}

export interface CreditStanding {
  customerId: number;
  creditLimit: string | null;
  balance: string;
  availableCredit: string | null;
  overdueAmount: string;
  oldestOverdueDays: number | null;
  paymentTermsDays: number;
  cashOnly: boolean;
  hasOverdue: boolean;
}

export type CreditOutcome = "ALLOWED" | "REQUIRES_APPROVAL" | "REFUSED";

export interface CreditDecision {
  outcome: CreditOutcome;
  requestedAmount: string;
  standing: CreditStanding;
  reason: string | null;
}

export interface DiscountAllowance {
  maxPercent: string;
  minMarginPercent: string;
  requiresApprovalAbove: string | null;
}

/* ── Printing ───────────────────────────────────────────────────────────── */

export type Align = "LEFT" | "CENTER" | "RIGHT";

export type PrintElement =
  | { type: "line"; text: string; align?: Align; bold?: boolean; doubleHeight?: boolean }
  | { type: "columns"; left: string; right: string }
  | { type: "barcode"; value: string; symbology?: string }
  | { type: "qr"; value: string }
  | { type: "feed"; lines?: number }
  | { type: "rule" }
  | { type: "cut" };

export interface PrintJob {
  id: string;
  template: string;
  widthChars: number;
  elements: PrintElement[];
  copies: number;
}

/* ── Till-local ─────────────────────────────────────────────────────────── */

export interface TenderDraft {
  method: string;
  amount: number;
  tendered?: number;
  momoNetwork?: string;
  reference?: string;
  bankName?: string;
  chequeNumber?: string;
}

/**
 * A buyer written into the §6.3 register, for one restricted line.
 *
 * Only the name is obliged. The rest is what a licence inspector expects to
 * find and what a recall needs to trace, but a counter that refuses the sale
 * over a missing ID number teaches the cashier to type anything into the box.
 */
export interface BuyerRecordDraft {
  saleLineId: number;
  buyerName: string;
  buyerPhone: string;
  buyerIdType: string;
  buyerIdNumber: string;
  intendedUse: string;
}

/* ── Stock takes ────────────────────────────────────────────────────────── */

export interface CategoryView {
  id: number;
  code: string;
  name: string;
  parentId: number | null;
  /**
   * The ltree path — `agro.herbicide`. The catalogue rail nests by counting
   * its dots rather than walking parents, which is also the order the server
   * returns categories in.
   */
  path: string | null;
  /** AGROCHEMICAL forces batch tracking on anything filed under it. */
  kind: string;
  isActive: boolean;
}

/**
 * One field a category declares its products carry, inherited included.
 *
 * `declaredOnCategoryId` says which ancestor declared it — the same list a
 * product form is built from, so a field inherited from `agro` looks no
 * different from one declared on the leaf.
 */
export interface AttributeView {
  key: string;
  label: string;
  dataType: "TEXT" | "NUMBER" | "BOOL" | "DATE" | "ENUM";
  enumValues: string[] | null;
  unit: string | null;
  required: boolean;
  declaredOnCategoryId: number;
}

/** A unit of measure, as offered when a product is built or a unit is added. */
export interface UomView {
  id: number;
  code: string;
  name: string;
  decimals: number;
}

export interface PriceListView {
  id: number;
  code: string;
  name: string;
  isDefault: boolean;
}

/**
 * One price, with the window it applies to.
 *
 * `effectiveTo` is null for the price in force. A row dated ahead of today is
 * scheduled and can still be cancelled; a past one is history and cannot.
 */
export interface PriceView {
  id: number;
  priceListId: number;
  productId: number;
  productUomId: number;
  unitPrice: string;
  effectiveFrom: string;
  effectiveTo: string | null;
}

export interface StockTakeView {
  id: number;
  reference: string;
  scopePath?: string | null;
  scopeName: string;
  status: "OPEN" | "COUNTED" | "POSTED" | "CANCELLED";
  openedAt: string;
  postedAt?: string | null;
  lineCount: number;
  countedCount: number;
  uncountedCount: number;
}

/**
 * A row of the count sheet.
 *
 * Note what is absent: there is no expected quantity, and there must never be
 * one. The server does not send it — see the module README — and a counter who
 * can see what the system expects will find what the system expects. Adding a
 * field here to be "helpful" would quietly destroy the control.
 *
 * `countedQty` and `note` are omitted by the server while null, so both are
 * optional rather than nullable: absent means nobody has looked yet, which is a
 * different thing from a counted zero.
 */
export interface CountSheetRow {
  lineId: number;
  lotId: number;
  productId: number;
  sku: string;
  name: string;
  localName?: string | null;
  lotCode: string;
  expiresOn?: string | null;
  uomCode: string;
  decimals: number;
  countedQty?: number;
  note?: string | null;
  /** Movements against this lot since the snapshot — the figure is approximate. */
  movedSince: number;
}

/** The supervisor's view. Carries the expected figures and cost. */
export interface VarianceRow {
  lineId: number;
  lotId: number;
  sku: string;
  name: string;
  lotCode: string;
  uomCode: string;
  expectedQty: number;
  countedQty?: number;
  variance?: number;
  unitCost: number;
  note?: string | null;
  movedSince: number;
  onHandNow: number;
}

export interface StockTakePosting {
  reference: string;
  linesPosted: number;
  linesUnchanged: number;
  unitsWrittenOff: number;
  unitsFound: number;
  shrinkageValue: number;
  foundValue: number;
  netValue: number;
}

/* ── Goods receipt ──────────────────────────────────────────────────────── */

/**
 * A product's unit, with the unit of measure resolved.
 *
 * `factor` is how many base units one of these is: a carton of 24 has factor
 * 24. Goods receipt lives or dies on it — cost is quoted per *this* unit and
 * stored per base unit, so receiving a carton at the price of a piece is an
 * error of a factor of twenty-four in the lot cost, and every margin computed
 * from it afterwards.
 */
export interface ProductUomView {
  id: number;
  uomId: number;
  uomCode: string;
  uomName: string;
  decimals: number;
  factor: number;
  isBase: boolean;
  sellable: boolean;
  purchasable: boolean;
  barcode: string | null;
}

/**
 * A booked delivery, as a numbered document.
 *
 * `number` is ours (GRN-000001); `supplierReference` is whatever the delivery
 * note said. The id is what prints the slip — the server renders it from
 * `goods_receipt_line`, so the paper says what was recorded rather than what
 * this client remembers sending.
 */
export interface GoodsReceiptView {
  id: number;
  number: string;
  supplierReference: string | null;
  receivedOn: string;
  receivedBy: number;
  lineCount: number | null;
}

/* ── Receivables ────────────────────────────────────────────────────────── */

/**
 * A customer carrying a balance.
 *
 * `balance` is the account net of everything, money paid in and not yet matched
 * to an invoice included. `overdueAmount` counts only invoices past their due
 * date — the figure a collections call actually opens with.
 */
export interface DebtorView {
  customerId: number;
  code: string;
  name: string;
  phone: string | null;
  creditLimit: string | null;
  balance: string;
  overdueAmount: string;
  oldestOverdueDays: number;
}

export interface LedgerEntryView {
  id: number;
  entryType: "INVOICE" | "PAYMENT" | "CREDIT_NOTE" | "OPENING_BALANCE" | "WRITE_OFF" | "ADJUSTMENT";
  /** Signed: positive increases what the customer owes. */
  amount: string;
  dueOn: string | null;
  reference: string | null;
  occurredAt: string;
  salesDocumentId: number | null;
}

export interface OpenInvoiceView {
  entryId: number;
  amount: string;
  allocated: string;
  outstanding: string;
  dueOn: string | null;
  daysOverdue: number;
  reference: string | null;
}

export interface AgeingBuckets {
  current: string;
  days1To30: string;
  days31To60: string;
  days61To90: string;
  days90Plus: string;
  total: string;
}

export interface StatementLine {
  entryId: number;
  occurredAt: string;
  entryType: string;
  reference: string | null;
  dueOn: string | null;
  /** Signed as stored: positive increases the debt. */
  amount: string;
  runningBalance: string;
}

/** A period of account activity, with the balance carried through it. */
export interface Statement {
  customerId: number;
  customerCode: string;
  customerName: string;
  from: string;
  to: string;
  openingBalance: string;
  lines: StatementLine[];
  closingBalance: string;
  creditLimit: string | null;
  /** As of today, not as of `to` — the server is explicit about that. */
  ageing: AgeingBuckets;
}

/* ── Reports ────────────────────────────────────────────────────────────── */

/**
 * A report as the server reduces it: headers, then rows of values.
 *
 * Every report has this shape, which is why the screen has one table renderer
 * rather than seventeen. The server builds it once and serves both the screen
 * and the export from that same definition — the failure it exists to prevent
 * is an owner emailing an accountant figures that do not match the till.
 *
 * Unlike the rest of the API, money here arrives as a JSON **number** rather
 * than a string: these are `BigDecimal` projections, not the till's money DTOs.
 * Safe to read as a double for display — a NUMERIC(14,2) is far inside what a
 * double represents exactly — and the exports never round-trip through this
 * type anyway, because the server re-runs the report to build the file.
 */
export type ReportCell = string | number | boolean | null;

/**
 * How a column reads, as the server declares it.
 *
 * Without this the screen would have to guess from the values, and it would
 * guess wrong in the one case that matters: a money column whose figures all
 * happen to be whole is indistinguishable from a column of counts once JSON has
 * turned both into bare numbers. The export writes money with two decimals, so
 * a guessing screen prints `261` beside a file that says `261.00`.
 */
export type ColumnType = "TEXT" | "INTEGER" | "MONEY" | "DATE" | "TIMESTAMP" | "BOOLEAN";

export interface ReportTable {
  title: string;
  headers: string[];
  rows: ReportCell[][];
  /** Empty for a table the server built by hand; the screen falls back to inspecting values. */
  types: ColumnType[];
}

/** When the two inventory matviews were last rebuilt. Null if never. */
export interface RefreshState {
  refreshedAt: string | null;
}

/* ── Staffing ───────────────────────────────────────────────────────────── */

/**
 * A staff account as the admin screen sees it.
 *
 * Note what the server does not send: no password hash, no override PIN hash,
 * no failed-login count. The response DTO is hand-written for exactly that
 * reason, and this type is its mirror — if a credential ever appears here,
 * something upstream started serialising the entity.
 */
export interface UserSummary {
  id: number;
  username: string;
  fullName: string;
  phone: string | null;
  email: string | null;
  branchId: number;
  roles: string[];
  isActive: boolean;
  isLocked: boolean;
  mustChangePassword: boolean;
  /** A PIN exists. Never the PIN — the server does not send it or its hash. */
  hasOverridePin: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

/**
 * A role, and whether the person looking may hand it out.
 *
 * `grantable` is the server's own answer, not the screen's guess. You cannot
 * grant access you do not hold yourself, and `withheld` names the permissions
 * that fail that test — which is the difference between a greyed-out role and
 * one that explains itself.
 */
export interface RoleView {
  code: string;
  name: string;
  isSystem: boolean;
  permissions: string[];
  grantable: boolean;
  withheld: string[];
}

/** The temporary password is in the response and nowhere else, ever again. */
export interface CreatedUser {
  user: UserSummary;
  temporaryPassword: string;
}

/* ── The shop's settings ────────────────────────────────────────────────── */

/**
 * One setting.
 *
 * `valueType` is what the editor renders — a BOOL is a checkbox, a NUMBER is a
 * numeric field — and the server validates against it too. Keys cannot be
 * created from a screen: every one of these corresponds to code that reads it.
 */
export interface ConfigView {
  key: string;
  value: string;
  valueType: "STRING" | "NUMBER" | "BOOL" | "JSON";
  description: string | null;
  updatedAt: string;
}

export interface BranchView {
  id: number;
  code: string;
  name: string;
  address: string | null;
  phone: string | null;
  isActive: boolean;
}

/**
 * Whether the shop's records are actually protected.
 *
 * Staleness is measured from the last **verified** run rather than the last
 * successful one: a dump nobody has ever restored proves the job runs, not that
 * the file can be read back.
 */
export interface BackupStatus {
  lastDumpAt: string | null;
  lastVerifiedAt: string | null;
  hoursSinceVerified: number | null;
  stale: boolean;
  staleAfterHours: number;
  lastError: string | null;
}

export interface BackupRunView {
  id: number;
  kind: "DUMP" | "WAL_ARCHIVE" | "OFFSITE";
  destination: string;
  status: string;
  startedAt: string;
  completedAt: string | null;
  sizeBytes: number | null;
  verifiedAt: string | null;
  error: string | null;
  verifyError: string | null;
}
