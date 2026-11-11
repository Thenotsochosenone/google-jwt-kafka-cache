# Google JWT + Akka Streams + Kafka – Token Cache Demo
The main reason to build this is test out an old feature I couldnt complete due to running out of time but wanted to finish it at some point when i got time to touch upon this topic properly.

A containerized Scala demo that shows how to(semi working atm):

- Cache Google OAuth2 access tokens (and ID tokens) with **Caffeine**
- Drive **Apache Kafka** produce/consume with **Akka Streams / Alpakka Kafka**
- Expose **Prometheus metrics**, health/readiness, and a **Grafana** dashboard
- Run end-to-end **without a GCP project** via mock credentials

 
> Kafka uses plain (no SASL) so you can observe the pipeline locally.  
> Real Google Managed Kafka `OAUTHBEARER` setup is documented as comments and in the Production notes section — you must wire the official `GcpLoginCallbackHandler` (or equivalent) for production.

---

Making Changes To Structure

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
- Always refresh a short time before the token expires (`refreshSkewSeconds`).
- Expose `/metrics` only on an internal network or protect it.
- Pin dependencies and sbt (`project/build.properties`) for reproducible builds.
- Do not commit secrets; `.gitignore` excludes `secrets/` and `*.json` (except the Grafana dashboard).

---

## License

Apache 2.0 
