# Money Manage - backend

Personal finance dashboard: bank and broker movements imported from the banks' export files, balances over
time, a portfolio graph, real estate, and a Monte Carlo projection of the portfolio.
This is the REST API (Spring Boot); the web app is in
[money-manage-app](https://github.com/RR2000/money-manage-app).

Demo: https://money.raoulrondinella.dev - register with any username; tick "sample data" to start with a
made-up year of movements, or leave it empty and upload your own files (or "Accedi con GitHub"). Every account
sees only its own data.

## What it does

- **Bank movements**: upload of Revolut and Degiro CSV, Unicredit CSV, Intesa Sanpaolo XLSX
  (`POST /api/banks/transactions/{bank}/upload`, multipart field `multipartFile`). Given today's balance of an
  account, the balance of every past day is rebuilt and kept per day
  (`POST /api/banks/transactions/accounts/{account}/balance`, body `{"amountToday": 1234.56}`).
- **Broker**: Degiro transactions CSV (`POST /api/brokers/transactions/upload`), worth over time from the
  prices of each ISIN. Prices come from Yahoo Finance (bundled, adapted copy of the
  [yahoofinance-api](https://github.com/sstrickx/yahoofinance-api) client) and are cached in the database.
- **Real estate**: CRUD with total estimated value.
- **Monte Carlo**: projection of the portfolio from its historical monthly returns (percentiles P5-P95).
- **Accounts**: registration and login with a JWT, optional "Accedi con GitHub" (an account is found again only by a
  verified GitHub email), optional "forgot password" by email (`POST /api/auth/password/forgot`, then
  `/api/auth/password/reset` with the token of the link). The account's email is set from a confirmation link
  (`POST /api/account/email`, then `/api/auth/email/confirm`), also at registration. Each user has a PostgreSQL schema of their own
  (Hibernate multi-tenancy, schema per user): data never mix.

API docs: `/swagger-ui.html` on the backend, when `MONEY_API_DOCS=true` (on in the local run).

## Stack

Java 21, Spring Boot 4, Spring Security, JPA/Hibernate, Flyway, PostgreSQL. Tests run on an embedded
PostgreSQL (real binaries from Maven, no Docker needed).

## Run

```bash
mvn verify                      # tests
mvn spring-boot:test-run        # backend on :8080 with an embedded, empty PostgreSQL
```

With your own PostgreSQL: `DB_URL=jdbc:postgresql://host:5432/money DB_USER=money DB_PASSWORD=... mvn spring-boot:run`.
The database user must be able to create schemas in its database (one schema per registered user).

| variable | default | |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/money` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `money` / empty | credentials |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | empty (off) | GitHub OAuth App for "Accedi con GitHub", callback `https://<site>/api/login/oauth2/code/github` |
| `MONEY_JWT_SECRET` | random at every start | HMAC key (>= 32 random characters, otherwise no start) of the login tokens |
| `SMTP_HOST` / `SMTP_PORT` | empty (off) / `587` | SMTP relay for the emails (STARTTLS required) |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | empty (off) | SMTP credentials |
| `MAIL_FROM` | empty (off) | sender address, e.g. `noreply@example.org` (shown as "Money Manage") |
| `MONEY_WEB_URL` | empty (off) | public URL of the web app (e.g. `https://money.example.org`): the links in the emails point there |
| `MONEY_CORS_ORIGINS` | empty (closed) | only for a web app on another origin |
| `MONEY_API_DOCS` | `false` | `true`: OpenAPI description and Swagger UI on `/v3/api-docs` and `/swagger-ui.html` |

## Security

- Every user has a schema `u<id>` of their own, chosen by the server from the logged-in account (never from the
  request); the tests check that two users never see each other's data, endpoint by endpoint.
- Passwords with BCrypt; login tokens signed HMAC-SHA256, valid 24 hours. "Accedi con GitHub" links only a
  verified email.
- Login: after 5 wrong passwords a username is refused for 15 minutes (for every username, existing or not);
  at most 30 registrations every 15 minutes for the whole site. The reverse proxy also limits per address.
- Errors: unexpected ones answer "Unexpected error" and are logged with the exception class and the endpoint,
  never with their message (it can hold amounts or descriptions); SQL errors are not logged.
- Password reset by email: on only when all the SMTP variables, `MAIL_FROM` and `MONEY_WEB_URL` are set (otherwise
  the endpoints answer 404 and the web app hides it). The request always answers 202, whether an account has the
  email or not, and the mail leaves in the background (same timing). The link holds 32 random bytes, the database
  keeps only their SHA-256; it works once, for 30 minutes, and a new password voids the other links. At most 10
  requests per hour per client address (then 429), 3 mails per hour per email and 50 per hour for the site (then
  nothing is sent, same answer). An email becomes the account's only once confirmed from a link (24 hours), so
  nobody can attach an address they do not own. Tokens travel in the body and, in the links, in the
  URL fragment: never in an access log. Emails and tokens are never logged.
- Uploads up to 10 MB; amounts never travel in a URL (they would end up in the access logs).

## Image

The CI publishes `ghcr.io/rr2000/money-manage-backend:main` (and `:sha-<commit>`) at every push on `main`.
The web app image (nginx) serves the app and forwards `/api` to the service `api` on port 8080.

## License

GPL-3.0, see [LICENSE](LICENSE).
