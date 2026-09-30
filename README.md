# Counterweight

Point-of-sale and inventory control for a combined hardware and agro-chemical
shop. Designed to keep selling when the power and the internet do not.

> **Status — server complete, till complete, back office complete.**
> The full architecture is in [`docs/architecture.html`](docs/architecture.html).
> All eleven server modules are built against seventeen migrations. The till sells
> end to end. The back office has goods receipt, stock takes, receivables,
> collections, reports, the catalogue, staffing and the shop's settings. The peripherals
> agent is built and prints to the shop's Syncotek over USB.

## Layout

```
counterweight/
├── docs/                     the write-up
│   ├── architecture.html     full system architecture, 17 sections
│   ├── adr/                  decision records
│   └── diagrams/
├── counterweight-server/     Spring Boot 3.3, JVM 21 — domain, DB, serves the web build
├── counterweight-web/        React 18 + Vite + TS + Tailwind — till and back office
├── counterweight-agent/      local printing service on each till (ESC/POS)
├── Dockerfile                builds the till and the server into one image
├── docker-compose.prod.yml   what the shop runs: database + application
├── start-counterweight.bat   double-click this on the shop PC
├── stop-counterweight.bat    and this to shut it down
└── docker-compose.yml        PostgreSQL 16 for local development only
```

Each module has its own README covering what it owns and what to know before
working in it. Start with [`docs/README.md`](docs/README.md).

## The four constraints everything follows from

| Decision | Consequence |
|---|---|
| **Local server only**, no cloud | Nothing on the sale path may require a network hop beyond the shop switch |
| **Windows tills + local printing agent** | One web codebase; USB, Bluetooth and network printers all supported |
| **One shop, branch-ready schema** | `branch_id` on every transactional table from day one |
| **Not VAT-registered** | Tax engine is built and wired, configured to a zero scheme |

## What is built

| Component | State |
|---|---|
| `counterweight-server` | **Complete for scope.** Eleven modules — `platform`, `identity`, `catalog`, `inventory`, `pricing`, `parties`, `sales`, `billing`, `printing`, `alerting`, `reporting` — on seventeen migrations |
| `counterweight-web` — till | **Complete.** Sign-in, search and scanning, held sales, split tender, credit check, supervisor PIN, optional receipt printing |
| `counterweight-web` — back office | **Complete for scope.** Goods receipt, stock takes, receivables, collections, reports, catalogue maintenance, staffing (users and roles) and the shop's settings, including backups |
| `counterweight-agent` | **Built.** ESC/POS print agent on each till — USB via the Windows spooler, network and serial too. The shop's printer is a Syncotek on USB |

**Collections are the owner's alone.** Every so often the owner takes away what
the shop has taken in and records it as a collection (`COL-000001`): when, who,
and for each tender — cash, mobile money, transfers, cheques, card — what the
sales say should be there against what was counted. Collections chain in the
schema, each starting where the last one ended, so every payment, void and
counter refund lands in exactly one of them. A count that differs from the
sales needs a reason, and a recorded collection can never be edited. Only
`ADMIN` holds `SALES_COLLECT` (V17); the collections report sits under Money
behind `REPORT_VIEW`, so an auditor can read what was collected without being
able to collect.

**A supervisor's till PIN can be set only by its owner.** An administrator can
take one away and cannot choose one, because an override is recorded against
the person whose PIN was typed — a PIN somebody else picked would put their
name on an authorisation they never gave. Until the account screen existed,
`override_pin_hash` was read by the override path and written by nothing, so
every approval in a real shop would have been refused in the same words as a
wrong PIN.

Two things are out of scope by decision rather than omission: the shop does not
raise **purchase orders** from this system (goods arrive through
`inventory.receive`; V7 dropped the tables), and **branch transfers** stay
dormant until there is a second branch (§5.1).

[ADR-001 — implementation language](docs/adr/001-implementation-language.md) is
**settled: Kotlin**.

## Installing it in the shop

One command, and nothing to install but Docker Desktop:

```
start-counterweight.bat
```

It writes a signing key and a database password into `.env` on first run,
builds the image, waits until the application is actually answering rather than
merely started, and opens the till. Running it again is safe — it never
replaces a key that already exists, because regenerating one signs every till
out.

**Closing that window does not stop the shop**, and neither does restarting
Windows: the containers are marked `restart: unless-stopped`, so they come back
on their own as long as Docker Desktop starts with Windows. To actually shut it
down:

```
stop-counterweight.bat
```

It asks first — somebody may be mid-sale — and deletes nothing. Sales, stock,
customers and backups live in volumes that outlive the containers, so starting
again brings the shop back exactly as it was. A sale that was in progress is
held on the server rather than in the browser, so it survives too.

**Everything is one process on one port.** The web build is copied into the
server's static resources at image build time, so there is no second web host,
no reverse proxy and no CORS: `http://localhost:8080` is the till, the back
office and the API. §3 says there is no developer on site, and every extra
moving part is something that can be found broken on a Monday morning by
somebody whose job is selling cement.

