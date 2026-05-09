package com.oreki.cas_injector.core.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "mathEngineExecutor")
    public Executor taskExecutor() {
        org.springframework.core.task.SimpleAsyncTaskExecutor executor = new org.springframework.core.task.SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);
        executor.setThreadNamePrefix("math-engine-vt-");
        return executor;
    }
}
