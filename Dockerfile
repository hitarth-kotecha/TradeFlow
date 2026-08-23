# Phase 0: package the pre-built fat jar onto a Java 25 runtime.
# Build the jar first (`mvn clean package`), then `docker compose up --build`.
# (A multi-stage build that compiles inside Docker is a later refinement.)
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY gateway/target/gateway-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

# --enable-preview is required at runtime because we compiled with a preview API (StructuredTaskScope, JEP 505).
ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
