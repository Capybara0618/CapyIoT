FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/ .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -N dependency:copy \
    -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:2.33.1 \
    -DoutputDirectory=/agent -Dmdep.stripVersion=true

FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
WORKDIR /app
RUN addgroup -S capyiot && adduser -S capyiot -G capyiot
COPY --from=build --chown=capyiot:capyiot /workspace/${SERVICE}/target/${SERVICE}-0.1.0-SNAPSHOT.jar app.jar
COPY --from=build /agent/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY infra/java/start.sh /app/start.sh
USER capyiot
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8" \
    OTEL_SERVICE_NAME=${SERVICE} OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none \
    OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
ENTRYPOINT ["/bin/sh", "/app/start.sh"]
