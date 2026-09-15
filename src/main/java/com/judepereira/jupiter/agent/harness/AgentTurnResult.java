package com.judepereira.jupiter.agent.harness;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AgentTurnResult {
    private final String finalText;
    private final List<ToolCallTrace> traces;
}
