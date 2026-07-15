package com.judepereira.jupiter2.agent.llm.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class Message {
    public enum Role { SYSTEM, USER, ASSISTANT }

    private final Role role;
    private final String content;
}
