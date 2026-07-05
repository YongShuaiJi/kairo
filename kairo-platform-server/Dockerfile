FROM eclipse-temurin:21-jdk

WORKDIR /app
ARG JAR_FILE=kairo-platform-server/target/kairo-platform-server-0.1.0-SNAPSHOT.jar
COPY ${JAR_FILE} /app/kairo-platform-server.jar
COPY kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar /app/kairo-agent-bootstrap.jar
COPY kairo-agent-core-modern/target/kairo-agent-core-modern.jar /app/kairo-agent-core-modern.jar
COPY kairo-bootstrap-api/target/kairo-bootstrap-api-0.1.0-SNAPSHOT.jar /app/kairo-bootstrap-api.jar

EXPOSE 18280
ENTRYPOINT ["java", "-jar", "/app/kairo-platform-server.jar"]
