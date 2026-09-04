package com.judepereira.jupiter.agent.tools;

import com.judepereira.jupiter.agent.tools.impl.ApplyPatchTool;
import com.judepereira.jupiter.agent.tools.impl.RunCommandTool;
import com.judepereira.jupiter.agent.tools.impl.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ToolGuardrailsTest {

    @Test
    public void write_file_blocked_when_allow_write_false(@TempDir Path tmp) throws Exception {
        WriteFileTool t = new WriteFileTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, false, false, 5, null, null, null, null, null, null, null);
        ToolExecutionResult res = t.execute(Map.of("path", "a.txt", "content", "hello"), ctx);
        assertFalse(res.isSuccess());
        assertTrue(res.getText().toLowerCase().contains("disabled") || res.getText().toLowerCase().contains("disabled"));
        assertFalse(Files.exists(tmp.resolve("a.txt")));
    }

    @Test
    public void run_command_blocked_when_allow_command_false(@TempDir Path tmp) throws Exception {
        RunCommandTool t = new RunCommandTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, false, false, 1, null, null, null, null, null, null, null);
        ToolExecutionResult res = t.execute(Map.of("command", "echo hi"), ctx);
        assertFalse(res.isSuccess());
        assertTrue(res.getText().toLowerCase().contains("disabled"));
    }

    @Test
    public void apply_patch_fails_when_oldText_missing(@TempDir Path tmp) throws Exception {
        // create file
        Path file = tmp.resolve("file.txt");
        Files.writeString(file, "original content\n");
        ApplyPatchTool t = new ApplyPatchTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, false, 5, null, null, null, null, null, null, null);
        // missing oldText
        ToolExecutionResult res = t.execute(Map.of("path", "file.txt", "newText", "new"), ctx);
        assertFalse(res.isSuccess());
        assertTrue(res.getText().toLowerCase().contains("missing") || res.getText().toLowerCase().contains("oldtext") || res.getText().toLowerCase().contains("missing args"));
    }
}
