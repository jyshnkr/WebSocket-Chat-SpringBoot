# PR #1: scoped implementation

Started October 16; validation continued October 17, 2026. Base: `34444294288d055120fc8e58ecb87604afd97e3e`.

This record describes local verification before publication. The user subsequently authorized updating PR #1; current hosted results belong to the [PR checks](https://github.com/jyshnkr/WebSocket-Chat-SpringBoot/pull/1/checks).

**Outcome: the approved PR-fix scope is complete locally.** All 65 Java tests, 9 controlled browser tests and 5 real Chromium tests pass. Independent Standards and Spec reviews have no remaining in-scope findings. The broader framework upgrade and public-deployment work remain separate.

## What changed

| Area | Result | Regression evidence |
|---|---|---|
| Token/session lifetime | Requires JWT expiry; REST and sockets share an exclusive `exp + 60 seconds` deadline. Idle sockets close; queued messages and final transport sends are checked too. | Missing-expiry rejection; exact deadline, delayed timer, close/scheduling races; actual native WebSocket and forced HTTP SockJS tests. |
| Passwords | Rejects values over 72 UTF-8 bytes with a password-field `400`, before BCrypt. Keeps the previous minimum-length rule. | ASCII 72/73 bytes, `é` 36/37 repetitions, supplementary Unicode 18/19 repetitions; rejected accounts cannot sign in and the username remains available. |
| Presence | One synchronized coordinator owns registration, removal, snapshots and JOIN/LEAVE publication. Duplicate disconnects do nothing; only the final session emits LEAVE. JOIN requires its originating session to remain active. | Concurrent final disconnects; both reconnect orderings; delayed JOIN/connected events; expired sessions and replacement sessions. |
| Browser | Allows one authentication attempt, binds callbacks to their own socket, preserves useful errors and unsent drafts, and returns to sign-in after terminal failure. | Controlled callback races plus real Chromium registration, login, two-user chat and literal markup checks. |
| CI | Adds pull-request runs, disables persisted checkout credentials, uses the Maven wrapper, and declares Java 17/21 plus Node 24 browser jobs. | Workflow/config inspection; local Java and browser checks. Hosted results are reported separately on the PR. |

