package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolProgressSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

public class RunCommandToolTest {

    @Test
    void inlineCaptureFilesAreDeletedAndLargeCaptureIsRetained(@TempDir Path tmp) throws Exception {
        List<Path> created = new ArrayList<>();
        RunCommandTool tool = new RunCommandTool((prefix, suffix) -> {
            Path path = Files.createTempFile(tmp, prefix, suffix);
            created.add(path);
            return path;
        });
        ToolExecutionContext context = context(tmp);

        var result = tool.execute(Map.of("command", "printf inline; printf large 1>&2; head -c 5000 /dev/zero | tr '\\0' x 1>&2"), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(created).hasSize(3);
        assertThat(Files.exists(created.get(0))).isFalse();
        assertThat(Files.exists(created.get(1))).isFalse();
        assertThat(Files.exists(created.get(2))).isTrue();
    }

    @Test
    void timeoutCleansCaptureFilesAfterReadersStop(@TempDir Path tmp) {
        List<Path> created = new ArrayList<>();
        RunCommandTool tool = new RunCommandTool((prefix, suffix) -> {
            Path path = Files.createTempFile(tmp, prefix, suffix);
            created.add(path);
            return path;
        });

        var result = assertDoesNotThrow(() -> tool.execute(
                Map.of("command", "trap '' TERM; while :; do echo output; done"),
                new ToolExecutionContext(tmp, true, true, 1, null, null, null, null,
                        null, java.util.Set.of(), null, null)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(created).hasSize(3);
        assertThat(created).allMatch(path -> !Files.exists(path));
    }

    @Test
    void captureSetupFailureDeletesPreviouslyCreatedFilesAndDoesNotLaunch(@TempDir Path tmp) {
        List<Path> created = new ArrayList<>();
        RunCommandTool tool = new RunCommandTool((prefix, suffix) -> {
            if ("stderr".equals(prefix)) {
                throw new java.io.IOException("stderr allocation failed");
            }
            Path path = Files.createTempFile(tmp, prefix, suffix);
            created.add(path);
            return path;
        });

        assertThatThrownBy(() -> tool.execute(Map.of("command", "touch launched"), context(tmp)))
                .isInstanceOf(java.io.IOException.class);
        assertThat(created).hasSize(2);
        assertThat(created).allMatch(path -> !Files.exists(path));
        assertThat(Files.exists(tmp.resolve("launched"))).isFalse();
    }

    private static ToolExecutionContext context(Path tmp) {
        return new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, java.util.Set.of(), null, null);
    }

    @Test
    public void does_not_hang_on_output(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool(Files::createTempFile);
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, java.util.Set.of(), null, null);
        String cmd = "for i in $(seq 1 10); do echo out$i; echo err$i 1>&2; done";
        var res = t.execute(Map.of("command", cmd), ctx);
        assertTrue(res.isSuccess());
        var machine = res.getMachine();
        assertEquals(0, machine.get("exitCode"));
        String stdout = (String) machine.get("stdout");
        String stderr = (String) machine.get("stderr");
        assertNotNull(stdout);
        assertNotNull(stderr);
        assertTrue(stdout.contains("out1"));
        assertTrue(stderr.contains("err1"));
    }

    @Test
    public void passesEnvironmentVariablesToProcess(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool(Files::createTempFile);
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null,
                Map.of("PROJECT_ENV_VAR", "project-value"), java.util.Set.of(), ToolProgressSink.noop(), null);

        var res = t.execute(Map.of("command", "printf '%s' \"$PROJECT_ENV_VAR\""), ctx);

        assertThat(res.isSuccess()).isTrue();
        assertThat((String) res.getMachine().get("stdout")).isEqualTo("project-value\n");
    }

    @Test
    public void does_not_pass_http_auth_credentials_to_process(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool(Files::createTempFile);
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null,
                Map.of("JUPITER_HTTP_AUTH_PASSWORD", "secret-password",
                        "JUPITER_HTTP_AUTH_USERNAME", "secret-user",
                        "PROJECT_ENV_VAR", "project-value"), java.util.Set.of(), ToolProgressSink.noop(), null);

