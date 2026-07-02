FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY runtime-mock-sidecar/target/runtime-mock-sidecar.jar /app/runtime-mock-sidecar.jar

EXPOSE 18480

ENTRYPOINT ["java", "-jar", "/app/runtime-mock-sidecar.jar"]
