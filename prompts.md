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

### Phase 4 — User management
- Asked the assistant to implement the User endpoints exactly per the README contract, including the two unconventional shapes (POST /users returns 200 not 201; POST /users/update/{id} instead of PATCH).
- Discussed the spec gap around password: the README's create body has no password field, but POST /auth/login (Phase 5) requires one. Decided to add `password` to CreateUserRequest as the simplest defensible choice. Documented here.
- Chose to add only `spring-security-crypto` for now (not the full starter) so password hashing is available without auto-locking endpoints. Full Spring Security arrives in Phase 5.
- For delete: chose to flush() inside a try/catch in the service so FK-RESTRICT violations surface as a specific ConflictException with a clear message, not a generic 409 from the global handler.
- Tests structured as slice tests (@DataJpaTest for service, @WebMvcTest for controller) rather than a full @SpringBootTest — much faster and the focus per test is sharper.

### Phase 5 — JWT authentication
- Asked the assistant to design a JWT auth flow that honors the spec's "logout invalidates" requirement. Discussed three options (stateless expiry, JTI deny-list, refresh tokens) and chose JTI deny-list as the only honest answer to the spec.
- Worked through three sub-commits to keep diffs reviewable: (a) JWT primitives + V2 migration, (b) security filter chain + custom 401/403 + slice test config, (c) endpoints + end-to-end integration test.
- Hit two `@WebMvcTest` + Spring Security gotchas during 5b:
  1. Slice tests don't load main-package `@Configuration` classes by default — `@Import(SecurityConfig.class)` was needed.
  2. Mocking the JWT filter as a `@MockitoBean` broke the chain because servlet filters are auto-registered when present as beans; the mock's no-op `doFilter` swallowed every request.
- Resolved by creating a test-only `TestSecurityConfig` that mirrors path rules without the JWT filter, plus `@WebMvcTest.excludeFilters` to suppress the main `SecurityConfig` from the slice scan.
- Login deliberately collapses all AuthenticationException subtypes to one generic 401 message ("Invalid username or password") to prevent username enumeration.
- The end-to-end integration test (`AuthIntegrationTest`) uses real Spring context, real BCrypt, real JWT filter — the only test that proves the whole auth story works as a system.

### Phase 6 — Project management
- Asked the assistant to implement the Project endpoints and to verify the soft-delete pattern end-to-end before moving on.
- Used the @PreAuthorize annotation for the first time, gating /projects/deleted and /projects/{id}/restore to ADMIN role. The 403 path reuses the AccessDeniedHandler installed in Phase 5 — no new error-handling code needed.
- Discussed two semantics questions for restore: (a) what if the project is already active, and (b) what if the id doesn't exist. Chose 409 for the first and 404 for the second; distinguishing them in the service required one extra findById call but produces much clearer error messages than collapsing both to 404.
- Pre-checked owner existence on create so the error message is "User with id N not found" instead of the generic 409 the FK violation would produce.
- Tests focus on what's new (soft delete, owner validation, ADMIN-only authorization) rather than re-testing patterns already covered in Phase 4.

### Phase 7 — Ticket management
- Asked the assistant to design the heaviest single phase as the convergence point for status rules, optimistic locking, soft delete, and admin authorization established in earlier phases.
- Hardest test to write: optimistic locking. Hibernate's first-level cache means loading the same entity twice in one session returns the same instance, so the standard pattern (load, mutate, save twice) doesn't fire. Used detach + merge to exercise the version conflict in a single-test transaction; production hits this naturally with two HTTP requests.
- The PDF 3.7 invariant ("manual priority change resets isOverdue") was tested end-to-end by driving the ticket to CRITICAL+isOverdue via the entity's autoEscalate, then verifying that a service-layer manual priority change clears the flag.
- DONE lock check is the *first* gate in update — even no-op patches against a DONE ticket return 409. Simple, predictable, matches the spec.
- Type is deliberately omitted from UpdateTicketRequest per the README's spec ("can update title, description, status, priority, assigneeId").
- Auto-assignment (Phase 14) is marked with a TODO at the exact hook in TicketService.create — when the time comes, it's a small change, not a structural rewrite.

### Phase 8 — Comments and mentions
- Asked the assistant to design the mention parser regex with explicit thought about which substrings should and should not match (start of string yes, mid-text yes, inside emails no, after apostrophes yes-but-trimmed). Chose (?:^|\W)@([A-Za-z0-9_.-]+) and tested all the edge cases.
- Mention recomputation relies on entity.replaceMentions plus the CascadeType.ALL + orphanRemoval combination on Comment.mentions established in Phase 2. The service is one line: `comment.replaceMentions(parser.resolveMentions(content))`; Hibernate's flush handles the diff.
- Discussed whether comments on DONE tickets should be locked (the PDF only locks ticket fields, not the conversation around them). Decided: allow. Post-mortems are a real workflow.
- The README's mention pagination shape {data, total, page} doesn't match Spring Data's default Page. Adapted at the service boundary so the rest of the code stays in Spring conventions.
- The defensive ticketId-vs-comment.ticketId check on update/delete prevents URL-crafted cross-ticket comment mutation — 404 because that comment doesn't logically exist under the wrong ticket.

