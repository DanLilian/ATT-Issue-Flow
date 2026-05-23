# AI Usage Disclosure

## Model used
- **Claude Opus 4.7** (Anthropic), accessed via the Claude web interface.

## How AI assisted
- **Planning:** Phased implementation roadmap, identification of risky requirements, and ordering of work to keep the build green at every step.
- **Architecture:** Package layout (feature-first), soft-delete strategy (`@SQLRestriction`), JWT logout strategy (DB-backed JTI deny-list), schema management tool (Flyway over `ddl-auto`).
- **Code:** Drafted entities, DTOs, services, controllers, and tests. I reviewed and adapted each piece before committing.
- **Testing:** Identified high-risk business rules (optimistic locking, forward-only status transitions, mention recompute, CSV quoting, escalation idempotency, auto-assign tie-breakers) and the minimum tests to prove correctness.
- **Documentation:** Drafted `run.md` and this file.

## Accountability
All code in this repository was reviewed by me before commit. I can explain every design decision and every non-trivial line of code. AI accelerated the work but did not replace judgment.

## Main mentor prompt
The conversation was opened with a senior-mentor role prompt that constrained the assistant to: guide step-by-step rather than dump full solutions; respect the README API table as the implementation contract; prefer Spring Boot conventions over heavyweight patterns; explain *why* every decision matters; and never proceed if the build is broken.

## Notable follow-up prompts
(Logged as the project progresses.)

### Phase 1 — Project setup
- Asked the assistant to choose between Flyway vs `ddl-auto`, springdoc vs none, and stateless logout vs deny-list, given a strict reading of the requirements. Decisions made: Flyway, springdoc, JTI deny-list.

### Phase 2 — Domain model
- Asked the assistant to design entities, repositories, and the first Flyway migration as a coherent unit.
- Discussed and chose: `@SQLRestriction` over manual soft-delete filtering, `@Version` on Ticket and Comment only, `@EmbeddedId` + `@MapsId` for composite keys, bytes-in-DB for attachments at this scale, append-only AuditLog enforced by absence of mutators, and Postgres partial index for the escalation scan.
- During Postgres verification, hit `Schema-validation: wrong column type` on `attachments.data` — `@Lob byte[]` defaults to OID storage on Postgres. Replaced with `@JdbcTypeCode(SqlTypes.BINARY)` + `columnDefinition="bytea"` so the entity, migration, and runtime all agree on inline BYTEA storage.
- Reviewed the full schema against the entity model and the README contract before merging.

### Phase 3 — Validation and error handling
- Asked the assistant to design a consistent error-response shape and a single @RestControllerAdvice mapping every relevant exception.
- Decided to keep domain exceptions minimal (four total) rather than per-entity hierarchies — the message string carries specificity, not the type.
- Mapped OptimisticLockingFailureException -> 409 to satisfy the spec's "no concurrent updates" rule for tickets and comments.
- Mapped DataIntegrityViolationException -> 409 with a generic message (no SQL state leakage to clients).
- Verified with a throwaway controller via standalone MockMvc — confirms the mapping works without needing any production controller.