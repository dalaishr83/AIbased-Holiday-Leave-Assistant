FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build
COPY backend/pom.xml .
COPY backend/src ./src
RUN apt-get update && apt-get install -y maven && mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar

# Create data directories
RUN mkdir -p data/uploads data/working reports

EXPOSE 8080
ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx512m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]
