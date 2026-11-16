FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/ .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
WORKDIR /app
RUN addgroup -S capyiot && adduser -S capyiot -G capyiot
COPY --from=build --chown=capyiot:capyiot /workspace/${SERVICE}/target/${SERVICE}-0.1.0-SNAPSHOT.jar app.jar
USER capyiot
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -Dfile.encoding=UTF-8"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