        var res = t.execute(Map.of("command", "printf '%s|%s|%s' \"${JUPITER_HTTP_AUTH_PASSWORD-}\" \"${JUPITER_HTTP_AUTH_USERNAME-}\" \"$PROJECT_ENV_VAR\""), ctx);

        assertThat(res.isSuccess()).isTrue();
        assertThat((String) res.getMachine().get("stdout")).isEqualTo("||project-value\n");
    }

    @Test
    public void long_stdout_is_previewed_with_utf8_boundaries_and_written_to_file(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool(Files::createTempFile);
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, java.util.Set.of(), null, null);
        String cmd = "i=0; while [ $i -lt 3000 ]; do printf '😀'; i=$((i+1)); done";

        var res = t.execute(Map.of("command", cmd), ctx);

        assertTrue(res.isSuccess());
        String stdout = (String) res.getMachine().get("stdout");
        assertNotNull(stdout);
        assertTrue(stdout.contains("\n...\n...\n"));

        String preview = stdout.substring(0, stdout.indexOf("\n...\n...\n"));
        String fullOutputPath = stdout.substring(stdout.lastIndexOf("\n\n") + 2);
        Path outputFile = Path.of(fullOutputPath);
        assertTrue(Files.exists(outputFile));

        String fullText = Files.readString(outputFile, StandardCharsets.UTF_8);
        assertTrue(preview.codePoints().allMatch(cp -> cp == 0x1F600));
        assertTrue(preview.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024);
        assertTrue(fullText.startsWith(preview));
        assertTrue(fullText.endsWith("\n"));
    }

    @Test
    public void preserves_wrapped_suffix_and_preview_for_large_output(@TempDir Path tmp) throws Exception {
        RunCommandTool tool = new RunCommandTool(Files::createTempFile);
        ToolExecutionContext context = context(tmp);
        String command = "head -c 7000 /dev/zero | tr '\\0' a; printf 'TAIL-01'; printf 'TAIL-02'";

        var result = tool.execute(Map.of("command", command), context);

        String stdout = (String) result.getMachine().get("stdout");
        assertThat(stdout).contains("\n...\n...\n");
        assertThat(stdout).contains("TAIL-01TAIL-02");
        Path artifact = Path.of(stdout.substring(stdout.lastIndexOf("\n\n") + 2));
        assertThat(Files.readString(artifact, StandardCharsets.UTF_8)).endsWith("TAIL-01TAIL-02\n");
    }

    @Test
    public void preserves_utf8_when_a_character_crosses_a_read_boundary(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool(Files::createTempFile);
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, java.util.Set.of(), null, null);
        String cmd = "head -c 8188 /dev/zero | tr '\\0' a; printf '\\360\\237\\230\\200'; printf '%05000d' 0";

        var res = t.execute(Map.of("command", cmd), ctx);

        assertTrue(res.isSuccess());
        String stdout = (String) res.getMachine().get("stdout");
        String fullOutputPath = stdout.substring(stdout.lastIndexOf("\n\n") + 2);
        String fullText = Files.readString(Path.of(fullOutputPath), StandardCharsets.UTF_8);
        assertThat(fullText).startsWith("a".repeat(8188) + "😀");
    }

    @Test
    public void long_stderr_is_previewed_with_utf8_boundaries_and_written_to_file(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool(Files::createTempFile);
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, java.util.Set.of(), null, null);
        String cmd = "for i in $(seq 1 3000); do printf '😀' 1>&2; done";

        var res = t.execute(Map.of("command", cmd), ctx);

        assertTrue(res.isSuccess());
        String stderr = (String) res.getMachine().get("stderr");
        assertNotNull(stderr);
        assertTrue(stderr.contains("\n...\n...\n"));

        String preview = stderr.substring(0, stderr.indexOf("\n...\n...\n"));
        String fullOutputPath = stderr.substring(stderr.lastIndexOf("\n\n") + 2);
        Path outputFile = Path.of(fullOutputPath);
        assertTrue(Files.exists(outputFile));

        String fullText = Files.readString(outputFile, StandardCharsets.UTF_8);
        assertTrue(preview.codePoints().allMatch(cp -> cp == 0x1F600));
        assertTrue(preview.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024);
        assertTrue(fullText.startsWith(preview));
        assertTrue(fullText.endsWith("\n"));
    }
}
