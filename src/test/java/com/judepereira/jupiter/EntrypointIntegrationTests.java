package com.judepereira.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class EntrypointIntegrationTests {
    @TempDir
    Path tempDir;

    @Test
    void entrypointCapturesAndSanitizesBootstrapEnvironment() throws Exception {
        String username = "jupiter-test";
        Path imageNames = tempDir.resolve("image env names");
        Path result = tempDir.resolve("result file");
        Path invocations = tempDir.resolve("su invocations");
        Path jar = tempDir.resolve("fixture jar");
        Path rootInit = tempDir.resolve("root init.sh");
        Path userInit = tempDir.resolve("user init.sh");
        Path commands = tempDir.resolve("commands");
        Files.createDirectories(commands);

        Files.write(imageNames, "IMAGE_DEFINED\0".getBytes(StandardCharsets.US_ASCII));
        Files.writeString(jar, "fixture", StandardCharsets.US_ASCII);
        writeExecutable(rootInit,
                "set -euo pipefail\n[[ ${JUPITER_ENCRYPTION_KEY+x} && ${JUPITER_EMPTY+x} && -z $JUPITER_EMPTY ]]\nprintf root-ok > '"
                        + result + "'\n");
        writeExecutable(userInit, """
                set -euo pipefail
                [[ $HOME == /home/jupiter-test && $USER == jupiter-test && $LOGNAME == jupiter-test ]]
                [[ ${JUPITER_ENCRYPTION_KEY+x} && ${JUPITER_TEST_SENTINEL+x} ]]
                printf user-ok >> 'RESULT'
                """.replace("RESULT", result.toString()));
        Path fakeJava = commands.resolve("java");
        writeExecutable(fakeJava, fakeJavaScript(result));
        writeExecutable(commands.resolve("bash"), "exec /bin/bash \"$@\"\n");
        writeExecutable(commands.resolve("groupadd"),
                "set -euo pipefail\n[[ $1 == -g && $2 == 1000 && $3 == " + username + " ]]\n");
        writeExecutable(commands.resolve("useradd"),
                "set -euo pipefail\n[[ $1 == -u && $2 == 1000 && $3 == -g && $4 == 1000 && $5 == -m && $6 == -s && $7 == /bin/bash && $8 == "
                        + username + " ]]\n");
        writeExecutable(commands.resolve("chmod"), "set -euo pipefail\n[[ $1 == 644 && $2 == '" + jar + "' ]]\n");
        writeExecutable(commands.resolve("chown"),
                "set -euo pipefail\n[[ $1 == -R && $2 == " + username + ":" + username + " && $3 == * ]]\n");
        Path fakeEnv = commands.resolve("env");
        writeExecutable(fakeEnv, "/usr/bin/env \"$@\"\nprintf 'JUPITER_UNICODE=caf\\xC3\\xA9\\0'\n");
        Path fakeSu = commands.resolve("su");
        writeExecutable(fakeSu, fakeSuScript(invocations));

        ProcessBuilder builder = new ProcessBuilder("bash", Path.of("entrypoint.sh").toAbsolutePath().toString());
        Map<String, String> environment = builder.environment();
        environment.put("LC_ALL", "en_US.utf8");
        environment.put("LANG", "en_US.utf8");
        environment.put("USERNAME", username);
        environment.put("WITH_UID", "1000");
        environment.put("WITH_GID", "1000");
        environment.put("PORT", "7399");
        environment.put("INIT_SCRIPT", rootInit.toString());
        environment.put("INIT_USER_SCRIPT", userInit.toString());
        environment.put("IMAGE_ENV_NAMES_FILE", imageNames.toString());
        environment.put("JAR_PATH", jar.toString());
        environment.put("JAVA_PATH", fakeJava.toString());
        environment.put("GROUPADD_COMMAND", commands.resolve("groupadd").toString());
        environment.put("USERADD_COMMAND", commands.resolve("useradd").toString());
        environment.put("CHMOD_COMMAND", commands.resolve("chmod").toString());
        environment.put("CHOWN_COMMAND", commands.resolve("chown").toString());
        environment.put("ENV_COMMAND", fakeEnv.toString());
        environment.put("SU_COMMAND", fakeSu.toString());
        environment.put("JUPITER_ENCRYPTION_KEY", "fixture-encryption-key");
        environment.put("JUPITER_TEST_SENTINEL", "sentinel-value");
        environment.put("JUPITER_EMPTY", "");
        environment.put("JUPITER_RUNTIME_VALUE", "line one\nline=two");
        environment.put("JUPITER_UNICODE",
                new String(new byte[]{0x63, 0x61, 0x66, (byte) 0xc3, (byte) 0xa9}, StandardCharsets.UTF_8));
        environment.put("IMAGE_DEFINED", "from-image");
        environment.put("LAUNCHER_VARIABLE", "launcher-value");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(Files.readString(result)).isEqualTo("root-okuser-okfinal-ok");
        assertThat(Files.readString(invocations)).contains("-w ", " -c bash", "-w IMAGE_DEFINED", "|for");
        // Fixture source scripts are intentionally excluded: they contain the values
        // they assert against.
        assertThat(Files.readString(result)).doesNotContain("fixture-encryption-key", "sentinel-value");
        assertThat(Files.readString(invocations)).doesNotContain("fixture-encryption-key", "sentinel-value");
    }

    private static String fakeJavaScript(Path result) {
        return """
                set -euo pipefail
                export LC_ALL=C.utf8
                trap 'printf "fake-java failed at %s: %s\\n" "$LINENO" "$BASH_COMMAND" >&2' ERR
                args=("$@")
                [[ ${args[*]} == *"-Dserver.port=7399"* && ${args[*]} == *"-jar"* ]]
                [[ ${JUPITER_ENCRYPTION_KEY+x} != x && ${JUPITER_TEST_SENTINEL+x} != x && ${LAUNCHER_VARIABLE+x} != x ]]
                [[ $IMAGE_DEFINED == from-image ]]
                grep -zq 'IMAGE_DEFINED=from-image' /proc/self/environ
                ! env -0 | grep -zq 'JUPITER_ENCRYPTION_KEY='
                ! env -0 | grep -zq 'JUPITER_TEST_SENTINEL='
                ! grep -zq 'JUPITER_ENCRYPTION_KEY=' /proc/self/environ
                ! grep -zq 'JUPITER_TEST_SENTINEL=' /proc/self/environ
                [[ $* != *fixture-encryption-key* && $* != *sentinel-value* ]]
                header=; IFS= read -r -d '' header
                [[ $header == JUPITER_BOOTSTRAP_V1 ]]
                found_key=0; found_sentinel=0; found_empty=0; found_complex=0; found_unicode=0; found_launcher=0; found_ordinary=0
                launcher_name=; unicode_hex=
                while IFS= read -r -d '' name && IFS= read -r -d '' value; do
                  case $name in
                    USERNAME|PORT|WITH_UID|WITH_GID|INIT_SCRIPT|INIT_USER_SCRIPT|GROUPADD_COMMAND|USERADD_COMMAND|CHMOD_COMMAND|CHOWN_COMMAND|SU_COMMAND|ENV_COMMAND|IMAGE_ENV_NAMES_FILE|JAR_PATH|JAVA_PATH)
                      found_launcher=1; launcher_name=$name;;
                    JUPITER_ENCRYPTION_KEY) [[ -n $value ]] && found_key=1;;
                    JUPITER_TEST_SENTINEL) [[ $value == sentinel-value ]] && found_sentinel=1;;
                    JUPITER_EMPTY) [[ -z $value ]] && found_empty=1;;
                    JUPITER_RUNTIME_VALUE) [[ $value == $'line one\nline=two' ]] && found_complex=1;;
                    LAUNCHER_VARIABLE) [[ $value == launcher-value ]] && found_ordinary=1;;
                    JUPITER_UNICODE) unicode_hex=$(printf '%s' "$value" | od -An -tx1 -v | tr -d ' \n'); [[ $unicode_hex == 636166c3a9 ]] && found_unicode=1;;
                  esac
                done
                (( found_key && found_sentinel && found_empty && found_complex && found_unicode && found_ordinary && !found_launcher )) || {
                  printf 'flags key=%s sentinel=%s empty=%s complex=%s unicode=%s ordinary=%s launcher=%s name=%s unicode_hex=%s\n' "$found_key" "$found_sentinel" "$found_empty" "$found_complex" "$found_unicode" "$found_ordinary" "$found_launcher" "$launcher_name" "$unicode_hex" >&2
                  exit 1
                }
                printf final-ok >> 'RESULT'
                """
                .replace("RESULT", result.toString());
    }

    private static String fakeSuScript(Path invocations) {
        return "set -euo pipefail\n"
                + "trap 'printf \"fake-su failed at %s: %s\\n\" \"$LINENO\" \"$BASH_COMMAND\" >&2' ERR\n"
                + "whitelist=\nif [[ ${1-} == -w ]]; then whitelist=$2; shift 2; fi\n"
                + "[[ $1 == - ]]; shift; user=$1; shift\n"
                + "[[ $1 == -c ]]; shift; command=$1; shift; command_args=(\"$@\")\n"
                + "printf '%s %s %s -c %s' -w \"$whitelist\" - \"${command%% *}\" >> '" + invocations + "'\n"
                + "[[ $command == 'bash '* ]] && printf ' %s' \"${command#bash }\" >> '" + invocations + "'\n"
                + "printf '|%s\\n' \"${command%% *}\" >> '" + invocations + "'\n"
                + "IFS=, read -ra names <<< \"$whitelist\"\n"
                + "env_args=(\"HOME=/home/$user\" \"USER=$user\" \"LOGNAME=$user\" \"PATH=$PATH\" \"LC_ALL=C.utf8\")\n"

                + "for name in \"${names[@]}\"; do [[ -n $name ]] && env_args+=(\"$name=${!name-}\"); done\n"
                + "env -i \"${env_args[@]}\" /bin/bash -c \"$command\" \"${command_args[@]}\"\n";
    }

    private static void writeExecutable(Path path, String content) throws Exception {
        Files.writeString(path, "#!/bin/bash\n" + content, StandardCharsets.UTF_8);
        path.toFile().setExecutable(true);
    }
}
