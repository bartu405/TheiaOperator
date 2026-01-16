# =========================
# Build stage
# =========================
FROM gradle:8.5-jdk17 AS build

WORKDIR /workspace

# Copy Gradle wrapper & build config first (for layer caching)
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Download dependencies (cached layer)
RUN ./gradlew build -x test --no-daemon || true

# Copy source code
COPY src ./src

# Build fat jar
RUN ./gradlew build -x test --no-daemon



# =========================
# Runtime stage
# =========================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the built JAR
COPY --from=build /workspace/build/libs/*.jar app.jar

# Create non-root user (best practice for operators)
RUN addgroup -S operator && adduser -S operator -G operator

USER operator

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
