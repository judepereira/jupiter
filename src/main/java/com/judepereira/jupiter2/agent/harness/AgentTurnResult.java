package com.judepereira.jupiter2.agent.harness;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class AgentTurnResult {
    private final String finalText;
    private final List<ToolCallTrace> traces;
}
