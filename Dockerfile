FROM eclipse-temurin:25-jdk AS build

ENV HOME=/app
RUN mkdir -p $HOME
WORKDIR $HOME
COPY . $HOME

RUN --mount=type=cache,target=/root/.m2 \
    sh -c './mvnw clean package -DskipTests'

FROM eclipse-temurin:25-jre-alpine

COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar", "--spring.profiles.active=prod"]
