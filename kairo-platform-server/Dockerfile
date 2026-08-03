FROM eclipse-temurin:21-jdk

WORKDIR /app
ARG BUILD_VERSION=1.7.0-SNAPSHOT
ARG JAR_FILE=kairo-platform-server/target/kairo-platform-server-${BUILD_VERSION}.jar
ARG BOOTSTRAP_API_JAR=kairo-bootstrap-api/target/kairo-bootstrap-api-${BUILD_VERSION}.jar
COPY ${JAR_FILE} /app/kairo-platform-server.jar
COPY kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar /app/kairo-agent-bootstrap.jar
COPY kairo-agent-core-modern/target/kairo-agent-core-modern.jar /app/kairo-agent-core-modern.jar
COPY ${BOOTSTRAP_API_JAR} /app/kairo-bootstrap-api.jar

EXPOSE 18280
ENTRYPOINT ["java", "-jar", "/app/kairo-platform-server.jar"]
