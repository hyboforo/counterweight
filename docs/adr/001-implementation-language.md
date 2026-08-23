# ADR-001 — Implementation language

**Status:** Accepted — **Kotlin**
**Date raised:** 2026-08-20
**Date decided:** 2026-08-20
**Blocks:** nothing. Server scaffolding proceeds on Kotlin.

## Context

The team's existing `gig-gha-identity` service is Kotlin on Spring Boot 3.3 /
JVM 21, documented as a deliberate stack choice, with three or four
contributors in its history. Counterweight is a separate product for a
different business and shares no code with it, so continuity here buys
developer familiarity only — not shared libraries and not shared
infrastructure.

## Assessment

On Java 21 the technical case is close to a wash.

| | Kotlin | Java 21 |
|---|---|---|
| Null safety | Compile-time, non-optional | Opt-in (`Optional`, JSpecify + NullAway) |
| Value objects | `@JvmInline value class`, zero-cost | No equivalent until Valhalla |
| Closed hierarchies | `sealed` + exhaustive `when` | `sealed` + pattern-matching `switch` — comparable |
| DTOs / commands / events | `data class`, named + default args | `record`, no named/default args |
| JPA friction | Real: needs `allopen` + `noarg`; `data class` is a trap | None |
| Annotation processing | MapStruct is kapt-only; kapt is in maintenance | Native, fast |
| Concurrency | Coroutines | Virtual threads — simpler with Spring |
| Hiring and handover in Ghana | Shallower pool | Much deeper pool |

Kotlin's residual advantages are **compile-time null safety** and
**`value class`**. Both are real, both are narrower than they were before Java
21, and null safety is weakest exactly at the JPA and Jackson boundaries where
most production nulls originate. The bugs that would genuinely hurt this shop
are not NPEs but silent arithmetic errors — wrong rounding on a cut-to-size
price, wrong cost basis, stock double-deducted on a retried request. Kotlin
does nothing for any of those.

## Deciding factor

**Who maintains this in three years.** The technical merits are too close to
decide it.

- **Kotlin** — if the current team builds and keeps it. Ramp-up is zero.
- **Java 21** — if it is likely to pass to the shop's own IT contact, a local
  contractor, or a junior hire. Deeper pool, Java-first documentation, no JPA
  or kapt friction.

## Decision

**Kotlin**, with the guardrails below. The team has shipped Kotlin on this exact
stack before, so ramp-up is zero, and the domain is unusually dense in the value
objects and closed hierarchies Kotlin handles well.

This accepts a known cost: if the system later passes to a local contractor or a
junior hire, the Ghanaian Kotlin pool is shallower than the Java one. Revisit
this ADR if maintenance ownership changes — the schema, the ledger semantics and
the agent protocol are all language-neutral, so a port would be confined to the
service layer rather than being a rewrite.

## Guardrails

These are binding, not advisory. The defaults will bite otherwise:

1. **Never `data class` for JPA entities.** Plain `class`, `var` properties,
   business-key `equals`/`hashCode`. Enable `kotlin-allopen` + `kotlin-jpa`.
2. **`data class` freely** for DTOs, commands, domain events and print
   documents — that is where the leverage is.
3. **Drop MapStruct.** Write mappers as extension functions. Removes kapt from
   the build entirely. This is a deliberate divergence from `gig-gha-identity`.
4. **`@JvmInline value class` for every ID and for `Money`.** Passing a `LotId`
   where a `ProductId` belongs becomes a compile error.
5. **`sealed interface` for `StockMovement`, `PaymentMethod`, `PrintElement`.**
   Adding a movement type then makes the compiler enumerate every site that
   must handle it.

## Consequences

Nothing else in the design depends on the answer. The schema, the ledger
semantics, the agent protocol and the module boundaries are all
language-neutral. Code examples in the architecture document are Kotlin because
that is the current house stack; they translate directly.

## Related divergence already taken

The baseline migration uses `TEXT` + `CHECK` for enumerations rather than
PostgreSQL `ENUM` types, diverging from `gig-gha-identity`. Reason:
`ALTER TYPE ... ADD VALUE` cannot run inside a transaction, which fights
Flyway's transactional migrations, and the movement-type and document-type
vocabularies will grow. This holds regardless of how ADR-001 is decided.
