package com.judepereira.jupiter2.agent.harness;

import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.llm.AgentModelClient;
import com.judepereira.jupiter2.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter2.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter2.agent.tools.ToolRegistry;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CodingAgentHarness {

    private final AgentModelClientFactory modelFactory;
    private final ToolRegistry registry;
    private final AgentProperties props;

    public CodingAgentHarness(AgentModelClientFactory modelFactory, ToolRegistry registry, AgentProperties props) {
        this.modelFactory = modelFactory;
        this.registry = registry;
        this.props = props;
    }

    public AgentTurnResult runTurn(AgentTurnRequest request) {
        AgentModelClient model = modelFactory.getClient();

        List<Message> convo = new ArrayList<>();
        convo.add(new Message(Message.Role.SYSTEM, request.getSystemPrompt()));
        convo.add(new Message(Message.Role.USER, request.getUserPrompt()));

        List<ToolCallTrace> traces = new ArrayList<>();

        int max = props.getMaxIterations();
        ToolExecutionContext execCtx = new ToolExecutionContext(Path.of(props.getWorkspaceRoot()),
                props.getTooling().isAllowWrite(), props.getTooling().isAllowCommand(), props.getCommandTimeoutSeconds());

        for (int i = 0; i < max; i++) {
            // provide tool definitions
            List<ToolDefinition> defs = registry.all().values().stream().map(AgentTool -> AgentTool.definition()).collect(Collectors.toList());

            ModelResponse resp = model.chat(convo, defs);

            ToolCall call = resp.getToolCall();
            String assistantText = resp.getAssistantText();

            if (call != null) {
                // execute tool
                String toolName = call.getToolName();
                Map<String, Object> args = call.getArguments();
                try {
                    ToolExecutionResult result = registry.executeByName(toolName, args, execCtx);
                    // append tool result as assistant message for model
                    String toolMsg = "[tool_result] " + toolName + "\n" + (result.getText() == null ? "" : result.getText());
                    convo.add(new Message(Message.Role.ASSISTANT, toolMsg));
                    traces.add(new ToolCallTrace(toolName, args, result.isSuccess(), result.getText(), result.getMachine()));
                } catch (IllegalArgumentException e) {
                    // unknown tool
                    String toolMsg = "[tool_error] Unknown tool: " + toolName;
                    convo.add(new Message(Message.Role.ASSISTANT, toolMsg));
                    traces.add(new ToolCallTrace(toolName, args, false, toolMsg, Map.of("error", e.getMessage())));
                } catch (Exception e) {
                    String toolMsg = "[tool_error] " + e.getMessage();
                    convo.add(new Message(Message.Role.ASSISTANT, toolMsg));
                    traces.add(new ToolCallTrace(toolName, args, false, toolMsg, Map.of("exception", e.toString())));
                }
                // continue loop
            } else if (assistantText != null && !assistantText.isBlank()) {
                // final assistant text
                return new AgentTurnResult(assistantText, traces);
            } else {
                // no tool call and no text; continue
            }
        }

        // max iterations reached
        String fallback = "Agent reached max iterations without producing a final answer.";
        return new AgentTurnResult(fallback, traces);
    }
}
