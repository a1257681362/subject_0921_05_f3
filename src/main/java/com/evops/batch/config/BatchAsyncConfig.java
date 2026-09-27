package com.evops.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 批量任务异步执行与定时清理开关。
 * 批量任务串行更安全（全量核对不并发打数据库/词库）：core=max=1，
 * 多出的任务在队列排队；线程满用 AbortPolicy（配合提交时的"已有活动任务"拦截，正常不会触发）。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class BatchAsyncConfig {

    @Bean(name = "batchJobExecutor")
    public ThreadPoolTaskExecutor batchJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("batch-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
