FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# The simulator is part of the build but not of the image; Gradle only needs its build file
COPY simulator/build.gradle.kts simulator/
RUN ./gradlew :dependencies --no-daemon > /dev/null
COPY src src
RUN ./gradlew :bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
