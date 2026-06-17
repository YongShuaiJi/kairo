FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/runtime-mock-platform-server-0.1.0-SNAPSHOT.jar
COPY ${JAR_FILE} /app/runtime-mock-platform-server.jar

EXPOSE 18280
ENTRYPOINT ["java", "-jar", "/app/runtime-mock-platform-server.jar"]
