# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    apk add --no-cache maven && \
    mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S batemcp && adduser -S batemcp -G batemcp
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R batemcp:batemcp /app
USER batemcp

EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
