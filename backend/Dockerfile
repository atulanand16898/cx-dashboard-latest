# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Cache dependencies separately from source so rebuilds are fast
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM debian:bookworm-slim

WORKDIR /app

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        chromium \
        fonts-dejavu-core \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /tmp/project-files \
    && chmod 1777 /tmp/project-files \
    && groupadd --system spring \
    && useradd --system --gid spring spring
ENV BROWSER_BIN=/usr/bin/chromium
USER spring

COPY --from=builder /app/target/cxalloy-integration-1.0.0.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
