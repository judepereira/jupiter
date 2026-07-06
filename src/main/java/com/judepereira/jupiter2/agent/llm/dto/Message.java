package com.judepereira.jupiter2.agent.llm.dto;

public class Message {
    public enum Role { SYSTEM, USER, ASSISTANT }

    private final Role role;
    private final String content;

    public Message(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
