package com.judepereira.jupiter.config;

import jakarta.servlet.http.HttpServletRequest;

public final class PublicRequestScheme {

    private PublicRequestScheme() {
    }

    public static boolean isHttps(HttpServletRequest request) {
        String forwarded = firstForwardedProto(request.getHeader("Forwarded"));
        if (forwarded != null) {
            return "https".equalsIgnoreCase(forwarded);
        }
        String xForwarded = firstCommaSeparatedValue(request.getHeader("X-Forwarded-Proto"));
        if (xForwarded != null) {
            return "https".equalsIgnoreCase(xForwarded);
        }
        return request.isSecure();
    }

    private static String firstForwardedProto(String header) {
        String firstElement = firstCommaSeparatedValue(header);
        if (firstElement == null) {
            return null;
        }
        for (String parameter : firstElement.split(";")) {
            int separator = parameter.indexOf('=');
            if (separator > 0 && "proto".equalsIgnoreCase(parameter.substring(0, separator).trim())) {
                return stripQuotes(parameter.substring(separator + 1).trim());
            }
        }
        return null;
    }

    private static String firstCommaSeparatedValue(String header) {
        if (header == null) {
            return null;
        }
        String value = header.split(",", 2)[0].trim();
        return value.isEmpty() ? null : value;
    }

    private static String stripQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }
}
