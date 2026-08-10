package com.judepereira.jupiter.agent.tools;

import com.judepereira.jupiter.agent.tools.impl.RunCommandTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
