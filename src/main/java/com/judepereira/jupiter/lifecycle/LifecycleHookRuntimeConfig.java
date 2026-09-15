package com.judepereira.jupiter.lifecycle;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LifecycleHookRuntimeConfig {

    @Bean
    LifecycleHookRuntime lifecycleHookRuntime() {
        return new LifecycleHookRuntime(Executors.newVirtualThreadPerTaskExecutor(), LifecycleHookService::startProcess,
                Path.of("/tmp"));
    }
}
