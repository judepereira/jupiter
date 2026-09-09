package com.judepereira.jupiter.agent.llm.openai;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolParameter;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.List;

public final class LangChain4jToolSpecificationMapper {

    public List<ToolSpecification> toToolSpecifications(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<ToolSpecification> specifications = new ArrayList<>(tools.size());
        for (ToolDefinition tool : tools) {
            ToolSpecification nativeSpecification = tool.getNativeToolSpecification();
            if (nativeSpecification != null) {
                specifications.add(ToolSpecification.builder()
                        .name(tool.getName())
                        .description(nativeSpecification.description())
                        .parameters(nativeSpecification.parameters())
                        .build());
            } else {
                specifications.add(ToolSpecification.builder()
                        .name(tool.getName())
                        .description(tool.getDescription() == null ? "" : tool.getDescription())
                        .parameters(toObjectSchema(tool.getSchema()))
                        .build());
            }
        }
        return specifications;
    }

    private static JsonObjectSchema toObjectSchema(ToolSchema schema) {
        return toObjectSchema(schema, null);
    }

    private static JsonObjectSchema toObjectSchema(ToolSchema schema, String descriptionFallback) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        if (schema == null) {
            return builder.build();
        }

        String description = schema.description();
        if ((description == null || description.isBlank()) && descriptionFallback != null && !descriptionFallback.isBlank()) {
            description = descriptionFallback;
        }
        if (description != null && !description.isBlank()) {
            builder.description(description);
        }

        if (!schema.required().isEmpty()) {
            builder.required(schema.required());
        }

        if (schema.additionalProperties() != null) {
            builder.additionalProperties(schema.additionalProperties());
        }

        for (ToolParameter parameter : schema.properties()) {
            builder.addProperty(parameter.name(), toSchemaElement(parameter));
        }

        return builder.build();
    }

    private static JsonSchemaElement toSchemaElement(ToolParameter parameter) {
        if (parameter instanceof ToolParameter.StringParameter stringParameter) {
            return JsonStringSchema.builder().description(stringParameter.description()).build();
        }
        if (parameter instanceof ToolParameter.IntegerParameter integerParameter) {
            return JsonIntegerSchema.builder().description(integerParameter.description()).build();
        }
        if (parameter instanceof ToolParameter.NumberParameter numberParameter) {
            return JsonNumberSchema.builder().description(numberParameter.description()).build();
        }
        if (parameter instanceof ToolParameter.BooleanParameter booleanParameter) {
            return JsonBooleanSchema.builder().description(booleanParameter.description()).build();
        }
        if (parameter instanceof ToolParameter.EnumParameter enumParameter) {
            return JsonEnumSchema.builder()
                    .description(enumParameter.description())
                    .enumValues(enumParameter.values())
                    .build();
        }
        if (parameter instanceof ToolParameter.ObjectParameter objectParameter) {
            return toObjectSchema(objectParameter.schema(), objectParameter.description());
        }
        if (parameter instanceof ToolParameter.ArrayParameter arrayParameter) {
            return JsonArraySchema.builder()
                    .description(arrayParameter.description())
                    .items(toSchemaElement(arrayParameter.items()))
                    .build();
        }

        throw new IllegalStateException("Unsupported tool parameter type: " + parameter.getClass().getName());
    }
}
