# Multi-stage Dockerfile for SnapVault
# Stage 1: Build JAR using Maven
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal JRE runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S snapvault && adduser -S snapvault -G snapvault
USER snapvault

COPY --from=builder /build/target/snapvault-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
