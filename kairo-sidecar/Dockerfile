FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY kairo-sidecar/target/kairo-sidecar.jar /app/kairo-sidecar.jar

EXPOSE 18480

ENTRYPOINT ["java", "-jar", "/app/kairo-sidecar.jar"]
