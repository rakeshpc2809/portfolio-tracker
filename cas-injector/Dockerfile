# Build Stage for Java
FROM maven:3.9.6-eclipse-temurin-21 AS build-java
COPY cas-injector /app
WORKDIR /app
RUN mvn clean package -DskipTests

# Run Stage
FROM eclipse-temurin:21-jre-jammy

# Standard system utilities
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the Java JAR
COPY --from=build-java /app/target/*.jar app.jar

# Expose Java port
EXPOSE 8080

# Run the Java application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
