package com.judepereira.jupiter2.agent.llm;

import com.judepereira.jupiter2.agent.harness.AgentTurnResult;

/**
 * Simple callback interface for streaming model responses.
 * All methods are no-op by default so implementers only need the callbacks they use.
 */
public interface AgentStreamListener {

    default void onTextDelta(String delta) {}

    default void onStatus(String status) {}

    default void onComplete(AgentTurnResult result) {}

    default void onError(Exception e) {}
}
