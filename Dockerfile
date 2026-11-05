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
COPY --chmod=0755 entrypoint.sh git-wrapper.sh ./

RUN --mount=type=cache,id=maven-cache,target=/root/.m2 \
    --mount=type=cache,id=root-cache,target=/root/.cache \
    --mount=type=secret,id=bluecave_token,required=false \
    set +e; \
    if [ -s /run/secrets/bluecave_token ]; then \
      BLUECAVE_TOKEN="$(cat /run/secrets/bluecave_token)" \
      BLUECAVE_EXTRA_OPTS="$BLUECAVE_EXTRA_OPTS" \
      ./mvnw -B -ntp package bluecave:report; \
    else \
      ./mvnw -B -ntp package; \
    fi; \
    status=$?; \
    mkdir -p /workspace/report-output || exit 1; \
    printf '%s\n' "$status" > /workspace/report-output/build-exit-status || exit 1; \
    if [ -d /workspace/target/surefire-reports ]; then \
      cp -R /workspace/target/surefire-reports/. /workspace/report-output/surefire-reports/ || exit 1; \
    fi; \
    exit 0

FROM build AS verified-build
RUN test "$(cat /workspace/report-output/build-exit-status)" = 0

FROM scratch AS report-export
COPY --from=build /workspace/report-output/ /

FROM eclipse-temurin:25-jre AS runtime

RUN     apt update && apt install -y git git-lfs ripgrep && \
        dpkg-divert --local --rename --add --divert /usr/bin/git.real /usr/bin/git && \
        apt-get clean && rm -rf /var/cache/apt/lists

COPY --chmod=0755 git-wrapper.sh /usr/bin/git

RUN     userdel ubuntu || true
RUN     groupdel ubuntu || true

COPY --from=verified-build /workspace/target/jupiter.jar /opt/jupiter.jar
ADD entrypoint.sh /entrypoint.sh

# Keep only names so build-time environment values are not stored in the image.
RUN bash -c 'set -euo pipefail; env -0 | while IFS= read -r -d "" entry; do printf "%s\0" "${entry%%=*}"; done > /etc/jupiter-image-env-names'

EXPOSE 7272

CMD ["bash", "/entrypoint.sh"]