### Phase 9 — Audit log
- The big design choice: REQUIRES_NEW propagation for audit writes, so a logging failure cannot roll back a real state change. The trade-off (audit row may exist for an event that subsequently rolls back) is acceptable because audit is observational. The opposite trade-off would let an audit blip undo a user's action — much worse.
- Replaced every TODO Phase 9 marker placed in Phases 4-8 in one branch. Per-service ordering kept the build green at each step.
- For TICKET updates, the audit captures both a general TICKET_UPDATE and (when applicable) granular TICKET_STATUS_CHANGE / TICKET_PRIORITY_CHANGE / TICKET_ASSIGN events with old/new metadata. Consumers care most about these specific transitions.
- The filter endpoint uses JpaSpecificationExecutor for AND-composed optional filters. Type-safe via Criteria, no JPQL strings.
- Introduced a generic PageResponse<T> matching the README's pagination shape; will eventually replace MentionsPageResponse from Phase 8.

### Phase 10 — Ticket dependencies
- Discussed cycle-detection depth. The PDF says "no circular dependencies" without qualifying — implemented full BFS over the dependency graph rather than just rejecting direct A→B→A cycles. Indirect cycles (A→B→C→A) would make DONE permanently unreachable, so they're equally bad.
- The DONE-blocker check uses a single direct-blocker repository query, not BFS. The rule is about whether the ticket's *direct* blockers are resolved, not whether the entire dependency graph is collapsed. This matches the natural reading of the spec.
- Five validation rules in the add path, ordered from cheapest to most expensive: self-block, ticket existence (both endpoints), same-project, duplicate, cycle. Fail fast on the cheap checks.
- Reused Phase 2's existsByTicketIdAndBlockerStatusNot repository method — it was added precisely for this DONE-transition check and finally got its first caller.

### Phase 11 — Attachments
- Two-layer size enforcement: Spring's multipart filter (10MB) for primary defense plus a service-level check for belt-and-braces and easier unit testing. The service check catches misconfiguration of the filter, and the test verifies the business rule independently of Spring's plumbing.
- MIME whitelist is duplicated between AttachmentService and V1__init.sql. Adding a new type requires changing both — annoying but deliberate. The service is user-facing; the DB constraint is the safety net.
- For listing attachments, used a JPQL constructor expression projection to avoid touching the BYTEA column. @Basic(LAZY) is only a hint without bytecode enhancement; the explicit projection query is guaranteed and visible in the SQL log.
- For download, chose byte[] over StreamingResponseBody. 10MB fits comfortably in memory and the controller stays simple. Production at larger scale would stream.
- Content-Disposition uses Spring's ContentDisposition builder for proper RFC 5987 encoding of non-ASCII filenames.

### Phase 12 — CSV
- Used commons-csv 1.10.0 (pulled in by the skeleton pom). CSVPrinter handles quoting on write; CSVParser handles unquoting on read. Embedded commas, doubled quotes, and embedded newlines round-trip correctly with no manual handling. The round-trip test exists specifically to prove this.
- Key transactional decision: the CSV service's importCsv method is NOT @Transactional. Per-row commits happen via TicketService.create's @Transactional boundary across the proxy. If importCsv itself were @Transactional, any failed row would mark the whole transaction rollback-only, defeating partial-failure semantics.
- Whole-file rejection for missing-required-column header errors: better UX than per-row failures with identical messages.
- Row numbers from CSVRecord.getRecordNumber() rather than raw file lines: a description with embedded newlines spans multiple file lines but is one row. Users think in rows.
- Catch Exception in the per-row loop is intentional and documented: this is the one place in the codebase where converting any failure into a row-level error is correct. The alternative (catching every specific type) would be fragile and incomplete.
- Chose to ignore the id column on import. Exported id is informational; imported tickets get fresh ids. Avoids cross-environment collision and matches the natural "import = create new" semantics.

### Phase 13 — Scheduled jobs
- Externalized cron schedules to application.yaml under issueflow.schedule.*. Test profile sets both to "-" (Spring's disable sentinel) so the scheduler doesn't fire jobs spontaneously during unrelated tests.
- Idempotency at two layers: (a) the query already excludes CRITICAL+overdue terminal-state tickets, so the job picks up zero work on subsequent runs; (b) the service method asserts state-change before emitting an audit row, so even hypothetical no-op pickups produce no audit noise. The combined effect: running the job N times on the same data is indistinguishable from running it once.
- Per-ticket transactions for escalation (same model as CSV import in Phase 12): a single user-editing-the-same-ticket version conflict doesn't roll back the other 49 escalations.
- AuditService.record is called with the long-form signature that lets us pass actor=SYSTEM and userId=null explicitly. The short-form would look up the security context, which is empty in a scheduled job — would default to SYSTEM, but the long-form makes the intent unmistakable.
- Tests invoke the service method (or job's run() method) directly rather than relying on the scheduler. Testing the cron expression itself would test Spring's scheduling, not our logic.
- RevokedTokenPurgeJob uses @Modifying + JPQL for bulk delete rather than Spring Data's derived per-row deletes. One SQL statement vs N.

### Phase 14 — Auto-assign by workload
- Reused Phase 2's findWorkloadByProject query directly. The LEFT JOIN was the key design choice — DEVELOPERs with zero open tickets in the project still appear as candidates, which is what makes auto-assignment work for new developers who haven't been assigned anything yet.
- Tie-breaker (oldest createdAt) is encoded in the SQL ORDER BY, not in Java. One round trip; deterministic at the database.
- AUTO_ASSIGN audit fires only when auto-assignment actually happened — not on every create. Explicit assignees produce only TICKET_CREATE.
- If no DEVELOPER users exist, assignee remains null and no AUTO_ASSIGN audit is emitted. Matches PDF 3.8.