# syntax=docker/dockerfile:1.7

# ---- Build stage ------------------------------------------------------
# Resolve dependencies first so they cache across source-only edits.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
    && mv target/yaya-agentic-*.jar /workspace/app.jar

# ---- Runtime stage ----------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Curl is for the compose healthcheck — JRE images don't ship it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --no-create-home --uid 10001 yaya
USER yaya

COPY --from=build --chown=yaya:yaya /workspace/app.jar ./app.jar

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=docker

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
