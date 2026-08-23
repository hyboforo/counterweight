# Documentation

## Start here

**[architecture.html](architecture.html)** — the full system architecture. Open
it in a browser; it is a self-contained page with no dependencies. Seventeen
numbered sections, cited elsewhere in this repo as §5, §11.2 and so on.

Sections worth reading before writing any code:

| § | Why |
|---|---|
| **§1** | The four fixed constraints everything else follows from |
| **§2** | Why a hardware shop and an agro-chemical shop have opposite inventory problems, and how one model serves both |
| **§5** | The stock ledger. The single most consequential decision in the system |
| **§6.1** | Why adding a product category is data entry and never a migration |
| **§10** | The print pipeline and the server/agent split |
| **§14** | Backup and the degradation ladder. Not optional hardening |

## Decision records

| ADR | Title | Status |
|---|---|---|
| [001](adr/001-implementation-language.md) | Implementation language | **Accepted** — Kotlin |

Decisions 002–008 are settled and recorded in §4 of the architecture document
rather than as separate files, because none of them needed the long-form
treatment ADR-001 does.

## Open questions

Six remain, listed in §17.1. The two that would change scope:

1. **Restricted agro-chemical buyer register** — licence condition or
   value-add? The schema supports it
   (the `requires_buyer_record` attribute on the agro root, and
   `restricted_sale_record`), but whether
   it can be dropped under time pressure depends on the answer.
2. **Does the shop deliver** with its own vehicle? Delivery notes would then
   need driver, vehicle and signature capture — a small module that is not
   currently scoped or in the schema.

## diagrams/

Empty for now. The three diagrams in the architecture document are inline SVG
authored by hand and live in that file. This directory is for source files if
any diagram later outgrows that — a tool file, not a rendered export.
