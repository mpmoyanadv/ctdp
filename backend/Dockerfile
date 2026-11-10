FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S capi && adduser -S capi -G capi \
    && mkdir -p /app/storage/attachments \
    && chown -R capi:capi /app
USER capi
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

