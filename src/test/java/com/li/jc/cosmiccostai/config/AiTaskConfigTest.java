package com.li.jc.cosmiccostai.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTaskConfigTest {

    @Test
    void deepSplitExecutorRunsSeveralProjectsAtTheSameTime() throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new AiTaskConfig().deepSplitExecutor(32, 50);
        CountDownLatch started = new CountDownLatch(12);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 12; i++) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        started.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
            assertTrue(started.await(2, TimeUnit.SECONDS),
                    "human-triggered project jobs should start together without entering the queue");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
