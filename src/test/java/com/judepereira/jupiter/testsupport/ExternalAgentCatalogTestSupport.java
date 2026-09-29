package com.judepereira.jupiter.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.ExternalAgentCatalogService;
import java.nio.file.Path;

public final class ExternalAgentCatalogTestSupport {
    private ExternalAgentCatalogTestSupport() {
    }

    public static ExternalAgentCatalogService service(AgentDefinitionService definitions, Path home) {
        return new ExternalAgentCatalogService(definitions, ModelCatalogTestSupport.modelCatalogService(),
                home.toString());
    }

    public static ExternalAgentCatalogService bundled(Path home) {
        return service(new AgentDefinitionService(new ObjectMapper()), home);
    }
}
