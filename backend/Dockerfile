FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

COPY backend/pom.xml ./pom.xml
RUN mvn dependency:go-offline -q

COPY backend/src ./src
RUN mvn clean package -DskipTests -q

FROM debian:bookworm-slim

WORKDIR /app

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        python3 \
        python3-pip \
        chromium \
        fonts-dejavu-core \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /tmp/project-files /tmp/xer-processing \
    && chmod 1777 /tmp/project-files /tmp/xer-processing \
    && groupadd --system spring \
    && useradd --system --gid spring spring

COPY xer_processing/python/processor_requirements.txt /tmp/processor_requirements.txt
RUN pip3 install --break-system-packages --no-cache-dir -r /tmp/processor_requirements.txt \
    && rm /tmp/processor_requirements.txt

ENV BROWSER_BIN=/usr/bin/chromium

COPY xer_processing /app/xer_processing
COPY --from=builder /app/target/cxalloy-integration-1.0.0.jar /app/app.jar

RUN chown -R spring:spring /app /tmp/project-files /tmp/xer-processing

USER spring

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
