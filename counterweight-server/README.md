# counterweight-server

Spring Boot 3.3 on JVM 21. Owns the domain, the database, and serves the built
web client as static content — the till loads the UI from this server over the
shop LAN.

Kotlin, per [ADR-001](../docs/adr/001-implementation-language.md), which is
settled.

## What is here now

**Every module in scope exists**, each carrying the whole
`domain / repo / service / web` stack: `platform`, `identity`, `catalog`,
`inventory`, `pricing`, `parties`, `sales`, `billing`, `printing`, `alerting`,
`reporting`.

**Catalogue maintenance** is what a shop does to a product for years after
creating it: `PUT /api/products/{id}` corrects the names, `POST` and `PUT` on
`/api/products/{id}/units` add the carton that turned up later and attach the
barcode that was not on the pack at first. Three things are deliberately not
editable, and each refusal says why — the SKU (shelf labels and order books
point at it), the base unit (every row in `stock_movement` is counted in it),
and a unit's factor (re-sizing a carton from 12 to 24 halves the per-piece
price of every price row quoted against it, with no figure on screen changing).
A pack that changes size is a new unit.

A category's `kind` is inherited rather than chosen: a GENERAL category under
the agro root is refused, because its products would inherit the EPA and hazard
fields and still be sellable with no expiry to enforce.

**Stock takes** are built: open a count over the whole shop or one category
subtree, count it blind, sign it off, post the variances into the ledger. Two
things about them are controls rather than features and should survive any
refactor.

The count sheet **never carries the expected quantity** — not on screen and not
on the printed sheet. A counter shown the system's figure will find the system's
figure, and the count stops being independent evidence.

Posting is gated on `STOCK_ADJUST`, not `STOCK_COUNT`. `STOREKEEPER` holds the
latter and not the former, so the person who counts a shelf short is not the
person who signs the shortage away. V1's description of `STOCK_COUNT` said
otherwise and V11 corrects it.

**Goods receipt is a numbered document again.** V1 had a `goods_receipt`
header; V7 dropped it with purchasing, because it hung off `supplier` and
`purchase_order` — but goods still arrive, and only the ordering that preceded
them was out of scope. V12 restores the header without that coupling, and with
it two things that were quietly broken: RECEIPT movements now set `source_id`
(they carried `source_type = 'GRN'` and a null id, so the ledger claimed a
receipt justified the stock without being able to say which), and the printed
slip is rendered from `goods_receipt_line` rather than from whatever the client
sent. A slip assembled from a request body is not evidence of anything, and
this one is the paper behind every lot cost the ledger later depends on.

A receipt line stores both the figures as keyed and the same figures converted
to base units, for the reason `sale_line` gives: a later change to a product's
conversion factor must not rewrite what the document said.

Branch transfers remain unbuilt, deliberately: §5.1 has them dormant until a
second branch exists.

**`purchasing` is out of scope** — the shop does not raise purchase orders from
this system. Goods still arrive and are received through `inventory.receive`;
what is not modelled is the ordering that happened beforehand, which stays on
paper and on the phone.

```
src/main/kotlin/com/counterweight/
  common/      ApiException, GlobalExceptionHandler, validation annotations
  platform/    branch, configuration, document numbering, backup orchestration
  identity/    JWT, roles, permissions, audit, supervisor override PINs
  catalog/     categories, ltree attribute inheritance, products, units
  inventory/   lots, movements, balances, receiving, adjustments, expiry sweep
  pricing/     price lists, effective-dated prices, discount policy, tax engine
  parties/     customers, credit limits, account ledger, allocation, ageing
  sales/       cart, picking, completion, payments, returns, voids
  billing/     quotations, documents, tax snapshots, statements
  printing/    print document model, templates, job queue, agent contract
  alerting/    rules, evaluators, dedupe, reorder points, morning briefing
  reporting/   sales rollups, materialised views, registers, traceability, exports

src/main/resources/db/migration/
  V1__counterweight_baseline.sql       51 tables, 102 FKs, 45 indexes, 3 triggers
  V2__identity_roles_and_auth.sql      refresh tokens, login attempts, role grants
  V3__category_tree_maintenance.sql    ltree path derivation and cascade
  V4__pricing_configuration.sql        price lists, discount policy, dormant VAT scheme
  V5__customer_codes.sql               customer code sequence, receivables indexes
  V6__alerting_rules.sql               the alert catalogue, one enabled rule per type
  V7__drop_purchasing.sql              removes the cancelled purchasing scope
  V8__reporting.sql                    sales rollups, inventory matviews, register indexes
  V9__platform_configuration.sql       app_config becomes the source of truth
  V10__drop_unread_auth_config.sql     removes auth rows nothing read
```

