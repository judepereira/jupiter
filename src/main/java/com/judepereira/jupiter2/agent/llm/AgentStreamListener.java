package com.judepereira.jupiter2.agent.llm;

import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import com.judepereira.jupiter2.agent.llm.dto.Message;

import java.util.List;

/**
 * Simple callback interface for streaming model responses.
 * All methods are no-op by default so implementers only need the callbacks they use.
 */
public interface AgentStreamListener {

    default void onTextDelta(String delta) {}

    default void onStatus(String status) {}

    default void onComplete(AgentTurnResult result) {}

    default void onError(Exception e) {}

    default void onToolCallTrace(ToolCallTrace trace) {}

    default void onToolCallStarted(ToolCallTrace trace) {}

    default void onToolCallProgress(String toolCallId, String toolName, String eventName, Object payload) {}

    default List<Message> onBeforeModelRequest(AgentTurnRequest request, List<Message> conversation) {
        return conversation;
    }
}
