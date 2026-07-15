package com.judepereira.jupiter2.agent.harness;

import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.llm.AgentModelClient;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.judepereira.jupiter2.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter2.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter2.agent.tools.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CodingAgentHarness {

    private final AgentModelClientFactory modelFactory;
    private final ToolRegistry registry;
    private final AgentProperties props;

    public AgentTurnResult runTurn(AgentTurnRequest request) {
        return runTurnStreaming(request, new AgentStreamListener() {});
    }

    public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
        AgentModelClient model = modelFactory.getClient();

        List<Message> convo = new ArrayList<>();
        convo.add(new Message(Message.Role.SYSTEM, request.getSystemPrompt()));
        convo.add(new Message(Message.Role.USER, request.getUserPrompt()));

        List<ToolCallTrace> traces = new ArrayList<>();

        int max = props.getMaxIterations();
        ToolExecutionContext execCtx = new ToolExecutionContext(Path.of(props.getWorkspaceRoot()),
                props.getTooling().isAllowWrite(), props.getTooling().isAllowCommand(), props.getCommandTimeoutSeconds());

        StringBuilder accumulated = new StringBuilder();

        try {
            for (int i = 0; i < max; i++) {
                // provide tool definitions
                List<ToolDefinition> defs = registry.all().values().stream().map(AgentTool -> AgentTool.definition()).collect(Collectors.toList());

                // use streaming chat; emit deltas to listener
                ModelResponse resp = model.chatStreaming(convo, defs, delta -> {
                    // forward and accumulate every non-null delta, including whitespace-only chunks
                    if (delta != null) {
                        accumulated.append(delta);
                        listener.onTextDelta(delta);
                    }
                });

                ToolCall call = resp.getToolCall();
                String assistantText = resp.getAssistantText();

                if (call != null) {
                    // guard: ensure tool name is present
                    String toolName = call.getToolName();
                    Map<String, Object> args = call.getArguments();
                    if (toolName == null || toolName.isBlank()) {
                        String safeName = "(missing_tool_name)";
                        String toolMsg = "[tool_error] Tool call missing tool name";
                        // inform model / assistant convo
                        convo.add(new Message(Message.Role.ASSISTANT, toolMsg));
                        ToolCallTrace trace = new ToolCallTrace(safeName, args == null ? Map.of() : args, false, toolMsg,
                                Map.of("error", "tool name missing"));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_error:missing_tool_name");
                        // let model recover
                        continue;
                    }
                    listener.onStatus("calling_tool:" + toolName);
                    // execute tool
                    try {
                        ToolExecutionResult result = registry.executeByName(toolName, args, execCtx);
                        // append tool result as assistant message for model
                        String toolMsg = "[tool_result] " + toolName + "\n" + (result.getText() == null ? "" : result.getText());
                        convo.add(new Message(Message.Role.ASSISTANT, toolMsg));
                        ToolCallTrace trace = new ToolCallTrace(toolName, args, result.isSuccess(), result.getText(), result.getMachine());
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_result:" + toolName);
                    } catch (IllegalArgumentException e) {
                        // unknown tool
                        String toolMsg = "[tool_error] Unknown tool: " + toolName;
                        convo.add(new Message(Message.Role.ASSISTANT, toolMsg));
                        ToolCallTrace trace = new ToolCallTrace(toolName, args, false, toolMsg, Map.of("error", e.getMessage()));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_error:" + toolName);
                    } catch (Exception e) {
                        String toolMsg = "[tool_error] " + e.getMessage();
                        convo.add(new Message(Message.Role.ASSISTANT, toolMsg));
                        ToolCallTrace trace = new ToolCallTrace(toolName, args, false, toolMsg, Map.of("exception", e.toString()));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_exception:" + toolName);
                    }
                    // continue loop
                } else if (assistantText != null && !assistantText.isBlank()) {
                    // final assistant text
                    AgentTurnResult result = new AgentTurnResult(accumulated.length() == 0 ? assistantText : accumulated.toString(), traces);
                    listener.onComplete(result);
                    return result;
                } else {
                    // no tool call and no text; continue
                }
            }

            // max iterations reached
            String fallback = "Agent reached max iterations without producing a final answer.";
            AgentTurnResult result = new AgentTurnResult(fallback, traces);
            listener.onComplete(result);
            return result;
        } catch (Exception e) {
            listener.onError(e);
            throw e;
        }
    }
}