The shop can now sell, invoice, print and watch itself: quote a
contractor, convert the quote, take split tender, put the balance on account,
issue the invoice, print a receipt for whoever wants one carrying the batch and
expiry of what left, return goods to their original lot, credit them back — and
arrive the next morning to a slip saying what expired overnight, what fell below
reorder, and who is late paying.

### How the modules talk

One direction only, and deliberately so. `billing` and `printing` both call into
`sales`; sales does **not** call back. It publishes `SaleCompleted` and
`SaleReturned`, and they listen. Spring would happily wire the mutual version,
since the bean graph has no cycle — the events are there so the module boundary
stays real, and so a third listener costs completion nothing. `alerting` is that
third listener, and it was added without touching sale completion at all.

Listeners run **synchronously in the publisher's transaction**, but they differ
on failure, on purpose:

- **Billing must fail the sale.** An invoice that cannot be issued means a debt
  with nothing to owe it against, and a document number allocated for a sale
  that then fails has to roll back with it.
- **Printing is not on the sale path at all.** Nothing queues a receipt when a
  sale completes — most customers do not want one. `PrintingService.printReceipt`
  is called when somebody asks, and a printer that is unreachable is a toast at
  the till rather than a sale that will not close.

### Printing and the agent

The server **never emits ESC/POS bytes** (§10). It queues a structured print
document; the browser at the till relays it to the local agent on
`127.0.0.1:9110`, which encodes for whatever printer is attached and spools it.
Swapping an 80 mm Epson for a 58 mm Xprinter is agent configuration and nothing
else — which only holds because nothing server-side describes *how* to print.

The `type` discriminator on `PrintElement` is part of that wire contract:
renaming a subclass renames it on the wire and breaks every agent in the shop.

### The agro licence fields are withdrawn (V16)

The agro root declared `epa_registration_no`, `active_ingredient`, `hazard_band`
and `requires_buyer_record` as required fields, and V16 removes all four at the
owner's instruction. What that costs is worth stating plainly, because one of
them was a control rather than a form field:

- **The §6.3 buyer register can no longer fire.** `SaleCompletionService` reads
  `requires_buyer_record` to decide whether selling a restricted product obliges
  the counter to write the buyer's name and ID down. With no product carrying
  the flag, that obligation never arises. `ProductAttributes.flag` reads a
  missing key as `false`, so nothing fails — it simply never triggers.
- **Receipts no longer print the WHO hazard band.** `ReceiptTemplate` prints it
  only when the line carries one; the "Read the label before use" warning on
  batch-tracked lines is unconditional and stays.

**Nothing was deleted from the code.** `restricted_sale_record`,
`ProductAttributes`, the completion check and the register report are all
untouched, and `POST /api/categories/{id}/attributes` is a real endpoint — so
re-declaring the field on the agro root switches the control back on for every
product created afterwards, with no code change and no migration.
`SalePathTest` and `ReportingTest` now do exactly that before testing the
register, which is what keeps the mechanism honest: declare it optional, not
required, or every existing product is refused the next time somebody saves one.

What is deliberately kept is the half the shop uses daily: the agro root is
still `AGROCHEMICAL`, so products filed under it are batch tracked, carry an
expiry date, and feed the expiry alerts.

### Credentials nobody else can choose

A staff account holds two secrets, and neither can be set by an administrator.

A **password** is created as a one-time value the server generates, returned
once, and forced to be replaced on first sign-in. An admin who picks it knows
it, and `must_change_password` is what makes the generated one a delivery
mechanism rather than a shared credential. `TemporaryPasswordFilter` refuses
everything from an account still on one except changing it, asking who it is,
signing in and signing out — the last two because they carry their own identity
in the body, and refusing them on the strength of a stale header traps the very
sign-in that follows a password change.

A **till override PIN** is set only by its owner, against their own password
(`POST /api/auth/override-pin`). `PASSWORD_RESET` can clear somebody's
(`DELETE /api/admin/users/{id}/override-pin`); nothing can set one. An override
is re-evaluated under the approver's roles and recorded against their name, so a
PIN an administrator chose would put that name on an authorisation they never
gave.

**That endpoint is also why overrides work at all.** `override_pin_hash` was
read by `SupervisorOverrideService` and written by nothing, so every supervisor
in a real install had none — and an account with no PIN is refused in exactly
the same words as a wrong one, deliberately. The sale tests passed because they
wrote the hash through the repository, which no screen could do;
`SecurityIntegrationTest.the override pin round trip` now sets one through the
API and verifies the till accepts it.

