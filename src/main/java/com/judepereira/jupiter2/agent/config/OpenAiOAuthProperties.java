package com.judepereira.jupiter2.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "openai.oauth")
public class OpenAiOAuthProperties {

    private String issuer = "https://auth.openai.com";
    private String clientId = "app_EMoamEEZ73f0CkXaXp7hrann";
    private String deviceUserCodeUrl;
    private String deviceTokenUrl;
    private String tokenUrl;
    private String verificationUrl;
}
