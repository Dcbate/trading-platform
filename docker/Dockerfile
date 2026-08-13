# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    apk add --no-cache maven && \
    mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S trading && adduser -S trading -G trading
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R trading:trading /app
USER trading

# Chronicle Queue add-opens (JDK-internal APIs for off-heap memory access) alongside the
# production GC tuning from the spec (G1, 200ms pause target).
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
    --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.io=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
