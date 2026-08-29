FROM eclipse-temurin:25-jdk AS build

RUN     apt update
RUN     apt install -y git ripgrep

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml lombok.config ./

RUN --mount=type=cache,id=s/94c09288-3228-4cb1-b165-aba516b8ebd2-/root/cache/m2,target=/root/.m2 \
    --mount=type=cache,id=s/94c09288-3228-4cb1-b165-aba516b8ebd2-/root/cache/root,target=/root/.cache \
    ./mvnw -B -ntp exec:java -e \
      -Dexec.classpathScope=test \
      -Dexec.mainClass=com.microsoft.playwright.CLI \
      -Dexec.args="install --with-deps chromium"

COPY src/ src/

RUN --mount=type=cache,id=s/94c09288-3228-4cb1-b165-aba516b8ebd2-/root/cache/m2,target=/root/.m2 \
    --mount=type=cache,id=s/94c09288-3228-4cb1-b165-aba516b8ebd2-/root/cache/root,target=/root/.cache \
    ./mvnw -B -ntp package

FROM eclipse-temurin:25-jre AS runtime

RUN     apt update && apt install -y git git-lfs ripgrep && apt-get clean && rm -rf /var/cache/apt/lists

RUN     userdel ubuntu || true
RUN     groupdel ubuntu || true

COPY --from=build /workspace/target/jupiter-0.0.1-SNAPSHOT.jar /opt/jupiter.jar
ADD entrypoint.sh /entrypoint.sh

EXPOSE 7272

CMD ["bash", "/entrypoint.sh"]
