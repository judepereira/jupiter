package com.judepereira.jupiter2.agent.llm.openai;

import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.config.OpenAiProperties;
import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter2.agent.llm.AgentModelOptions;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.llm.dto.ToolSchema;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.judepereira.jupiter2.agent.llm.dto.ToolParameter.string;

public class OpenAiAgentModelClientTest {

    @Test
    public void source_does_not_reference_raw_http_or_mapping_helpers() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/judepereira/jupiter2/agent/llm/openai/OpenAiAgentModelClient.java"));

        assertFalse(source.contains("HttpURLConnection"));
        assertFalse(source.contains("java.net.http"));
        assertFalse(source.contains("ObjectMapper"));
        assertFalse(source.contains("TypeReference"));
        assertFalse(source.contains("JsonObjectSchema"));
    }

    @Test
    public void chat_honors_per_turn_model_and_reasoning_and_parses_tool_calls() {
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            capturedRequest.set(invocation.getArgument(0));
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("assistant", List.of(ToolExecutionRequest.builder()
                            .id("call-1")
                            .name("read_file")
                            .arguments("{\"path\":\"notes.txt\"}")
                            .build())))
                    .build();
        });

        RecordingClient client = new RecordingClient(chatModel, null);
        var response = client.chat(
                List.of(new Message(Message.Role.USER, "read it")),
                List.of(new ToolDefinition("read_file", "Read a file", ToolSchema.object(string("path", "path")).required("path"))),
                new AgentModelOptions("turn-model", "per-turn-model", ThinkingLevel.HIGH, true)
        );

        assertEquals(List.of("per-turn-model"), client.chatModelNames());
        assertEquals("per-turn-model", capturedRequest.get().modelName());
        assertInstanceOf(OpenAiResponsesChatRequestParameters.class, capturedRequest.get().parameters());
        assertEquals("high", ((OpenAiResponsesChatRequestParameters) capturedRequest.get().parameters()).reasoningEffort());
        assertEquals(1, capturedRequest.get().toolSpecifications().size());
        assertEquals("read_file", capturedRequest.get().toolSpecifications().get(0).name());
        assertEquals("assistant", response.getAssistantText());
        assertNotNull(response.getToolCall());
        assertEquals("call-1", response.getToolCall().getToolCallId());
        assertEquals("read_file", response.getToolCall().getToolName());
        assertEquals("notes.txt", response.getToolCall().getArguments().get("path"));
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    public void streaming_honors_per_turn_model_and_parses_streamed_tool_calls() {
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            capturedRequest.set(invocation.getArgument(0));
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("hel");
            handler.onPartialResponse("lo");
            handler.onCompleteToolCall(new CompleteToolCall(0, ToolExecutionRequest.builder()
                    .id("stream-call")
                    .name("write_file")
                    .arguments("{\"path\":\"out.txt\"}")
                    .build()));
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        RecordingClient client = new RecordingClient(null, streamingModel);
        List<String> deltas = new ArrayList<>();
        var response = client.chatStreaming(
                List.of(new Message(Message.Role.USER, "stream it")),
                List.of(new ToolDefinition("write_file", "Write a file", ToolSchema.object(string("path", "path")).required("path"))),
                new AgentModelOptions("turn-model", "stream-model", ThinkingLevel.MEDIUM, true),
                deltas::add
        );

        assertEquals(List.of("stream-model"), client.streamingModelNames());
        assertEquals("stream-model", capturedRequest.get().modelName());
        assertInstanceOf(OpenAiResponsesChatRequestParameters.class, capturedRequest.get().parameters());
        assertEquals("medium", ((OpenAiResponsesChatRequestParameters) capturedRequest.get().parameters()).reasoningEffort());
        assertEquals(List.of("hel", "lo"), deltas);
        assertEquals("done", response.getAssistantText());
        assertNotNull(response.getToolCall());
        assertEquals("stream-call", response.getToolCall().getToolCallId());
        assertEquals("write_file", response.getToolCall().getToolName());
        assertEquals("out.txt", response.getToolCall().getArguments().get("path"));
        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    private static final class RecordingClient extends OpenAiAgentModelClient {
        private final List<String> chatModelNames = new ArrayList<>();
        private final List<String> streamingModelNames = new ArrayList<>();
        private final ChatModel chatModel;
        private final StreamingChatModel streamingChatModel;

        private RecordingClient(ChatModel chatModel, StreamingChatModel streamingChatModel) {
            super(new OpenAiProperties(), new AgentProperties());
            this.chatModel = chatModel;
            this.streamingChatModel = streamingChatModel;
        }

        @Override
        protected ChatModel buildChatModel(String modelName) {
            chatModelNames.add(modelName);
            return chatModel;
        }

        @Override
        protected StreamingChatModel buildStreamingChatModel(String modelName) {
            streamingModelNames.add(modelName);
            return streamingChatModel;
        }

        private List<String> chatModelNames() {
            return chatModelNames;
        }

        private List<String> streamingModelNames() {
            return streamingModelNames;
        }
    }
}
