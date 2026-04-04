# Build Stage for Java
FROM maven:3.9.6-eclipse-temurin-21 AS build-java
# Copy only the cas-injector folder into the build image
COPY cas-injector /app
WORKDIR /app
RUN mvn clean package -DskipTests

# Run Stage
FROM eclipse-temurin:21-jre-jammy

# Install Python and necessary build dependencies
RUN apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    python3-venv \
    libpq-dev \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Set up a working directory
WORKDIR /app

# Copy the Java JAR
COPY --from=build-java /app/target/*.jar app.jar

# Copy the scraper project into the image
# This assumes the build context is the root directory
COPY cas-scraper /app/cas-scraper

# Install scraper dependencies
# Explicitly use pip3
RUN pip3 install --no-cache-dir -r /app/cas-scraper/requirements.txt

# Expose Java port
EXPOSE 8080

# Run the Java application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
