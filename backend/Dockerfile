FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/folio-query-1.0.0.jar app.jar
ENV FOLIO_BIND=0.0.0.0 PORT=8765 JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70
EXPOSE 8765
USER 10001
ENTRYPOINT ["java", "-jar", "app.jar"]
