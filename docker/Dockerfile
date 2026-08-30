# ---------- Stage 1: build ----------
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Install sbt
RUN apt-get update && \
    apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

# Copy build definition first (better layer caching)
COPY build.sbt ./
COPY project ./project

# Download dependencies
RUN sbt update

# Copy sources and build the fat jar
COPY src ./src
RUN sbt assembly

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser

# Wildcard works because only one assembly jar is produced
COPY --from=builder /build/target/scala-2.13/*-assembly-*.jar /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
