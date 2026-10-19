FROM eclipse-temurin:25.0.4.1_1-jdk-noble AS build
RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY apps/api/pom.xml apps/api/pom.xml
COPY apps/simulator/pom.xml apps/simulator/pom.xml
COPY apps/simulator/src apps/simulator/src
RUN chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress -pl apps/simulator -am package -DskipTests

FROM eclipse-temurin:25.0.4.1_1-jre-noble AS runtime
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 bora \
    && useradd --uid 10001 --gid bora --no-create-home --shell /usr/sbin/nologin bora
WORKDIR /app
COPY --from=build --chown=bora:bora /workspace/apps/simulator/target/bora-simulator.jar /app/bora-simulator.jar
USER bora:bora
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=4 \
  CMD curl --fail --silent http://localhost:8090/health || exit 1
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:MaxDirectMemorySize=64m", "-jar", "/app/bora-simulator.jar"]
