# MoneyManage - backend

Personal finance dashboard: bank and broker movements imported from the banks' export files, balances over
time, a portfolio graph, real estate, and a Monte Carlo projection of the portfolio.
This is the REST API (Spring Boot); the web app is in
[MoneyManageFrontend](https://github.com/RR2000/MoneyManageFrontend).

Demo: https://money.raoulrondinella.dev - register with any username; tick "sample data" to start with a
made-up year of movements, or leave it empty and upload your own files. Every account sees only its own data.

## What it does

- **Bank movements**: upload of Revolut and Degiro CSV, Unicredit CSV, Intesa Sanpaolo XLSX
  (`POST /api/banks/transactions/{bank}/upload`, multipart field `multipartFile`). Given today's balance of an
  account, the balance of every past day is rebuilt (`computeCumulativeAmount`) and kept per day.
- **Broker**: Degiro transactions CSV (`POST /api/brokers/transactions/upload`), worth over time from the
  prices of each ISIN. Prices come from Yahoo Finance (bundled, adapted copy of the
  [yahoofinance-api](https://github.com/sstrickx/yahoofinance-api) client) and are cached in the database.
- **Real estate**: CRUD with total estimated value.
- **Monte Carlo**: projection of the portfolio from its historical monthly returns (percentiles P5-P95).
- **Accounts**: registration and login with a JWT. Each user has a PostgreSQL schema of their own
  (Hibernate multi-tenancy, schema per user): data never mix.

API docs: `/swagger-ui.html` on the backend.

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
| `MONEY_JWT_SECRET` | random at every start | HMAC key (>= 32 characters) of the login tokens |
| `MONEY_CORS_ORIGINS` | `http://localhost:4200` | only for a web app on another origin |

## Image

The CI publishes `ghcr.io/rr2000/moneymanage-backend:main` (and `:sha-<commit>`) at every push on `main`.
The web app image (nginx) serves the app and forwards `/api` to the service `api` on port 8080.

## License

GPL-3.0, see [LICENSE](LICENSE).
