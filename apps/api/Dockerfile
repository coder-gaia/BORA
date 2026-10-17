FROM eclipse-temurin:25.0.4.1_1-jdk-noble AS build
RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY apps/api/pom.xml apps/api/pom.xml
COPY apps/simulator/pom.xml apps/simulator/pom.xml
COPY apps/api/src apps/api/src

RUN chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress -pl apps/api -am package -DskipTests

FROM eclipse-temurin:25.0.4.1_1-jre-noble AS runtime
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 bora \
    && useradd --uid 10001 --gid bora --no-create-home --shell /usr/sbin/nologin bora

WORKDIR /app
COPY --from=build --chown=bora:bora /workspace/apps/api/target/bora-api.jar /app/bora-api.jar
USER bora:bora
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
  CMD curl --fail --silent http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/bora-api.jar"]
