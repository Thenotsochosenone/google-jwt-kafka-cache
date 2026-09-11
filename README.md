# Google JWT + Akka Streams + Kafka – Token Cache Demo

A **runnable, containerized** Scala demo that shows how to:

- Cache Google OAuth2 access tokens (and ID tokens) with **Caffeine**
- Drive **Apache Kafka** produce/consume with **Akka Streams / Alpakka Kafka**
- Expose **Prometheus metrics**, health/readiness, and a **Grafana** dashboard
- Run end-to-end **without a GCP project** via mock credentials

> **This is a demo, not production auth wiring.**  
> Kafka uses plain (no SASL) so you can observe the pipeline locally.  
> Real Google Managed Kafka `OAUTHBEARER` setup is documented as comments and in the Production notes section — you must wire the official `GcpLoginCallbackHandler` (or equivalent) for production.

---

## Architecture

```
┌─────────────────┐     token      ┌──────────────────────┐
│  Google Auth    │◄───────────────│ GoogleJwtTokenCache  │
│  (ADC / SA)     │                │  (Caffeine + metrics)│
└─────────────────┘                └──────────┬───────────┘
                                              │
                    ┌─────────────────────────┼─────────────────────────┐
                    │                         │                         │
                    ▼                         ▼                         ▼
           ┌────────────────┐      ┌──────────────────┐      ┌────────────────┐
           │ Kafka Producer │      │  Metrics Server  │      │ Kafka Consumer │
           │ (Akka Streams) │      │ /metrics /health │      │ (Akka Streams) │
           └───────┬────────┘      └────────┬─────────┘      └───────┬────────┘
                   │                        │                       │
                   ▼                        ▼                       ▼
              ┌─────────┐            ┌────────────┐            ┌─────────┐
              │  Kafka  │            │ Prometheus │            │  Kafka  │
              └─────────┘            └─────┬──────┘            └─────────┘
                                           │
                                           ▼
                                    ┌────────────┐
                                    │  Grafana   │
                                    │  :3000     │
                                    └────────────┘
```

---

## Quick Start (Docker – recommended)

```bash
git clone https://github.com/Thenotsochosenone/google-jwt-kafka-cache.git
cd google-jwt-kafka-cache

# Start Kafka + app + Prometheus + Grafana
docker compose up --build -d

# Wait ~30–40 s for Kafka to become healthy, then follow logs
docker compose logs -f app
```

### Access points

| Service              | URL                                          | Credentials   |
|----------------------|----------------------------------------------|---------------|
| App health           | http://localhost:8080/healthz                | —             |
| App readiness        | http://localhost:8080/readyz                 | —             |
| Prometheus metrics   | http://localhost:8080/metrics                | —             |
| Token cache stats    | http://localhost:8080/token-stats            | —             |
| Force token refresh  | `POST http://localhost:8080/force-refresh`   | —             |
| Prometheus UI        | http://localhost:9090                        | —             |
| Grafana              | http://localhost:3000                        | admin / admin |

In Grafana open the pre-provisioned dashboard **“Google JWT Kafka Cache”**.

---

## Local development (without full Docker stack)

### Prerequisites

- JDK 21+
- sbt 1.10.x (pinned in `project/build.properties`)
- A running Kafka (or: `docker compose up kafka -d`)

```bash
# Start only Kafka
docker compose up kafka -d

# Run tests
sbt test

# Run the application
sbt run
```

When Application Default Credentials are missing, the app uses **mock Google credentials** so the full pipeline runs without a GCP project. The mock supports both access-token and ID-token (audience) paths.

---

## Using real Google credentials

1. Create a service-account key (or rely on Workload Identity / ADC).
2. Mount it into the container:

```yaml
# docker-compose.yml (already commented)
volumes:
  - ./secrets/sa.json:/secrets/sa.json:ro
environment:
  - GOOGLE_APPLICATION_CREDENTIALS=/secrets/sa.json
```

3. (Optional) Request an **ID token** instead of an access token:

```hocon
app.google.audience = "https://your-service.example.com"
```

---

## What this demo does vs production Kafka auth

| Concern | This demo | Production (Google Managed Kafka) |
|---------|-----------|-----------------------------------|
| Kafka security | Plaintext (easy local observability) | `SASL_SSL` + `OAUTHBEARER` |
| Token usage | Cached token; preview embedded in message payload | Token used only for SASL login via callback handler |
| Recommended handler | — | `com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler` |

See comments in `KafkaProducerWithJwt.scala` for the exact producer properties to enable real OAUTHBEARER.

---

## Project layout

```
google-jwt-kafka-cache/
├── build.sbt
├── project/
│   ├── build.properties          # pins sbt 1.10.2
│   └── plugins.sbt
├── docker-compose.yml
├── docker/Dockerfile
├── prometheus/prometheus.yml
├── grafana/
│   ├── provisioning/...
│   └── dashboards/jwt-cache-dashboard.json
├── src/main/scala/com/example/jwtcache/
│   ├── GoogleJwtTokenCache.scala   # core cache + metrics + mock
│   ├── KafkaProducerWithJwt.scala
│   ├── KafkaConsumerWithJwt.scala
│   ├── MetricsServer.scala
│   └── Main.scala
├── src/test/scala/...
└── README.md
```

---

## Key design decisions

| Decision | Why |
|----------|-----|
| Caffeine cache | Fast, TTL + stats; we also respect real token expiry with skew |
| Correct hit/miss metrics | `getIfPresent` + loader only counts true misses/errors |
| Mock credentials fallback | Demo runs without GCP; supports access + ID token paths |
| Prometheus + Grafana | Pre-loaded dashboard for token refresh / cache / JVM |
| Fat-jar via sbt-assembly | Simple single-file image in multi-stage Dockerfile |
| KRaft Kafka (no ZooKeeper) | Fewer moving parts for the demo stack |

---

## Tests

```bash
sbt test
```

Covers:

- Token cache hit / miss / invalidate
- Mock ID-token (audience) path without ClassCastException
- Cache statistics (`usingMock`, sizes, etc.)
- Real `MetricsServer` routes: `/healthz`, `/readyz`, `/metrics`, `/token-stats`, `POST /force-refresh`

---

## Production notes

- Prefer the official Google `GcpLoginCallbackHandler` for **Google Cloud Managed Service for Apache Kafka**.
- The custom cache is useful when you need the same token for other Google APIs or full control over refresh timing and metrics.
- Always refresh a short time **before** the token expires (`refreshSkewSeconds`).
- Expose `/metrics` only on an internal network or protect it.
- Pin dependencies and sbt (`project/build.properties`) for reproducible builds.
- Do not commit secrets; `.gitignore` excludes `secrets/` and `*.json` (except the Grafana dashboard).

---

## License

Apache 2.0 – free to use, modify, and publish on GitHub.
