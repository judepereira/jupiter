package com.judepereira.jupiter.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Setter
@Getter
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String apiKey;

}
