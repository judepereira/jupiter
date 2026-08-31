package com.judepereira.jupiter.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.ModelCatalogService;
import com.judepereira.jupiter.agent.config.ModelCatalogProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

public final class ModelCatalogTestSupport {

    public static final String OPENAI_CATALOG_JSON = """
            {
              "models": {
                "openai/gpt-5.5": {
                  "id": "openai/gpt-5.5",
                  "name": "GPT-5.5",
                  "reasoning": true,
                  "tool_call": true,
                  "release_date": "2026-04-23",
                  "limit": {
                    "context": 1050000,
                    "output": 128000
                  }
                },
                "openai/gpt-4.1": {
                  "id": "openai/gpt-4.1",
                  "name": "GPT-4.1",
                  "reasoning": true,
                  "tool_call": true,
                  "release_date": "2025-04-14",
                  "limit": {
                    "context": 128000,
                    "output": 16000
                  }
                },
                "anthropic/claude-opus-4": {
                  "id": "anthropic/claude-opus-4",
                  "name": "Claude Opus 4",
                  "reasoning": true,
                  "tool_call": true,
                  "release_date": "2026-02-15",
                  "limit": {
                    "context": 200000,
                    "output": 32000
                  }
                },
                "openai/gpt-5.5-pro": {
                  "id": "openai/gpt-5.5-pro",
                  "name": "GPT-5.5 Pro",
                  "reasoning": true,
                  "tool_call": true,
                  "release_date": "2026-05-10",
                  "limit": {
                    "context": 1050000,
                    "output": 128000
                  }
                },
                "openai/gpt-5.6-sol": {
                  "id": "openai/gpt-5.6-sol",
                  "name": "GPT-5.6 Sol",
                  "reasoning": true,
                  "tool_call": true,
                  "release_date": "2026-05-10",
                  "limit": {
                    "context": 1050000,
                    "output": 128000
                  }
                },
                "openai/gpt-5.6-terra": {
                  "id": "openai/gpt-5.6-terra",
                  "name": "GPT-5.6 Terra",
                  "reasoning": true,
                  "tool_call": true,
                  "release_date": "2026-05-10",
                  "limit": {
                    "context": 1050000,
                    "output": 128000
                  }
                },
                "openai/gpt-5.6-luna": {
                  "id": "openai/gpt-5.6-luna",
                  "name": "GPT-5.6 Luna",
                  "reasoning": true,
                  "tool_call": true,
                  "release_date": "2026-05-10",
                  "limit": {
                    "context": 1050000,
                    "output": 128000
                  }
                }
              }
            }
            """;

    private static final String DEFAULT_URL = "https://models.dev/catalog.json";

    private ModelCatalogTestSupport() {
    }

    public static ModelCatalogService modelCatalogService() {
        return modelCatalogService(DEFAULT_URL, OPENAI_CATALOG_JSON);
    }

    public static ModelCatalogService modelCatalogService(String catalogUrl, String json) {
        ModelCatalogProperties properties = new ModelCatalogProperties();
        properties.setCatalogUrl(catalogUrl);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(catalogUrl))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        ModelCatalogService service = new ModelCatalogService(new ObjectMapper(), builder, properties);
        server.verify();
        return service;
    }
}
