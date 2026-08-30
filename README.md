# Google JWT + Akka Streams + Kafka – Token Cache Demo

A **completely runnable**, containerized Scala project that demonstrates:

- Caching Google OAuth2 / JWT access (or ID) tokens with **Caffeine**
- Using the cached token with **Apache Kafka** via **Akka Streams / Alpakka Kafka**
- Full **observability** (Prometheus metrics + Grafana dashboard)
- Health / readiness endpoints
- Unit tests
- One-command `docker compose up`

Perfect for cloning to GitHub and running anywhere.

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
git clone <your-repo-url>
cd google-jwt-kafka-cache

# Start everything (Kafka + app + Prometheus + Grafana)
docker compose up --build -d

# Wait ~30-40 s for Kafka to become healthy
docker compose logs -f app
```

### Access points

| Service          | URL                              | Credentials      |
|------------------|----------------------------------|------------------|
| App health       | http://localhost:8080/healthz    | —                |
| App readiness    | http://localhost:8080/readyz     | —                |
| Prometheus metrics | http://localhost:8080/metrics  | —                |
| Token cache stats| http://localhost:8080/token-stats| —                |
| Force token refresh | `POST http://localhost:8080/force-refresh` | —          |
| Prometheus UI    | http://localhost:9090            | —                |
| Grafana          | http://localhost:3000            | admin / admin    |

In Grafana open the pre-provisioned dashboard **“Google JWT Kafka Cache”**.

---

## Local development (without Docker)

### Prerequisites

- JDK 21+
- sbt 1.9+
- A running Kafka (or use the `kafka` service from docker-compose)

```bash
# Start only Kafka
docker compose up kafka -d

# Run tests
sbt test

# Run the application
sbt run
```

The app will use **mock Google credentials** when Application Default Credentials are not present, so you can exercise the whole pipeline locally without a GCP project.

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

3. (Optional) Request an **ID token** instead of an access token by setting:

```hocon
app.google.audience = "https://your-service.example.com"
```

or the env var equivalent.

---

## Project layout

```
google-jwt-kafka-cache/
├── build.sbt
├── docker-compose.yml
├── docker/Dockerfile
├── prometheus/prometheus.yml
├── grafana/
│   ├── provisioning/...
│   └── dashboards/jwt-cache-dashboard.json
├── src/main/scala/com/example/jwtcache/
│   ├── GoogleJwtTokenCache.scala   # core cache + metrics
│   ├── KafkaProducerWithJwt.scala
│   ├── KafkaConsumerWithJwt.scala
│   ├── MetricsServer.scala
│   └── Main.scala
├── src/test/scala/...
└── README.md
```

---

## Key design decisions

| Decision                        | Why |
|---------------------------------|-----|
| Caffeine cache                  | Extremely fast, supports TTL + stats out of the box |
| Mock credentials fallback       | Makes the demo runnable without GCP |
| Prometheus + Grafana            | Industry standard, dashboard is pre-loaded |
| Fat-jar via sbt-assembly        | Simple, single-file deployment |
| Multi-stage Docker build        | Small runtime image (~200 MB) |
| KRaft Kafka (no ZooKeeper)      | Modern, fewer moving parts |

---

## Tests

```bash
sbt test
```

Covers:

- Token cache hit / miss / invalidate
- Cache statistics
- Metrics HTTP endpoints

---

## Production notes

- Prefer the official Google `GcpLoginCallbackHandler` when talking to **Google Cloud Managed Service for Apache Kafka**.
- The custom cache shown here is useful when you need the token for other Google APIs as well, or when you want full control over refresh timing and metrics.
- Always refresh a short time **before** the token expires (`refreshSkewSeconds`).
- Expose `/metrics` only on an internal network or protect it.

---

## License

Apache 2.0 – free to use, modify and publish on GitHub.
