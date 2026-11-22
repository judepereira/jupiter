package com.judepereira.jupiter.config;

import jakarta.servlet.http.HttpServletRequest;

public final class PublicRequestScheme {

    private PublicRequestScheme() {
    }

    public static boolean isHttps(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getScheme());
    }
}
