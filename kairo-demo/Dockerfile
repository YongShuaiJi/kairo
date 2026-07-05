FROM eclipse-temurin:21-jre

WORKDIR /app

COPY kairo-demo/target/kairo-demo-0.1.0-SNAPSHOT-exec.jar /app/kairo-demo.jar
COPY kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar /app/kairo-agent-bootstrap.jar
COPY kairo-agent-core-modern/target/kairo-agent-core-modern.jar /app/kairo-agent-core-modern.jar
COPY kairo-bootstrap-api/target/kairo-bootstrap-api-0.1.0-SNAPSHOT.jar /app/kairo-bootstrap-api.jar

EXPOSE 18080 18090

ENTRYPOINT ["java", "-jar", "/app/kairo-demo.jar", "--server.port=18090"]
