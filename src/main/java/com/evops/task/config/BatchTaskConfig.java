package com.evops.task.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 后台批量任务基础设施：独立线程池（不占请求线程）+ 定时调度（保留期清理）。
 * 任务在 batchTaskExecutor 上异步执行；拒绝策略 CALLER_RUNS + 有界队列防止任务堆积打爆内存。
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(BatchTaskProperties.class)
public class BatchTaskConfig {

    public static final String EXECUTOR = "batchTaskExecutorPool";

    @Bean(name = EXECUTOR, destroyMethod = "shutdown")
    public Executor batchTaskExecutor(BatchTaskProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getCoreSize());
        executor.setMaxPoolSize(Math.max(1, props.getCoreSize()));
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("batch-task-");
        // 队列满时退化为调用方线程执行，形成背压，不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
