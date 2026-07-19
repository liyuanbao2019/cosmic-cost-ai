package com.li.jc.cosmiccostai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AiTaskConfig {

    @Bean(name = "deepSplitExecutor")
    public TaskExecutor deepSplitExecutor(
            @Value("${cosmic.ai.deep-split.worker-count:2}") int workerCount) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = Math.max(1, workerCount);
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("deep-split-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
