# Stage 1: Build
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

# Copy only configuration files first to cache dependencies
COPY build.gradle settings.gradle gradlew ./
COPY gradle ./gradle

# Grant execution permission to gradlew
RUN chmod +x ./gradlew

# Download dependencies (without building)
# This step will be cached if build.gradle/settings.gradle don't change
RUN ./gradlew dependencies --no-daemon

# Now copy the source code
COPY src ./src

# Build the application, skipping tests
RUN ./gradlew bootJar -x test --no-daemon

# Stage 2: Run
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 80
ENTRYPOINT ["java", "-jar", "-Dserver.port=80", "app.jar"]
