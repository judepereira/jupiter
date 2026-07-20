FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml lombok.config ./
COPY src/ src/

RUN ./mvnw -DskipTests package

FROM eclipse-temurin:25-jre AS runtime

ARG USERNAME=app

RUN useradd --create-home --home-dir /home/${USERNAME} --shell /bin/bash ${USERNAME} \
    && mkdir -p /workspace \
    && chown -R ${USERNAME}:${USERNAME} /home/${USERNAME} /workspace

WORKDIR /workspace

COPY --from=build /workspace/target/jupiter2-0.0.1-SNAPSHOT.jar /workspace/app.jar

EXPOSE 7272

USER ${USERNAME}

ENTRYPOINT ["java", "-jar", "/workspace/app.jar"]
