package com.judepereira.jupiter.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "jupiter.http-auth")
public class HttpAuthProperties {

    private String password = "";
    private String username = "jupiter";

    public boolean enabled() {
        return password != null && !password.isBlank();
    }
}
