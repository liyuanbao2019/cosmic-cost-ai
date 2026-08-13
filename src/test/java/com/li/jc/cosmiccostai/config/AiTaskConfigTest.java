package com.li.jc.cosmiccostai.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTaskConfigTest {

    @Test
    void batchSplitExecutorCanStartFiftyProjectsWithoutApplicationQueueing() throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new AiTaskConfig().batchSplitExecutor(50, 100);
        CountDownLatch started = new CountDownLatch(50);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 50; i++) {
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
            assertTrue(started.await(5, TimeUnit.SECONDS),
                    "fifty confirmed documents should enter split execution without application queueing");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void batchPreparationExecutorUsesIndependentConfiguredParallelism() throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new AiTaskConfig().batchPreparationExecutor(4, 20);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
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
                    "document preparation workers should run independently from AI split workers");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

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
