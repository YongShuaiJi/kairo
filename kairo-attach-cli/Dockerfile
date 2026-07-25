FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY kairo-attach-cli/target/kairo-attach.jar /app/kairo-attach.jar

EXPOSE 18480

ENTRYPOINT ["java", "-jar", "/app/kairo-attach.jar", "exec"]