### Configuration: which file wins

`app_config` holds what the **shop** decides — rounding increment, paper width,
expiry horizons, reorder terms, variance thresholds, the backup window. The
owner changes these from a settings screen, and waiting for a release to do it
would be absurd.

`application.yml` holds what the **machine** needs to start, and what must not
be changeable from a screen: datasource, JWT secret, port, lockout policy. A
signing key editable from a settings endpoint would not be a signing key, and an
endpoint that can set lockout max-attempts to 9999 can switch off brute-force
protection.

Until V9 and V10 the two overlapped and the database lost: `app_config` carried
rows nothing read while the same settings lived in yml, so changing a rounding
increment there did nothing, silently. `PlatformTest.noSettingLies` now asserts
every row in `app_config` corresponds to a key in `ConfigKeys` — a setting that
does nothing is worse than a missing one.

### Backups: verified, or it does not count

§14 calls losing this machine the end of the business's records. The nightly
`pg_dump` runs from the application rather than from a cron job, because the
constraint is a site with no developer on it and a backup that depends on
somebody remembering to configure it is the one that is not there.

**Staleness is measured from the last *verified* run, never the last successful
one.** A dump that has run for six months and never been restored proves the
process is alive, not that the file is readable. The weekly restore drill
restores into a scratch database, asserts row counts, and drops it again — never
over the live one. A shop with no verified backup raises a CRITICAL alert
hourly, which is the correct reading: it has no evidence any backup would
restore.

WAL archiving and the off-site USB rotation happen outside this process;
`POST /api/backups/record` exists so the register describes the shop's real
protection rather than only the part that runs here.

### Alerting, and not crying wolf

§11.1 is blunt: a system that cries daily gets ignored, and then the one that
mattered gets ignored with it. Three mechanisms fight that, all in the schema
rather than in code, and nothing may work around them:

- **Dedupe.** A partial unique index allows one open alert per `dedupe_key`.
  Re-raising while it is still open writes nothing and notifies nobody.
- **Auto-resolution.** Restocking above the reorder point closes the low-stock
  alert without anyone clicking anything. An alert only a human can close is one
  that accumulates until the list is worthless.
- **Snooze**, bounded to 14 days. Longer than that is a disabled rule wearing a
  disguise, and the disguise is the problem — nobody remembers to undo it.

Evaluation is event-driven where a movement can change the answer immediately
(`StockChanged`, `SaleCompleted`, `CashSessionClosed`) and scheduled where it
depends on the calendar. Every scheduled evaluator is idempotent and closes its
own stale alerts, so a restart never grows the notification centre.

Rules are rows, not code. Severity, channels and on/off are configuration; only
a new *type* needs a release, because a type is an evaluator.

### Reporting, and keeping aggregation off the till

One database serves both trading and analysis (§12), so the discipline is to
keep heavy aggregation off the tables the till writes to during opening hours.
Which mechanism a report uses is a decision about how current the answer has to
be:

- **Rollup tables**, incremented as sales complete and decremented on a void.
  "What have we taken today" is asked mid-shift, so it cannot wait for a
  nightly refresh. Derived data drifts, so `POST /api/reports/sales/rebuild`
  recomputes any date range from `sale` and `sale_line` — without that, the only
  remedy for a discrepancy is to distrust every report.
- **Materialised views**, refreshed 02:00 CONCURRENTLY. Stock valuation is read
  by somebody making a decision, not by a cashier, so yesterday's answer is
  fine. `report_refresh` records when, because PostgreSQL does not.
- **Direct queries** for the registers and batch traceability, which are run
  rarely and must be exact rather than as-of-last-refresh.

Batch traceability is the one §12 singles out: when a manufacturer recalls a
batch, "which customers received lot 4471" is one query. It sits behind
`REPORT_VIEW` and not `AUDIT_VIEW` — a recall is time-critical and the person
making the calls is whoever is in the shop.

Exports are CSV and XLSX. **PDF is not built**: an A4 report layout is a
typesetting job, and the thermal summary the till already prints covers the case
§12 gives for wanting one in hand.

The reorder alerts tell the shop what to buy; acting on that happens outside the
system, which is the intended arrangement rather than a gap.

Tests are Testcontainers against real PostgreSQL 16 — **Docker must be running**
before `./mvnw test`. H2 reproduces none of what these suites check.

