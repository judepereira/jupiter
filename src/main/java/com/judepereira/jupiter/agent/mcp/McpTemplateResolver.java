package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.persistence.Persistence.McpServerHeader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class McpTemplateResolver {
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{env\\.([A-Za-z_][A-Za-z0-9_]*)}");

    private final Function<String, String> systemEnvironmentLookup;

    McpTemplateResolver() {
        this(System::getenv);
    }

    McpTemplateResolver(Function<String, String> systemEnvironmentLookup) {
        this.systemEnvironmentLookup = systemEnvironmentLookup;
    }

    String resolve(String fieldName, String value, Map<String, String> projectEnvironmentVariables) {
        if (value == null) {
            return null;
        }

        String resolved = replaceTemplates(value, projectEnvironmentVariables);
        rejectLineBreaks(fieldName, resolved);
        return resolved;
    }

    Map<String, String> resolveHeaders(List<McpServerHeader> headers, Map<String, String> projectEnvironmentVariables) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        for (McpServerHeader header : headers) {
            resolved.put(header.name(), resolve("MCP header " + header.name(), header.value(), projectEnvironmentVariables));
        }
        return Map.copyOf(resolved);
    }

    static String slugify(String value) {
        if (value == null) {
            return "mcp";
        }
        String slug = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "mcp" : slug;
    }

    private String replaceTemplates(String value, Map<String, String> projectEnvironmentVariables) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String envValue = lookupEnv(envName, projectEnvironmentVariables);
            if (envValue == null) {
                throw new IllegalArgumentException("Unable to resolve MCP environment placeholder");
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(envValue));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String lookupEnv(String name, Map<String, String> projectEnvironmentVariables) {
        if (projectEnvironmentVariables != null) {
            String value = projectEnvironmentVariables.get(name);
            if (value != null) {
                return value;
            }
        }
        return systemEnvironmentLookup.apply(name);
    }

    private static void rejectLineBreaks(String fieldName, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(fieldName + " must not contain line breaks");
        }
    }
}
