package com.judepereira.jupiter.testsupport;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.util.Date;
import java.util.Map;

public final class JwtTestTokens {
    private JwtTestTokens() {
    }

    public static String withExpiry(long exp) {
        return plain(
                Map.of("exp", new Date(exp * 1000L), "iss", "untrusted-test-issuer", "aud", "untrusted-test-audience"));
    }

    public static String plain(Map<String, Object> claims) {
        try {
            var builder = new JWTClaimsSet.Builder();
            claims.forEach(builder::claim);
            return new PlainJWT(builder.build()).serialize();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to build test JWT", e);
        }
    }
}
