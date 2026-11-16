FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY simulator/ simulator/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f simulator/pom.xml package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S capyiot && adduser -S capyiot -G capyiot
WORKDIR /app
COPY --from=build /workspace/simulator/target/device-simulator-0.1.0-SNAPSHOT.jar app.jar
USER capyiot
ENTRYPOINT ["java", "-jar", "app.jar"]
