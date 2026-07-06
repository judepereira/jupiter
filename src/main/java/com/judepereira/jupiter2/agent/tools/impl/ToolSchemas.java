package com.judepereira.jupiter2.agent.tools.impl;

import java.util.Map;

public class ToolSchemas {
    // helpers if needed in future
    public static Map<String, Object> stringProp(String desc) {
        return Map.of("type", "string", "description", desc);
    }
}
