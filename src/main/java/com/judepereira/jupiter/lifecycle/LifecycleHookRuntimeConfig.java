package com.judepereira.jupiter.lifecycle;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.Executors;

@Configuration
public class LifecycleHookRuntimeConfig {

    @Bean
    LifecycleHookRuntime lifecycleHookRuntime() {
        return new LifecycleHookRuntime(Executors.newVirtualThreadPerTaskExecutor(),
                LifecycleHookService::startProcess, Path.of("/tmp"));
    }
}
