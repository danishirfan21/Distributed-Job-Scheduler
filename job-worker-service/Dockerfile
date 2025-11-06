# Multi-stage build for Job Worker Service
FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /app

# Copy parent POM
COPY pom.xml .

# Copy common module
COPY job-common/pom.xml job-common/
COPY job-common/src job-common/src

# Copy worker service
COPY job-worker-service/pom.xml job-worker-service/
COPY job-worker-service/src job-worker-service/src

# Build the application
RUN mvn clean package -pl job-worker-service -am -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the built JAR
COPY --from=build /app/job-worker-service/target/*.jar app.jar

# Expose port
EXPOSE 8082

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${SERVER_PORT:-8082}/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
