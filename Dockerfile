# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
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
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S operator && adduser -S operator -G operator

# Use explicit filename since we know it's operator.jar
COPY --from=builder /build/build/libs/operator.jar app.jar

RUN chown operator:operator /app/app.jar

USER operator

ENV USER_JVM_ARGS=""

ENTRYPOINT [
  "sh",
  "-c",
  "exec java -XX:MaxRAMPercentage=75.0 $USER_JVM_ARGS -jar /app/app.jar \"$@\"",
  "java"
]