**PostgreSQL is a separate service with its own volume**, deliberately: the
shop's records have to outlive any rebuild of the application image. Its port
is not published — only the application container needs to reach it.

**Back up `.env` somewhere off the machine.** Losing the signing key signs
everybody out; losing the database password locks the shop out of its own
records.

**A fresh database has no demo data.** It comes up with three categories — agro
chemicals, hardware, garden inputs — the units of measure, the roles and two
accounts: `owner`, which runs the shop, and `sysadmin`, which runs the system.
Both are forced to replace their first password before they can do anything,
and that first password is written to the log exactly once:

```
docker compose -f docker-compose.prod.yml logs app
```

**The print agent is not in the container.** It runs on each till machine
beside the printer it drives — see `counterweight-agent/README.md`.

## Running the database locally

```bash
docker compose up -d
```

Postgres listens on **5434** — chosen to avoid colliding with a local install on
5432 and with `taskers-ghana` on 5433. The container comes up **empty**; the
server applies the migrations through Flyway on startup. Credentials are in
`docker-compose.yml` and are development-only.

Confirm it came up clean — an empty database is the correct result here, and
the tables appear once the server has started and migrated:

```bash
docker compose exec postgres psql -U counterweight -d counterweight -c "\dt"
```

The migration directory is deliberately **not** mounted at
`/docker-entrypoint-initdb.d` any more. The entrypoint would run every `*.sql`
there on first init, applying the migrations with no `flyway_schema_history` to
show for it, and Flyway — set to `baseline-on-migrate: false` — then refuses to
start against a schema it has no record of. A database created under the old
mount has to be dropped once:

```bash
docker compose down -v && docker compose up -d
```

Kill any running server before recreating the volume. A JVM left pointed at a
wiped database gives confusing 500s, and a second `spring-boot:run` silently
loses the race for port 8080.

## The one thing to know before touching the schema

Stock quantity is **never updated in place**. There is no
`UPDATE ... SET qty = qty - 1` anywhere in this system. Every change is an
immutable signed row in `stock_movement`; `stock_balance` is derived by trigger
and carries `CHECK (qty_base >= 0)`, which is what makes overselling impossible
rather than merely unlikely. Both `stock_movement` and `audit_log` reject
`UPDATE` and `DELETE` outright.

Corrections are compensating movements. A lot's `unit_cost` can never be edited
once movements exist against it. §5 of the architecture document explains why,
and what that costs.

## Verification status

Two layers, and both need Docker running.

**The JVM suite** — **247 tests across thirteen classes, all passing**, one class
per module. Testcontainers against real PostgreSQL 16 rather than H2,
deliberately: the invariants under test are triggers, partial unique indexes and
check constraints that H2 does not reproduce.

```bash
cd counterweight-server && ./mvnw test
```

If Testcontainers reports that no provider strategy worked, check the
`api.version` system property in `pom.xml` before anything else. Docker Desktop
Engine 29 answers docker-java's default negotiation with an HTTP 400 and an
empty Info body, so every strategy is rejected and no container starts; the pom
pins `1.44`. Note the spelling — the key docker-java reads is `api.version`, and
setting `docker.api.version` instead does nothing at all, silently.

**The ledger conformance script**, which predates the server and still runs
standalone against a live database. The baseline applies clean to **PostgreSQL
16.14** from empty, and the suite passes **19/19**, including the
concurrent-oversell race:

```bash
docker compose up -d
docker compose exec -T postgres psql -U counterweight -d counterweight < counterweight-server/src/test/sql/ledger_conformance.sql
```

The suite rolls everything back, so it is safe against a populated database. It
exits non-zero if any check fails.

Two real bugs were found by running it, both now fixed:

1. **The balance trigger could not be an `INSERT ... ON CONFLICT`.** PostgreSQL
   evaluates `CHECK` constraints on the proposed tuple *before* diverting to the
   `DO UPDATE` path, so a negative delta tripped `qty_never_negative` on every
   sale. The balance row is now created with the lot and the trigger is a pure
   `UPDATE`. See §5 of the architecture document.
2. A seed `INSERT` mixed `NULL` (inferred `text`) with `text[]` across `UNION`
   branches.

Separately verified with two live sessions: two tills selling the same last unit
serialise on the row lock, the loser is rejected by the check constraint after
waiting ~3.3 s, exactly one `SALE_ISSUE` persists, and the balance lands on zero
rather than going negative.

## Delivery

Eight phases, roughly 22–23 weeks for one developer. Full breakdown in §16.
Two notes that matter more than the estimate:

- **The printer is bought — a Syncotek, USB.** It installs as the Windows queue
  `80mm Series Printer`, and `counterweight-agent --test-print` is what proves
  a till before anyone sells from it. The agent is the only component that
  touches real hardware, which is why it was not written until the hardware
  existed.
- **Do not cut Phase 7.** A wrong opening stock count produces "the system says
  four, we have none" in month one, and that distrust is very hard to recover
  from.