| Suite | Covers |
|---|---|
| `LedgerConformanceTest` | Oversell, trigger semantics, the concurrent race |
| `SalePathTest` | Completion, idempotency, picking, tender, returns, voids |
| `BillingTest` | Gapless numbering, tax snapshots, quotations, statements |
| `PrintingTest` | Receipt contents, batch/expiry on agro lines, queue handover |
| `AlertingTest` | Dedupe, auto-resolution, snooze bounds, reorder recompute |
| `PlatformTest` | Settings reaching the sale path, verified-vs-successful backups |
| `ReportingTest` | Rollup/rebuild agreement, traceability, ABC, export formats |
| `PricingConformanceTest` | Price windows, list fallback, compound tax basis |
| `CustomerAccountTest` | Allocation order, sign convention, credit gating |
| `SecurityIntegrationTest` | Filter chain, method security, account rules |
| `CategoryPathTest` | The `ltree` ↔ `@Formula` seam, through both read paths |

## Planned module layout

Package-by-feature, each module internally layered the way `gig-gha-identity`
already is. Modules talk through published application events and each other's
service interfaces — never through each other's repositories. Spring Modulith is
worth adding for the boundary test alone, which fails the build when someone
reaches across.

| Module | Owns |
|---|---|
| `platform` | Branch, configuration, document sequences, backup orchestration |
| `identity` | Users, roles, permissions, supervisor PINs, audit log |
| `catalog` | Products, categories, attributes, units, barcodes. The regulated agro fields are category attributes, not a table of their own — V15 retired `agro_profile` |
| `pricing` | Price lists, effective-dated prices, discount policy, tax schemes |
| `inventory` | Lots, movements, balances, stock takes, transfers, picking |
| ~~`purchasing`~~ | **Out of scope** — ordering is not done from this system. Goods are received directly through `inventory` |
| `parties` | Customers, credit limits, contacts |
| `sales` | Cart, sale, payments, returns, voids |
| `billing` | Quotations, invoices, delivery notes, receipts, statements, ageing |
| `printing` | Print documents, templates, job queue, agent protocol |
| `alerting` | Rules, evaluation, deduplication, notification centre, channels |
| `reporting` | Read models, rollups, exports |

Everything above is built except `purchasing`, which is cancelled.

## Conformance suite

`src/test/sql/ledger_conformance.sql` proves the runtime properties the schema
asserts but that no parser can confirm. **19/19 passing on PostgreSQL 16.14**,
covering balance accumulation, decimal quantities, oversell rejection, selling
to exactly zero, append-only enforcement on `stock_movement` and `audit_log`,
drift detection, gapless numbering, catalog invariants, alert deduplication and
FEFO ordering.

```bash
docker compose -f ../docker-compose.yml exec -T postgres psql -U counterweight -d counterweight < src/test/sql/ledger_conformance.sql
```

It rolls back everything it does and exits non-zero on any failure, so it drops
straight into CI.

## Testcontainers and Docker Engine 29 — resolved, and worth knowing

The suite runs against real PostgreSQL 16 through Testcontainers. On Docker
Desktop with Engine 29 it did not run at all: every docker-java provider
strategy was rejected with

```
BadRequestException (Status 400: {"ID":"","Containers":0,...,"ServerVersion":"",...})
```

— an HTTP 400 with an empty `Info` body. The engine answers the `/info` probe,
docker-java cannot parse what the version negotiation returns, and the `docker`
CLI is fine throughout, which is what makes it look like anything other than a
client problem.

The fix is one line in `pom.xml`: a surefire system property **`api.version`**
pinned to `1.44`. The spelling is the whole trap — the Maven property is
conventionally written `docker.api.version`, the key docker-java actually reads
is `api.version`, and setting the former does nothing at all, silently. Neither
`DOCKER_API_VERSION` in the environment nor an explicit `DOCKER_HOST` helps.

Confirmed green against Engine **29.6.2** and **29.7.2**. If the suite ever
reports that no provider strategy worked, check that property before anything
else.

## The race is automated

The one case plain SQL cannot express in a single session — **the concurrent
oversell race** — is now a test rather than a manual demonstration.
`LedgerConformanceTest` runs two tills at the last unit of stock and asserts
that exactly one wins, the loser is rejected by the balance check after
blocking on the row lock, and the balance lands on zero rather than below it.
It runs with everything else on `./mvnw test`.

## Before changing the schema

Stock quantity is never updated in place. Corrections are compensating
movements, not `UPDATE`s — and a lot's `unit_cost` can never be edited once
movements exist against it. Section 5 of
[the architecture document](../docs/architecture.html) explains why and what it
costs.
