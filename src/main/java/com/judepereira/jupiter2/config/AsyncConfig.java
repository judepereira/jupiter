package com.judepereira.jupiter2.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "agentTaskExecutor")
    public Executor agentTaskExecutor() {
        ThreadPoolTaskExecutor t = new ThreadPoolTaskExecutor();
        t.setCorePoolSize(2);
        t.setMaxPoolSize(4);
        t.setThreadNamePrefix("agent-exec-");
        t.setDaemon(true);
        t.initialize();
        return t;
    }
}
