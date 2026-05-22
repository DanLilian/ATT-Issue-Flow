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