package com.judepereira.jupiter.agent.tools;

import com.judepereira.jupiter.agent.tools.impl.RunCommandTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

public class RunCommandToolTest {

    @Test
    public void does_not_hang_on_output(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5);
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
        RunCommandTool t = new RunCommandTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null,
                Map.of("PROJECT_ENV_VAR", "project-value"), ToolProgressSink.noop());

        var res = t.execute(Map.of("command", "printf '%s' \"$PROJECT_ENV_VAR\""), ctx);

        assertThat(res.isSuccess()).isTrue();
        assertThat((String) res.getMachine().get("stdout")).isEqualTo("project-value\n");
    }

    @Test
    public void does_not_pass_http_auth_credentials_to_process(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null,
                Map.of("JUPITER_HTTP_AUTH_PASSWORD", "secret-password",
                        "JUPITER_HTTP_AUTH_USERNAME", "secret-user",
                        "PROJECT_ENV_VAR", "project-value"), ToolProgressSink.noop());

        var res = t.execute(Map.of("command", "printf '%s|%s|%s' \"${JUPITER_HTTP_AUTH_PASSWORD-}\" \"${JUPITER_HTTP_AUTH_USERNAME-}\" \"$PROJECT_ENV_VAR\""), ctx);

        assertThat(res.isSuccess()).isTrue();
        assertThat((String) res.getMachine().get("stdout")).isEqualTo("||project-value\n");
    }

    @Test
    public void long_stdout_is_previewed_with_utf8_boundaries_and_written_to_file(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5);
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
    public void long_stderr_is_previewed_with_utf8_boundaries_and_written_to_file(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5);
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
