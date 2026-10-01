# spring-roomescape-payment

## Requirements
- Java 21
- Gradle (wrapper included)

## Local Development
- Run the app: `./gradlew bootRun`
- Run all tests: `./gradlew test`

## Pool Contention Demo (Slow External API)
This demo reproduces the impact of a slow external payment API holding DB connections inside a transaction.

- Run: `./gradlew test --tests roomescape.reservation.ReservationPoolContentionTest`
- What to look for in logs:
  - Requests with latency >= `EXTERNAL_DELAY_MS` (1.5s)
  - Failures caused by connection pool contention (Hikari pool size 2, connection timeout 500ms)

The demo uses the `pool-demo` Spring profile with H2 and a mocked Toss payment client that sleeps.
