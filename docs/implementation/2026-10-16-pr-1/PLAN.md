# Scoped PR fixes

Base: PR #1, `34444294288d055120fc8e58ecb87604afd97e3e`. Implementation and verification used an isolated detached worktree. After completion, the user authorized updating the existing PR with these changes. Merge and deployment remain outside the scope.

## Scope

1. Enforce a shared JWT/session deadline (`exp + 60 seconds`), including idle sockets, queued work, native WebSocket and SockJS fallback. Require `exp`; preserve signature, issuer and not-before validation. Use an injectable clock and cancelable expiry tasks. Closed sessions cannot be restored by late events.
2. Reject passwords over 72 UTF-8 bytes before BCrypt, preserving the existing minimum length and structured validation response.
3. Serialize presence transitions and publication. Only the final disconnect emits LEAVE; a reconnect cannot overtake that publication. Preserve explicit JOIN behavior.
4. Make browser authentication and socket ownership single-attempt and race-safe. Preserve useful errors, unsent text, memory-only tokens and safe text rendering.
5. Verify pull requests in CI, disable persisted checkout credentials, use the Maven wrapper on Java 17/21, and add pinned Chromium regression tests on Node 24.

## Agreed test boundaries

- Register/login HTTP API: ASCII, accented and supplementary Unicode password limits; rejected registrations cannot sign in.
- JWT decoder and transport/channel lifecycle: exact deadline, missing expiry, delayed timer, queued work, scheduling failure, close races and shutdown.
- Actual native WebSocket and forced SockJS HTTP fallback: expired sender/subscriber is rejected, idle subscriber closes, unexpired peer still works.
- Presence coordinator publication: two tabs, simultaneous final disconnect, duplicate events and both reconnect orderings.
- Browser UI and controlled callbacks: repeated authentication, stale callbacks, disconnected sends, terminal error preservation and sign-in recovery; real two-user chat and safe rendering.

Use red/green slices, deterministic clock/scheduler/barrier tests where possible, bounded waits for real transports, then full Java verification, packaged startup, browser checks and independent Standards/Spec reviews.

## Explicitly deferred

Spring Boot migration, persistence, restart-token policy, rate limits, CSP/CDN modernization, accessibility, broad action pinning and authorization-framework redesign. Boot 3.5.16 remains temporarily; this patch is not a public-deployment approval.

## Progress

- [x] Verify PR head and create isolated worktree.
- [x] Implement server lifetime and presence fixes.
- [x] Implement password and browser fixes with sub-agents.
- [x] Integrate CI and document changed behavior.
- [x] Run regression gates and independent review.
- [x] Save a reviewable patch and factual completion record.

## Remaining effort (October 17)

1. **Complete:** reproduced the protocol-disconnect-before-transport-close race, then separated logical disconnect from transport cleanup. The focused 20-test lifecycle/transport run passes. Transport tests now explicitly assert the intended HTTP 410 rejection and retain message-isolation checks.
2. **Complete:** clean Java verification passed all 65 tests; controlled browser tests passed 9/9 and Chromium passed 5/5. Both independent source-review axes closed with no remaining concrete in-scope findings.
3. **Complete:** saved the patch, verified application to the pinned PR base, and matched all 34 changed-file hashes. Reconstructed Java verification passed 65/65. Results and limits are recorded in the completion report and evidence; final documentation-only changes receive a fresh patch/hash check.

No new features or deferred upgrades are included. Each correction must map to the approved scope and a reproduced failure or framework source evidence.
