package com.judepereira.jupiter.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

public final class RequestOrigin {
    private RequestOrigin() {
    }

    public static boolean matches(URI supplied, HttpServletRequest request) {
        if (supplied.getScheme() == null || supplied.getHost() == null) {
            return false;
        }
        String scheme = PublicRequestScheme.isHttps(request) ? "https" : "http";
        int port = request.getServerPort();
        if (port <= 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return supplied.getScheme().equalsIgnoreCase(scheme)
                && supplied.getHost().equalsIgnoreCase(request.getServerName())
                && effectivePort(supplied.getScheme(), supplied.getPort()) == port;
    }

    public static int effectivePort(String scheme, int port) {
        return port < 0 ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : port;
    }
}
