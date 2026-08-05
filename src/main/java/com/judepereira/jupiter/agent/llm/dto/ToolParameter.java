package com.judepereira.jupiter.agent.llm.dto;

import java.util.List;

public sealed interface ToolParameter permits ToolParameter.StringParameter, ToolParameter.IntegerParameter,
        ToolParameter.NumberParameter, ToolParameter.BooleanParameter, ToolParameter.EnumParameter,
        ToolParameter.ObjectParameter {

    String name();

    String description();

    static StringParameter string(String name, String description) {
        return new StringParameter(name, description);
    }

    static IntegerParameter integer(String name, String description) {
        return new IntegerParameter(name, description);
    }

    static NumberParameter number(String name, String description) {
        return new NumberParameter(name, description);
    }

    static BooleanParameter bool(String name, String description) {
        return new BooleanParameter(name, description);
    }

    static EnumParameter enumeration(String name, String description, String... values) {
        return new EnumParameter(name, description, List.of(values));
    }

    static EnumParameter enumeration(String name, String description, List<String> values) {
        return new EnumParameter(name, description, values);
    }

    static ObjectParameter object(String name, String description, ToolSchema schema) {
        return new ObjectParameter(name, description, schema);
    }

    record StringParameter(String name, String description) implements ToolParameter {}

    record IntegerParameter(String name, String description) implements ToolParameter {}

    record NumberParameter(String name, String description) implements ToolParameter {}

    record BooleanParameter(String name, String description) implements ToolParameter {}

    record EnumParameter(String name, String description, List<String> values) implements ToolParameter {
        public EnumParameter {
            values = List.copyOf(values);
        }
    }

    record ObjectParameter(String name, String description, ToolSchema schema) implements ToolParameter {}
}
