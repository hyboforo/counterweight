# counterweight-web

React 18 + Vite + TypeScript + Tailwind. Two surfaces in one build:

- **Till** — the selling screen. Optimised for speed with a customer waiting:
  keyboard-first, search by name / local name / short code, held sales, split
  tender.
- **Back office** — products and categories, goods receipt, stock takes,
  customers and receivables, reports, alerts, configuration. (No purchasing:
  the shop does not raise purchase orders from this system.)

Built output is served by `counterweight-server` over the shop LAN. There is no
separate web host.

**The till and the back office are both built.** The back office has seven
sections — goods receipt (`STOCK_RECEIVE`), stock takes (`STOCK_COUNT`), the
catalogue (`PRODUCT_MANAGE`), receivables (`CUSTOMER_MANAGE`), reports
(`REPORT_VIEW` or `AUDIT_VIEW`), people (`USER_MANAGE` or `ROLE_ASSIGN`) and
settings (`CONFIG_MANAGE` or `BACKUP_MANAGE`) — tab-switched, and each tab
appears only for the permission that owns it.

Which room a user lands in is decided by permission, not by preference. A
storekeeper holds `STOCK_RECEIVE` and `STOCK_COUNT` and no till permissions, so
sending everyone to the till stranded them: `App.tsx` gates the till on
`SALE_CREATE` and the back office on holding any one of those permissions, and
opens whichever the user can actually use. An auditor is the same problem in a
different shape — `AUDIT_VIEW` and nothing else — which is why reports are on
that list too, and the system administrator is a third: nothing commercial at
all, which is why `USER_MANAGE`, `ROLE_ASSIGN`, `CONFIG_MANAGE` and
`BACKUP_MANAGE` are on it as well.

**The room resets with the person.** None of this state unmounts on sign-out, so
without clearing it the cashier coming on after a supervisor who was reading
reports would land in the back office.

## Running it

For the shop, `start-counterweight.bat` at the repo root brings up the whole
system in Docker and there is nothing else to install. For working on the web
build:

```bash
docker compose up -d                       # from the repo root
export COUNTERWEIGHT_JWT_SECRET="$(openssl rand -base64 48)"
cd counterweight-server && ./mvnw spring-boot:run
cd counterweight-web && npm install && npm run dev
```

**The secret is not optional.** `JwtService.verifySecret` refuses to start on a
blank, short, or placeholder value, so without it the server dies during
startup with a bean-creation failure rather than serving anything. Any 32-byte
value does for development; a shop install gets a real one that outlives the
process, since regenerating it signs out every till.

**There is no demo data.** A fresh database comes up with the shop's own
categories, the units of measure, the roles and the two bootstrap accounts —
nothing else. The `seed-dev.mjs` script that used to fill it with sample
products, prices and customers is gone: this repository is what gets installed
in the shop, and a script one command away from writing invented stock into the
live ledger is not something to keep lying around. Build a catalogue from the
back office.

Vite proxies `/api` to `localhost:8080` in development so the browser sees one
origin and no CORS preflight sits in front of every till request. In the shop
there is no proxy and no second port: the server serves the built app itself.

## What is in the till

Sign-in, product search by name / local name / SKU, barcode scanning, quantity
prefix, held sales, split tender with a live credit check, supervisor PIN
approval, and an optional receipt.

**Signing in lands on the selling screen.** Nothing stands in front of it —
there is no drawer in this shop, so no float to count and no session to open.
The till code lives in `localStorage` (`src/till/tillCode.ts`) and exists only
to route a receipt back to the printer next to that machine.

**The receipt is a button, not a side effect.** Completing a sale queues
nothing. The completed-sale panel offers *Print receipt* (hotkey `P`), which
posts to `/api/printing/receipt`; `usePrintRelay` hands queued jobs to the agent
on `127.0.0.1:9110`. A till with no printer attached shows "No printer on this
till" in the status bar and sells exactly as normal.

The button stays available for further copies, marked as reprints, **because
that panel is the only place a receipt can be asked for.** There is no
sale-history screen and no print-queue screen, so once the cashier moves to the
next customer the receipt for that sale is out of reach from the UI. If the shop
turns out to want receipts after the fact, that is the screen to build.

## What is in the back office

**Goods received** — book in a delivery against its note: search or scan, pick
the unit it arrived in, enter quantity and cost, plus batch and expiry for
anything batch tracked. The whole receipt posts as one transaction.

**Stock takes** — open a count over a category subtree or the whole shop, enter
the count blind, sign it off, and — with `STOCK_ADJUST` — review the variances
and post them.

