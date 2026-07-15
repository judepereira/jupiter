package com.judepereira.jupiter2.agent.llm.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class Message {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    private final Role role;
    private final String content;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;

    public Message(Role role, String content) {
        this(role, content, null, null);
    }

    public Message(Role role, String content, String toolCallId) {
        this(role, content, toolCallId, null);
    }

    public Message(Role role, String content, List<ToolCall> toolCalls) {
        this(role, content, null, toolCalls);
    }

    public Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls;
    }
}
