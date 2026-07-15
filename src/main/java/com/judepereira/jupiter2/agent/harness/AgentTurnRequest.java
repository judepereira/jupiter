package com.judepereira.jupiter2.agent.harness;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AgentTurnRequest {
    private final String systemPrompt;
    private final String userPrompt;
}