**Catalogue** — the category tree, what is filed under it, and one product at a
time: names, the fields its category declares, its units, and what it sells
for. `PRODUCT_MANAGE` opens it; `PRICE_VIEW` decides whether the prices section
appears at all and `PRICE_MANAGE` whether it can be changed, so a storekeeper
files stock and reads prices without setting them.

**Three things it will not edit, each for the same reason.** The SKU is what
shelf labels and order books point at; the base unit is what every row in
`stock_movement` is counted in; a unit's factor is arithmetic every price
quoted in that unit depends on, so re-sizing a carton from 12 to 24 would halve
its per-piece price with no figure on screen changing. A pack that changes size
is a new unit — add it and clear `sold` on the old one, which leaves the
history readable.

**The product form is built from the category's declarations, not from what the
product happens to carry.** §6.1 makes a category data entry rather than a
migration, and attributes inherit down the path — so a herbicide filed three
levels under `agro` is asked for the EPA number the root declares. Driving the
form from stored keys instead would mean a field added to a category never
appears on the products that predate it, which is exactly how
`requires_buyer_record` stayed unanswerable until V15.

**A category under an agro-chemical one is agro-chemical.** The server refuses
the other reading, and the screen shows the box ticked and disabled. A GENERAL
category under `agro` would inherit the EPA and hazard fields and then let its
products be sold with no expiry to enforce — the licence fields present, the
control they exist for absent.

**A barcode belongs to one unit of one product**, and the refusal names what
already holds it rather than reporting a constraint. Clearing it is how a code
moves. The field saves when it is left, not per keystroke: a wedge scanner
types thirteen characters in about forty milliseconds and would otherwise race
itself against the uniqueness index.

**Prices are set per unit per list, and never edited in place.** Setting one
closes the row it supersedes the day before, so the table answers "what did
this cost on the 3rd" by itself; a date in the past is refused, because
back-dating cannot change what a sale already charged and only breaks a report.
A price dated ahead shows under *starting later* and can be cancelled until it
starts. A row priced from the default list while a trade list is selected says
so — a contractor price that is quietly the walk-in one is worth seeing before
somebody quotes it.

**Reports** — the seventeen reports of §12, in one screen. The rail lists only
what the account may run, because the permissions are split deliberately:
`REPORT_VIEW` sees trade, `COST_VIEW` is what adds cost and margin, and the four
control registers need `AUDIT_VIEW`. Offering a row that answers 403 when
clicked teaches the floor that the back office is broken rather than that they
are not allowed.

**One table renderer, not seventeen screens.** The server reduces every report
to `{title, headers, rows, types}` and builds the CSV and XLSX from that same
definition, so there is no path by which the screen and the file can disagree.
`types` is why the screen can write money to two decimals without guessing:
JSON turns a `BigDecimal` and an `Int` into the same bare number, and a takings
column that happens to be whole would otherwise read `261` against a file
saying `261.00`.

**Exports re-run on the server rather than posting the table back.** The screen
sends the report id and its parameters, never rows. A client-supplied table
would let anyone download any figures they could compose, permissions checked or
not — and it goes through `fetch` rather than a link because a link carries no
Authorization header.

The export also demands `REPORT_EXPORT` on top of the report's own gate, and
the buttons are hidden without it. Taking a file off the premises is a different
act from reading a figure on a screen the shop controls; until that check went
in, the permission was granted to two roles and read by nothing.

**Stock valuation and expiry ageing say how stale they are.** Both read
materialised views rebuilt at 02:00, which is the right place for that work and
the wrong thing to leave implicit — so the screen prints the refresh time and
offers to rebuild. An owner reading a valuation has to know whether it includes
this morning.

**The slip prints by receipt id, never by sending the lines back.** A delivery
is a numbered document (GRN-000001) and the server renders its slip from
`goods_receipt_line`, so the paper says what was recorded rather than what this
browser still happens to hold.

**The receipt draft is mirrored to `localStorage`.** A receipt posts all lines
or none, so there is no server-side draft to fall back on, and fifteen typed
lines living only in a browser tab on a shop PC is not something §14 would
accept. It is restored on load and deleted once empty.

**Cost is entered per purchase unit and stored per base unit.** Whenever the
chosen unit is not the base unit, the screen shows the derived per-base cost —
a carton of twelve booked at the price of one piece is wrong by a factor of
twelve, silently, and every margin drawn from that lot afterwards is wrong too.
Entering cost is not the same as viewing it: a storekeeper types what the
delivery note says without holding `COST_VIEW`, and the printed slip omits cost
unless they do.

**Which room you land in is decided by permission, not preference.** A
storekeeper holds `STOCK_COUNT` and not `SALE_CREATE`, so sending everyone to
the till would strand them on a screen they are not permitted to use. Whoever
can only count goes straight to the back office; whoever can do both gets a link
each way.

