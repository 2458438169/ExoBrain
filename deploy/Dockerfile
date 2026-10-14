FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN mkdir -p /app/data/documents && chown -R 10001:10001 /app
COPY --chown=10001:10001 app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
