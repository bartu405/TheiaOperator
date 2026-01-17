# Stage 1: Build
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build

# Copy Gradle wrapper and config first (layer caching)
COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Download dependencies (cached unless build files change)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Copy source and build
COPY src src
RUN ./gradlew shadowJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system operator && useradd --system --gid operator --no-create-home operator

# Use explicit filename since we know it's operator.jar
COPY --from=builder /build/build/libs/operator.jar app.jar

RUN chown operator:operator /app/app.jar

USER operator

ENTRYPOINT ["java", "-jar", "/app/app.jar"]