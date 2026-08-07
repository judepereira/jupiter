FROM eclipse-temurin:25-jdk AS build

RUN     apt update
RUN     apt install -y git

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml lombok.config ./

RUN --mount=type=cache,id=maven-cache,target=/root/.m2 \
    ./mvnw -B -ntp exec:java -e \
      -Dexec.classpathScope=test \
      -Dexec.mainClass=com.microsoft.playwright.CLI \
      -Dexec.args="install --with-deps chromium"

COPY src/ src/

RUN --mount=type=cache,id=maven-cache,target=/root/.m2 \
    ./mvnw -B -ntp package

FROM eclipse-temurin:25-jre AS runtime

ARG USERNAME=jupiter

RUN useradd --create-home --home-dir /home/${USERNAME} --shell /bin/bash ${USERNAME} \
    && mkdir -p /workspace \
    && chown -R ${USERNAME}:${USERNAME} /home/${USERNAME} /workspace

WORKDIR /workspace

COPY --from=build /workspace/target/jupiter-0.0.1-SNAPSHOT.jar /workspace/app.jar

EXPOSE 7272

USER ${USERNAME}

ENTRYPOINT ["java", "-jar", "/workspace/app.jar"]
