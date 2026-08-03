FROM eclipse-temurin:21-jre

WORKDIR /app

ARG BUILD_VERSION=1.7.0-SNAPSHOT
ARG DEMO_JAR=kairo-demo/target/kairo-demo-${BUILD_VERSION}-exec.jar
ARG BOOTSTRAP_API_JAR=kairo-bootstrap-api/target/kairo-bootstrap-api-${BUILD_VERSION}.jar
COPY ${DEMO_JAR} /app/kairo-demo.jar
COPY kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar /app/kairo-agent-bootstrap.jar
COPY kairo-agent-core-modern/target/kairo-agent-core-modern.jar /app/kairo-agent-core-modern.jar
COPY ${BOOTSTRAP_API_JAR} /app/kairo-bootstrap-api.jar

EXPOSE 18080 18090

ENTRYPOINT ["java", "-jar", "/app/kairo-demo.jar", "--server.port=18090"]
