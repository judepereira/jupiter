package com.judepereira.jupiter;

import com.judepereira.jupiter.security.EncryptionKey;
import com.judepereira.jupiter.security.ProcessEnvironmentSanitizer;
import com.judepereira.jupiter.security.TextEncryptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class JupiterStartupIntegrationTests {
    @TempDir
    Path tempDir;

    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String WRONG_KEY = "//////////////////////////////////////////8=";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(45);

    @Test
    @EnabledOnOs(OS.LINUX)
    void startsWithKeySuppliedOnStdinAndDoesNotExposeItToChildEnvironment() throws Exception {
        try (RunningJupiter app = start(KEY, tempDir.resolve("startup"))) {
            assertThat(app.responseBody()).contains("UP");
            assertThat(app.process.isAlive()).isTrue();
            assertThat(Files.readString(app.environmentMarker)).isEqualTo("absent");
        }
    }

    @Test
    void closedOrEmptyStdinFailsClearly() throws Exception {
        StartupResult result = runAndCapture("", tempDir.resolve("empty"));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("stdin encryption key is missing");
    }

    @Test
    void malformedAndWrongSizedStdinFailClearly() throws Exception {
        for (String input : new String[]{"not-base64", "AQ=="}) {
            StartupResult result = runAndCapture(input, tempDir.resolve("invalid-" + input.hashCode()));
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.output()).contains("stdin encryption key");
            assertThat(result.output()).doesNotContain(KEY);
        }
    }

    @Test
    void existingEncryptedDatabaseOpensWithStdinKeyAndRejectsAnotherValidKey() throws Exception {
        Path home = tempDir.resolve("existing-db");
        try (RunningJupiter first = start(KEY, home)) {
            assertThat(first.responseBody()).contains("UP");
        }
        seedEncryptedApplicationData(home);

        try (RunningJupiter reopened = start(KEY, home)) {
            assertThat(reopened.responseBody()).contains("UP");
        }

        StartupResult wrong = runAndCapture(WRONG_KEY, home);
        assertThat(wrong.exitCode()).isNotZero();
        assertThat(wrong.output()).contains("JUPITER_ENCRYPTION_KEY does not match this database");
        assertThat(wrong.output()).doesNotContain(KEY).doesNotContain(WRONG_KEY);
    }

    private static RunningJupiter start(String key, Path home) throws Exception {
        int port = freePort();
        Path log = home.resolve("startup.log");
        Process process = launchWithoutWaiting(key, home, port, log);
        try {
            waitForHealth(process, port, log);
            return new RunningJupiter(process, log, port, home.resolve("environment-marker"));
        } catch (Exception | Error failure) {
            destroy(process);
            throw failure;
        }
    }

    private static void seedEncryptedApplicationData(Path home) throws Exception {
        Path database = home.resolve(".jupiter/jupiter.sqlite");
        String ciphertext = new TextEncryptor(EncryptionKey.fromBase64(KEY)).encrypt(
                "encrypted restart data", "app_state.assistant_completed_hook_script");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var update = connection.prepareStatement(
                    "UPDATE app_state SET assistant_completed_hook_script = ?, "
                            + "assistant_errored_hook_script = NULL, subagent_completed_hook_script = NULL WHERE id = 1")) {
                update.setString(1, ciphertext);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery(
                    "SELECT assistant_completed_hook_script FROM app_state WHERE id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isNotEmpty();
            }
            try (var update = connection.prepareStatement("UPDATE encryption_metadata SET migration_complete = 0 WHERE id = 1")) {
                update.executeUpdate();
            }
        }
        assertThat(ciphertext).startsWith("JUPITER-ENCRYPTED-V1-AES-256-GCM:");
    }

    private static StartupResult runAndCapture(String key, Path home) throws Exception {
        int port = freePort();
        Path log = home.resolve("startup.log");
        Process process = null;
        try {
            process = launchWithoutWaiting(key, home, port, log);
            if (!process.waitFor(STARTUP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                fail("Jupiter did not fail within timeout; output: " + readLog(log));
            }
            return new StartupResult(process.exitValue(), readLog(log));
        } finally {
            if (process != null) {
                destroy(process);
            }
        }
    }

    private static Process launchWithoutWaiting(String key, Path home, int port, Path log) throws IOException {
        Files.createDirectories(home.resolve(".jupiter"));
        String database = home.resolve(".jupiter/jupiter.sqlite").toAbsolutePath().normalize().toString();
        Path environmentMarker = home.resolve("environment-marker");
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-Dspring.devtools.restart.enabled=false", "--enable-native-access=ALL-UNNAMED",
                "-XX:+DisableAttachMechanism", "-cp", System.getProperty("java.class.path"), EnvironmentProbe.class.getName(),
                Jupiter.class.getName(), environmentMarker.toString(),
                "--server.port=" + port,
                "--spring.datasource.url=jdbc:sqlite:file:" + database + "?journal_mode=WAL&foreign_keys=on&busy_timeout=20000",
                "--spring.flyway.enabled=true", "--spring.main.banner-mode=off",
                "--spring.devtools.restart.enabled=false", "--agent.workspace-root=" + home);
        Map<String, String> environment = new HashMap<>(builder.environment());
        environment.put("JUPITER_TEST_SENTINEL", "present");
        environment.put("JUPITER_ENCRYPTION_KEY", "fake-test-key");
        ProcessEnvironmentSanitizer.sanitize(environment);
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.environment().put("HOME", home.toString());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        process.getOutputStream().write((key + "\n").getBytes(StandardCharsets.US_ASCII));
        process.getOutputStream().close();
        return process;
    }

    private static void waitForHealth(Process process, int port, Path log) throws Exception {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("Jupiter exited during startup: " + readLog(log));
            }
            try {
                HttpURLConnection connection = (HttpURLConnection) new java.net.URL("http://127.0.0.1:" + port + "/health").openConnection();
                connection.setConnectTimeout(500);
                connection.setReadTimeout(500);
                if (connection.getResponseCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // The embedded server is still starting.
            }
            Thread.sleep(100);
        }
        process.destroyForcibly();
        throw new AssertionError("Jupiter startup timed out: " + readLog(log));
    }

    private static void destroy(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String readLog(Path log) throws IOException {
        if (!Files.exists(log)) {
            return "";
        }
        byte[] bytes = Files.readAllBytes(log);
        int start = Math.max(0, bytes.length - 128 * 1024);
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private record StartupResult(int exitCode, String output) { }

    public static final class EnvironmentProbe {
        public static void main(String[] args) throws Exception {
            boolean present = System.getenv().containsKey("JUPITER_ENCRYPTION_KEY");
            Files.writeString(Path.of(args[1]), present ? "present" : "absent", StandardCharsets.US_ASCII);
            if (present) {
                throw new AssertionError("JUPITER_ENCRYPTION_KEY was present in the launched JVM");
            }
            Jupiter.main(java.util.Arrays.copyOfRange(args, 2, args.length));
        }
    }

    private static final class RunningJupiter implements AutoCloseable {
        private final Process process;
        private final Path log;
        private final int port;
        private final Path environmentMarker;

        private RunningJupiter(Process process, Path log, int port, Path environmentMarker) {
            this.process = process;
            this.log = log;
            this.port = port;
            this.environmentMarker = environmentMarker;
        }

        String responseBody() throws IOException {
            return new String(((HttpURLConnection) new java.net.URL("http://127.0.0.1:" + port + "/health")
                    .openConnection()).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws Exception {
            destroy(process);
            assertThat(readLog(log)).doesNotContain(KEY).doesNotContain(WRONG_KEY);
        }
    }
}
