# WebSocket-Chat-SpringBoot
WebSocket Chat is an Online Group chat application built using WebSocket API/Protocol in SpringBoot framework. This Application will let the users chat with others.

The documentation, Installation and the working of the application were explained in Project's Wiki. [Click Here](https://github.com/jyshnkr/WebSocket-Chat-SpringBoot/wiki) to navigate to the Wiki.

## Security

Users sign in with a username and password and receive a short-lived JWT. Every REST call and every WebSocket connection is authenticated with that token.

| Concern | How it is handled |
|---|---|
| Passwords | Stored only as BCrypt hashes (`DelegatingPasswordEncoder`); validated length 8–72 |
| Login | `AuthenticationManager` + `DaoAuthenticationProvider`; one generic error for unknown users and wrong passwords |
| Tokens | HS256 JWTs signed with a 256-bit key; `iss`, `exp` and signature verified on every request (Spring Security OAuth2 Resource Server) |
| REST APIs | Stateless `SecurityFilterChain`; `401` with `WWW-Authenticate: Bearer` when the token is missing or invalid |
| RBAC | `roles` claim mapped to `ROLE_*` authorities; admin-only endpoint guarded by `@PreAuthorize("hasRole('ADMIN')")` |
| WebSocket / STOMP | Browsers cannot send headers on the WebSocket handshake, so the token is sent on the STOMP `CONNECT` frame and verified by `JwtChannelInterceptor` |
| Message spoofing | The server stamps the authenticated username on every message; clients may only `SEND` to `/app/**` and only `SUBSCRIBE` to `/topic/public` |
| Error handling | Centralized `@RestControllerAdvice` returning RFC 9457 problem details; short STOMP `ERROR` frames (`Unauthorized` / `Forbidden`) |
| Client | Token kept in memory only; STOMP debug logging disabled so the token is never printed to the console |

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
