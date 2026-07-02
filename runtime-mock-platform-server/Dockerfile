FROM eclipse-temurin:21-jdk

WORKDIR /app
ARG JAR_FILE=runtime-mock-platform-server/target/runtime-mock-platform-server-0.1.0-SNAPSHOT.jar
COPY ${JAR_FILE} /app/runtime-mock-platform-server.jar
COPY runtime-mock-agent-bootstrap/target/runtime-mock-agent-bootstrap.jar /app/runtime-mock-agent-bootstrap.jar
COPY runtime-mock-agent-core-modern/target/runtime-mock-agent-core-modern.jar /app/runtime-mock-agent-core-modern.jar
COPY runtime-mock-bootstrap-api/target/runtime-mock-bootstrap-api-0.1.0-SNAPSHOT.jar /app/runtime-mock-bootstrap-api.jar

EXPOSE 18280
ENTRYPOINT ["java", "-jar", "/app/runtime-mock-platform-server.jar"]
