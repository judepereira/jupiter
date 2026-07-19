package com.judepereira.jupiter2.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "models.dev")
public class ModelCatalogProperties {

    private String catalogUrl = "https://models.dev/catalog.json";
}
