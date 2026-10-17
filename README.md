# WebSocket-Chat-SpringBoot
WebSocket Chat is an Online Group chat application built using WebSocket API/Protocol in SpringBoot framework. This Application will let the users chat with others.

The documentation, Installation and the working of the application were explained in Project's Wiki. [Click Here](https://github.com/jyshnkr/WebSocket-Chat-SpringBoot/wiki) to navigate to the Wiki.

## Security

Users sign in with a username and password and receive a short-lived JWT. Protected REST APIs and STOMP connections require that token; registration, login, static files and the initial SockJS handshake are public.

| Concern | How it is handled |
|---|---|
| Passwords | Stored only as BCrypt hashes (`DelegatingPasswordEncoder`); at least 8 Java UTF-16 code units and at most 72 UTF-8 bytes. Oversized passwords return a `400` password-field error before hashing; they are never truncated. |
| Login | `AuthenticationManager` + `DaoAuthenticationProvider`; one generic error for unknown users and wrong passwords |
| Tokens | HS256 JWTs signed with a key of at least 256 bits; required `exp`, issuer, signature and `nbf` (when present) are validated. The existing 60-second clock tolerance is retained; a token is invalid at or after `exp + 60 seconds`. |
| REST APIs | Stateless `SecurityFilterChain`; `401` with `WWW-Authenticate: Bearer` when the token is missing or invalid |
| RBAC | `roles` claim mapped to `ROLE_*` authorities; admin-only endpoint guarded by `@PreAuthorize("hasRole('ADMIN')")` |
| WebSocket / STOMP | The token is sent on STOMP `CONNECT`. A cancelable task closes idle sessions at the token deadline; inbound/outbound channel checks and transport checks also reject expired work. An HTTP response-write guard covers cached SockJS messages waiting for a later poll. |
| Message spoofing | The server stamps the authenticated username on every message; clients may only `SEND` to `/app/**` and only `SUBSCRIBE` to `/topic/public` |
| Error handling | MVC validation/application errors use RFC 9457 problem details. Security-filter failures are separate (a bearer `401` need not contain a problem body). STOMP authentication/authorization errors use short `Unauthorized` / `Forbidden` messages. |
| Client | Token kept in memory only; STOMP debug logging disabled so the token is never printed to the console |
| Presence | Session updates and JOIN/LEAVE publication are serialized in this single-process demo. Only the last session disconnect emits LEAVE; reconnect publication cannot overtake it. |

An expired session closes with policy-violation code `1008` and reason `Session expired` where the transport exposes that close reason; an expired SockJS HTTP poll may return `410 Gone`. The browser returns to sign-in after terminal failure; it does not refresh tokens or reconnect automatically. Unsent message text is preserved. Bytes already handed to the underlying transport before the deadline cannot be recalled.

The 72-byte registration limit is not a 72-character limit: 36 copies of `é` occupy 72 UTF-8 bytes, while 37 occupy 74. The existing registration minimum uses Java's string-length rule, not user-perceived characters. Sign-in does not reapply registration rules, so existing seeded credentials still reach server authentication.

### API

| Method | Path | Access |
|---|---|---|
| `POST` | `/api/auth/register` | Public. Body: `{"username", "password"}` |
| `POST` | `/api/auth/login` | Public. Returns `{"accessToken", "tokenType", "expiresIn"}` |
| `GET` | `/api/users/me` | Any authenticated user |
| `GET` | `/api/admin/active-users` | `ADMIN` role only |
| WebSocket | `/ws` (SockJS) | STOMP `CONNECT` with `Authorization: Bearer <token>` |

## Running locally

Requires Java 17+.

```bash
cd WebSocket-Chat-Application
export JWT_SECRET=$(openssl rand -base64 32)   # optional; a random key is generated if unset
export ADMIN_USERNAME=admin                     # optional admin account
export ADMIN_PASSWORD='change-me-please'
./mvnw spring-boot:run
```

Open http://localhost:6369, create an account, and start chatting.

Run the test suite (REST security, token validation, RBAC and STOMP security):

```bash
./mvnw verify
```

From the repository root, run the controlled browser-lifecycle tests and real Chromium tests:

```bash
npm ci
npm run test:unit
npx playwright install chromium
(cd WebSocket-Chat-Application && ./mvnw -B -ntp -DskipTests package)
npm run test:e2e
```

Use Node 24 for the browser tooling. The browser runner starts and stops its own packaged application; see `playwright.config.js` for the exact startup command. CI runs Java verification on Java 17 and 21 and browser checks on Java 17 / Node 24.

## Demo boundaries

- Accounts and presence live in memory and disappear on restart; the simple broker is single-instance.
- With an ephemeral signing key, old tokens stop working on restart. With a fixed key, old signed tokens can remain valid even though the account store was reset. Persistent identities/revocation are a separate design task.
- Spring Boot 3.5.16 is intentionally unchanged in this scoped PR fix. Its open-source support has ended; upgrade before treating this as a maintained public demo. See the [Spring release announcement](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/).
- Public deployment still needs abuse controls, production configuration validation, dependency/CDN hardening and a persistence decision. Passing these tests is not a production-readiness claim.

## Design references

- [Spring token authentication for STOMP](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/authentication-token-based.html)
- [Spring WebSocket lifecycle events and duplicate disconnect handling](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/application-context-events.html)
- [Preserving message order](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/ordered-messages.html)
- [Transport decorators for closing sessions](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/web/socket/config/annotation/WebSocketTransportRegistration.html)
- [JWT timestamp validation and clock tolerance](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-validation)
