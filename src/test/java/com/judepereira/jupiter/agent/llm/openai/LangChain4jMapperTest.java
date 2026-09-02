package com.judepereira.jupiter.agent.llm.openai;

import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolParameter;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.integer;
import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class LangChain4jMapperTest {

    private final ToolArgumentsCodec codec = new ToolArgumentsCodec();
    private final LangChain4jMessageMapper messageMapper = new LangChain4jMessageMapper(codec);
    private final LangChain4jToolSpecificationMapper toolSpecificationMapper = new LangChain4jToolSpecificationMapper();
    private final OpenAiRequestParametersMapper requestParametersMapper = new OpenAiRequestParametersMapper();

    @Test
    public void round_trips_tool_arguments() {
        Map<String, Object> arguments = Map.of("path", "notes.txt", "nested", Map.of("startLine", 2));

        assertEquals(arguments, codec.parse(codec.serialize(arguments)));
    }

    @Test
    public void converts_conversation_with_tool_calls_and_tool_results() {
        List<ChatMessage> messages = messageMapper.toChatMessages(List.of(
                new Message(Message.Role.SYSTEM, "sys"),
                new Message(Message.Role.USER, "user"),
                new Message(Message.Role.ASSISTANT, null, List.of(new ToolCall("call-123", "write_file", Map.of("path", "x.txt")))),
                new Message(Message.Role.TOOL, "written", "call-123")
        ));

        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertInstanceOf(UserMessage.class, messages.get(1));

        AiMessage assistant = (AiMessage) messages.get(2);
        assertEquals(1, assistant.toolExecutionRequests().size());
        assertEquals("call-123", assistant.toolExecutionRequests().get(0).id());
        assertEquals("write_file", assistant.toolExecutionRequests().get(0).name());

        ToolExecutionResultMessage tool = (ToolExecutionResultMessage) messages.get(3);
        assertEquals("call-123", tool.id());
        assertEquals("write_file", tool.toolName());
        assertEquals("written", tool.text());
    }

    @Test
    public void converts_tool_definitions_to_langchain4j_tool_specifications() {
        ToolSchema schema = ToolSchema.object(
                string("path", "relative path"),
                ToolParameter.object("options", "options", ToolSchema.object(
                        integer("startLine", "start line")
                ))
        ).required("path");

        assertEquals(List.of("path", "options"), schema.properties().stream().map(ToolParameter::name).toList());
        assertEquals(List.of("path"), schema.required());

        List<ToolSpecification> specs = toolSpecificationMapper.toToolSpecifications(List.of(
                new ToolDefinition("read_file", "Read a file", schema)
        ));

        assertEquals(1, specs.size());
        assertEquals("read_file", specs.get(0).name());
        assertEquals("Read a file", specs.get(0).description());

        JsonObjectSchema parameters = specs.get(0).parameters();
        assertInstanceOf(JsonStringSchema.class, parameters.properties().get("path"));
        assertInstanceOf(JsonObjectSchema.class, parameters.properties().get("options"));
    }

    @Test
    public void maps_thinking_level_to_reasoning_effort() {
        OpenAiResponsesChatRequestParameters parameters = requestParametersMapper.toRequestParameters(
                new AgentModelOptions("m", "api-model", ThinkingLevel.HIGH, true, "low")
        );

        assertNotNull(parameters);
        assertEquals("high", parameters.reasoningEffort());
        assertEquals("low", parameters.textVerbosity());
    }

    @Test
    public void omits_reasoning_effort_when_not_supported() {
        assertNull(requestParametersMapper.toRequestParameters(
                new AgentModelOptions("m", "api-model", ThinkingLevel.HIGH, false, null)
        ));
    }

    @Test
    public void maps_openai_usage_and_response_metadata() {
        ModelResponse response = messageMapper.toModelResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("assistant"))
                .metadata(OpenAiResponsesChatResponseMetadata.builder()
                        .id("response-1")
                        .modelName("gpt-5.4")
                        .tokenUsage(OpenAiTokenUsage.builder()
                                .inputTokenCount(100)
                                .outputTokenCount(40)
                                .totalTokenCount(140)
                                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(25).build())
                                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(10).build())
                                .build())
                        .finishReason(FinishReason.STOP)
                        .createdAt(123L)
                        .completedAt(456L)
                        .serviceTier("default")
                        .build())
                .build());

        assertEquals(100, response.getMetadata().inputTokenCount());
        assertEquals(40, response.getMetadata().outputTokenCount());
        assertEquals(140, response.getMetadata().totalTokenCount());
        assertEquals(25, response.getMetadata().cachedInputTokenCount());
        assertEquals(10, response.getMetadata().reasoningTokenCount());
        assertEquals("response-1", response.getMetadata().responseId());
        assertEquals("gpt-5.4", response.getMetadata().modelId());
        assertEquals("STOP", response.getMetadata().finishReason());
        assertEquals(Map.of("createdAt", 123L, "completedAt", 456L, "serviceTier", "default"), response.getMetadata().providerMetadata());
    }

    @Test
    public void converts_chat_response_to_model_response_and_tool_call() {
        ModelResponse response = messageMapper.toModelResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("assistant", List.of(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("read_file")
                        .arguments("{\"path\":\"notes.txt\"}")
                        .build())))
                .build());

        assertEquals("assistant", response.getAssistantText());
        assertNotNull(response.getToolCall());
        assertEquals("call-1", response.getToolCall().getToolCallId());
        assertEquals("read_file", response.getToolCall().getToolName());
        assertEquals("notes.txt", response.getToolCall().getArguments().get("path"));
    }
}
