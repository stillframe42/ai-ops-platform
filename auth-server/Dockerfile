# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
ENV SPRING_PROFILES_ACTIVE=docker
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
