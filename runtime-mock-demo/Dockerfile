FROM eclipse-temurin:21-jre

WORKDIR /app

COPY runtime-mock-demo/target/runtime-mock-demo-0.1.0-SNAPSHOT-exec.jar /app/runtime-mock-demo.jar
COPY runtime-mock-agent-bootstrap/target/runtime-mock-agent-bootstrap.jar /app/runtime-mock-agent-bootstrap.jar
COPY runtime-mock-agent-core-modern/target/runtime-mock-agent-core-modern.jar /app/runtime-mock-agent-core-modern.jar
COPY runtime-mock-bootstrap-api/target/runtime-mock-bootstrap-api-0.1.0-SNAPSHOT.jar /app/runtime-mock-bootstrap-api.jar

EXPOSE 18080 18090

ENTRYPOINT ["java", "-jar", "/app/runtime-mock-demo.jar", "--server.port=18090"]
