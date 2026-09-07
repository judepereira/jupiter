FROM eclipse-temurin:25-jdk AS build

ARG BLUECAVE_EXTRA_OPTS

RUN     apt update
RUN     apt install -y git ripgrep curl

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml lombok.config ./

RUN --mount=type=cache,id=maven-cache,target=/root/.m2 \
    --mount=type=cache,id=root-cache,target=/root/.cache \
    ./mvnw -B -ntp exec:java -e \
      -Dexec.classpathScope=test \
      -Dexec.mainClass=com.microsoft.playwright.CLI \
      -Dexec.args="install --with-deps chromium"

COPY src/ src/

RUN --mount=type=cache,id=maven-cache,target=/root/.m2 \
    --mount=type=cache,id=root-cache,target=/root/.cache \
    --mount=type=secret,id=bluecave_token,required=false \
    if [ -s /run/secrets/bluecave_token ]; then \
      BLUECAVE_TOKEN="$(cat /run/secrets/bluecave_token)" \
      BLUECAVE_EXTRA_OPTS="$BLUECAVE_EXTRA_OPTS" \
      ./mvnw -B -ntp package bluecave:report; \
    else \
      ./mvnw -B -ntp package; \
    fi

FROM eclipse-temurin:25-jre AS runtime

RUN     apt update && apt install -y git git-lfs ripgrep && apt-get clean && rm -rf /var/cache/apt/lists

RUN     userdel ubuntu || true
RUN     groupdel ubuntu || true

COPY --from=build /workspace/target/jupiter-0.0.1-SNAPSHOT.jar /opt/jupiter.jar
ADD entrypoint.sh /entrypoint.sh

# Keep only names so build-time environment values are not stored in the image.
RUN bash -c 'set -euo pipefail; env -0 | while IFS= read -r -d "" entry; do printf "%s\0" "${entry%%=*}"; done > /etc/jupiter-image-env-names'

EXPOSE 7272

CMD ["bash", "/entrypoint.sh"]
