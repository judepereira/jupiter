package com.judepereira.jupiter;

import com.judepereira.jupiter.agent.catalog.ModelCatalogService;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Import(JupiterTests.TestCatalogConfiguration.class)
class JupiterTests {

    @Test
    void contextLoads() {
    }

    @TestConfiguration
    static class TestCatalogConfiguration {

        @Bean(name = "modelCatalogService")
        ModelCatalogService modelCatalogService() {
            ModelCatalogService delegate = ModelCatalogTestSupport.modelCatalogService();
            ModelCatalogService fake = Mockito.mock(ModelCatalogService.class);
            when(fake.list()).thenReturn(delegate.list());
            when(fake.defaultModelId()).thenReturn(delegate.defaultModelId());
            when(fake.getRequired(any())).thenAnswer(invocation -> delegate.getRequired(invocation.getArgument(0)));
            when(fake.resolveOrDefault(any())).thenAnswer(invocation -> delegate.resolveOrDefault(invocation.getArgument(0)));
            return fake;
        }
    }
}