**People** — who works here, what they may do, and the two credentials they
hold. Create an account and the server returns a one-time password shown once
and never retrievable; the account can do exactly one thing until it is
replaced. Roles can be handed out, taken back, and an account switched off —
never deleted, because every sale, count and adjustment points back at it.

**It is deliberately not a permission editor**, and `ROLE_MANAGE` stays dormant
because of it. What a role carries — that a storekeeper counts but does not
post, that sales staff never see cost — is the shop's separation of duties
written down in the migrations. Adding `COST_VIEW` to SALES_STAFF from a screen
would undo the reason the role exists, quietly, with no record of who decided
it. Changing what a role carries is a migration.

**The role picker asks the server what it may offer.** `GET /api/admin/roles`
returns each role with `grantable` and the permissions it carries that the
caller does not hold — computed by the same code that refuses the grant. A
picker deciding for itself would drift from the guard in one of two directions,
and both read as the system being broken. It also means a role already held
that you could not grant locks the whole editor rather than failing on save:
the server checks the set it is handed, not the difference.

**A till PIN is set by its owner and by nobody else.** An override is
re-evaluated under the approver's roles and recorded against their name, so a
PIN an administrator chose would put somebody's name on an authorisation they
never gave. Administrators can clear one; the owner sets it from *My account*,
against their own password. Until that screen existed `override_pin_hash` was
read by the override path and written by nothing — every supervisor in a real
shop had none, and every approval would have been refused in the same words as a
wrong PIN. The people list now shows *no till PIN* against anyone who can be
asked for one.

**Settings** — the eleven rows of `app_config`, grouped by prefix, each with the
description an owner reads before changing it. What the *shop* decides lives
here; what the *machine* needs to start — datasource, signing key, lockout
policy — lives in `application.yml` and is deliberately not editable from a
screen. Nothing here creates a key: every row corresponds to code that reads it,
and a screen that accepted free-form keys would accumulate
`currency.rounding.incremnt` sitting next to the real one.

**Backups lead with the verified date, not the last successful dump.** A dump
nobody has ever restored proves the job runs, not that the file can be read
back, so the card says *Not protected* until a restore drill has passed. The
drill restores into a scratch database and drops it; it never touches the live
one. Backups taken outside this process — WAL archiving, a disk carried home —
are recorded here so the register describes the shop's real protection.

**Replacing a temporary password takes over the screen.** The server refuses
every request from an account still on one, so rendering anything else would be
a screen that answers 403 to its own first request. The rules are not restated
on this side: length, the blocklist and "not your own name" live in
`PasswordPolicy`, and a copy here would drift from it — the screen enforces only
the thing it owns, that the two boxes match. Succeeding kills every session for
that account, this one included, so it signs straight back in with the new
password rather than leaving somebody at a till that 401s on the first scan.

**The count sheet's row order is the printed sheet's row order.** Both come from
`/sheet`, and the dominant use is transcription from paper — one PC, one
printer, a storekeeper with a pen. Re-sorting as lines are counted (uncounted
first, say) would move rows under the transcriber's eyes and lose their place.

## Design constraints that fall out of the architecture

**The till is not a general web app.** It runs all day on a cheap Windows PC,
often on a small screen, operated by someone who is not looking at it. Measure
**keystrokes-to-complete** as an actual target — if the system is slower than
pen and paper under pressure, staff will route around it and the inventory data
becomes fiction.

**Most products have no barcode.** Search has to be fast and forgiving.
`pg_trgm` indexes exist on `product.name` and `product.local_name` for exactly
this.

**Quantity is decimal.** Inputs must accept `3.5` metres and `2.25` kg. The
per-unit decimal allowance is on `uom.decimals` — respect it rather than
hardcoding.

**Printing goes through the local agent, not the browser, and only on request.**
The till POSTs a print document to `http://127.0.0.1:9110`. Chrome and Edge treat `127.0.0.1` as
a potentially trustworthy origin, so an HTTPS page may call it without
mixed-content blocking. Verify on the exact browser build the shop runs before
Phase 2 closes.

**Cost prices are permission-gated.** `COST_VIEW` is a real permission — a
cashier must not see margin. Do not ship cost fields to the client and hide them
in CSS.

**The count sheet is blind and must stay blind.** `GET /api/stock-takes/{id}/sheet`
does not return the expected quantity, and the back-office screen must not fetch
it from anywhere else to "help" the counter. The variance report is a separate
endpoint behind `STOCK_ADJUST` for the same reason cost is gated.

## Not a PWA for offline reasons

The server is on the same switch. The service worker is for app-shell caching
and fast reloads, not for offline selling — if the LAN is down the till is
read-only by design. See the degradation ladder in
[the architecture document](../docs/architecture.html) §14.1.