The expiry scheduler is marked `defaultCandidate=false`, so it does not replace Spring Boot's ordinary application/message executors. This follows [Spring Boot's task-execution guidance](https://docs.spring.io/spring-boot/3.5/reference/features/task-execution-and-scheduling.html).

## Original PR comments

All three comments were still relevant at the reviewed head. They are addressed in this implementation; review-thread resolution is left to the reviewers:

- [Checkout credentials](https://github.com/jyshnkr/WebSocket-Chat-SpringBoot/pull/1#discussion_r4296667171): `persist-credentials: false` on both checkout steps.
- [BCrypt byte limit](https://github.com/jyshnkr/WebSocket-Chat-SpringBoot/pull/1#discussion_r4296667197): reusable UTF-8 byte constraint before hashing.
- [Final-session race](https://github.com/jyshnkr/WebSocket-Chat-SpringBoot/pull/1#discussion_r4296667210): atomic state transition and ordered publication.

## Validation

| Check | Current evidence |
|---|---|
| Clean Java package/verify | **65 passed**, no failures/errors/skips; `./mvnw -B -ntp clean verify`, 23.572 seconds. Java 21.0.12.1, compiler target Java 17. |
| Focused lifecycle/transport checks | **20 passed**, including protocol disconnect before physical closure and native/HTTP expiry rejection. |
| Controlled browser tests | **9 passed**, no failures/skips; Node 24, test runner 52.476 ms (npm command 0.26 seconds). |
| Real Chromium | **5 passed**, no retries; 7.6 seconds against the final packaged JAR. Runner started and gracefully stopped its own server. |
| Independent Standards/Spec review | Both reviewers report no remaining concrete in-scope findings after the final lifecycle correction; source review, not a substitute for runtime tests. |
| Patch reconstruction | Applied to a fresh local archive of the pinned PR commit; all 34 changed-file hashes matched. Reconstructed application: **65 tests passed**, clean verification 23.353 seconds. Final documentation-only edits are rechecked by patch application and file hashes. |

Tests use a controllable clock and scheduler for exact/racy boundaries; real transports also test actual scheduled idle closure. The original 17 tests remain included. Tests cannot recall bytes already sent before a deadline, establish production capacity, or substitute for a deployment security review.

Initial red tests reproduced missing-expiry acceptance, a socket remaining open beyond expiry, Unicode passwords reaching BCrypt and failing, reconnect overtaking LEAVE publication, and missing direct JOIN publication at the new coordinator boundary. These were fixed before the corresponding green runs.

### Issues found during independent review

| Cause | Correction and evidence |
|---|---|
| SockJS can cache a message before expiry and flush it during a later HTTP poll, bypassing the logical WebSocket send check. | Guard the HTTP response write as well. A real cached-message polling regression failed before the fix and now passes. |
| The filter initially parsed a trailing slash differently from Spring's SockJS handler. | Use Spring's tokenization semantics; both `xhr` and `xhr/` real-transport regressions pass. |
| Erasing session state at the start of closure made a fresh HTTP guard permissive. A protocol disconnect event can also precede physical closure. | Keep terminal guard state while the transport remains open; separate logical disconnect from physical cleanup. Regressions cover a new guard during close, failed close, and the actual disconnect listener. |
| A queued JOIN could run after LEAVE, or accidentally use a replacement session's presence. | Recheck the originating session under the publication lock; tests also preserve a valid JOIN when its connected event is delayed. |
| Applying registration rules to login blocked existing short seeded credentials. | Apply those rules only when creating an account; retain server authentication for sign-in. A controlled regression first failed, then passed. |

The two errors in the intermediate 64-test run were the HTTP client's synchronous `410 Gone` rejection after expiry, not successful unauthorized sends. The transport tests now explicitly assert that rejection while retaining closure and healthy-peer/message-isolation assertions; production security was not relaxed to make tests pass.

### Standards

The independent standards/correctness reviewer rechecked the final source and found no remaining material findings. Earlier cache/path, closure, delayed-JOIN and login findings are resolved. This is a bounded review, not proof that no vulnerability exists.

### Spec

The independent spec reviewer checked the approved `PLAN.md` and found no remaining concrete requirement gaps, missing required tests, or unintended scope. Final source review and parent-run execution are separate evidence.

Summary: **0 remaining findings on each review axis**; no unresolved worst issue within either axis.

### Source-based design decisions

- Authentication still uses Spring's JWT decoder/validators; the explicit required-expiry and exclusive-deadline policy is shared with socket lifetime. [Spring Security JWT validation](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-validation).
- Presence is one atomic state transition plus ordered publication, not independent map operations. Duplicate disconnects are expected by the framework. [Spring lifecycle events](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/application-context-events.html), [message ordering](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/ordered-messages.html).
- Protocol-disconnected is not identical to transport-closed: Framework 6.2.19's send-limit failure path clears protocol state before calling transport close, and protocol cleanup publishes `SessionDisconnectEvent`. This source ordering motivated the final regression and lifecycle separation. [SubProtocolWebSocketHandler source](https://github.com/spring-projects/spring-framework/blob/v6.2.19/spring-websocket/src/main/java/org/springframework/web/socket/messaging/SubProtocolWebSocketHandler.java#L361-L375), [StompSubProtocolHandler source](https://github.com/spring-projects/spring-framework/blob/v6.2.19/spring-websocket/src/main/java/org/springframework/web/socket/messaging/StompSubProtocolHandler.java#L631-L647).

Use version-matched guidance for this patch; current-major migration guidance belongs to the separately deferred upgrade. No extra service/repository layers or new features were added merely for appearance.

## Still deliberately deferred

- Spring Boot upgrade: 3.5.16 remains unchanged. Its OSS support has ended; see the [official announcement](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/).
- Persistent accounts, restart-token policy, production configuration validation and abuse/rate limits.
- CDN/CSP modernization, accessibility, broad action pinning and authorization-framework redesign.

This is a focused correctness/security patch, **not approval for public deployment**. The original comprehensive review remains a historical record; this document describes only the approved subset.

Local Java 17 runtime execution was **not verified**. Hosted CI was not part of the local evidence recorded here; consult the PR checks for its results after publication. Existing provider-deprecation and Mockito dynamic-agent warnings remain outside this scope. The test suite includes an intentional failed-close warning and rejected-message logs; they are not test failures. No load/capacity claim or exhaustive dependency/secret-history audit is made.

## Delivery

Implementation and verification ran in an isolated worktree based on the reviewed PR head. The original `main` checkout was not modified. The later request to update the PR authorizes publishing this implementation to its existing branch, not merging or deploying it.

A pre-publication patch and receipts are retained locally in the original checkout at `docs/implementation/2026-10-16-pr-1/`. These local-only evidence files are not included in the PR:

- `fixes.patch`: all scoped source, test, CI and documentation changes relative to the pinned PR head.
- `manifest.json`: base commit, patch SHA-256 and every changed file's SHA-256.
- `evidence/`: Java summaries derived from Surefire XML, controlled-browser output, real-browser results/report and patch-reconstruction checks. Raw Maven environment properties are not copied.

The PR includes this completion record, `PLAN.md`, source tests and CI configuration. Generated reports and local paths are not required to build or run it.

The patch targets **PR head `3444429`, not `main`**. To apply it later, use a clean checkout of that exact commit, then run from its repository root:

```sh
git apply --check /absolute/path/to/fixes.patch
git apply /absolute/path/to/fixes.patch
cd WebSocket-Chat-Application
./mvnw -B -ntp clean verify
```

Reconstruction used a local Git archive and the existing Maven cache, not a network clone or empty dependency cache. Browser verification used a fresh dependency installation and an already installed Chromium binary; its source/configuration and JAR were hash-compared with the implementation worktree. The original comprehensive review is at `docs/reviews/2026-10-16-pr-1/README.md` in the original repository.
