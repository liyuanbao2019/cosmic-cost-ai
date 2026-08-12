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
            @Value("${cosmic.ai.deep-split.worker-count:32}") int workerCount,
            @Value("${cosmic.ai.deep-split.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = Math.max(1, workerCount);
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("deep-split-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "batchSplitExecutor")
    public TaskExecutor batchSplitExecutor(
            @Value("${cosmic.ai.batch-split.worker-count:24}") int workerCount,
            @Value("${cosmic.ai.batch-split.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = Math.max(1, workerCount);
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("batch-split-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "batchPreparationExecutor")
    public TaskExecutor batchPreparationExecutor(
            @Value("${cosmic.ai.batch-split.preparation-worker-count:4}") int workerCount,
            @Value("${cosmic.ai.batch-split.preparation-queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = Math.max(1, workerCount);
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("batch-prepare-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
