package com.evops.task.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后台批量任务配置（evops.batch）。
 */
@ConfigurationProperties(prefix = "evops.batch")
public class BatchTaskProperties {

    /** 后台任务线程池大小（并发执行的任务数；单任务内部仍逐项串行） */
    private int coreSize = 2;

    /** 任务记录保留天数；终态任务超过该天数转 ARCHIVED（不物理删除） */
    private int keepDays = 21;

    /** 终态任务归档清理的 cron（默认每天 02:30 跑一次） */
    private String cleanupCron = "0 30 2 * * ?";

    /** 单次从库里捞取的待处理项数量；也是取消请求的响应粒度（每批之间检查取消） */
    private int itemChunk = 100;

    public int getCoreSize() { return coreSize; }
    public void setCoreSize(int coreSize) { this.coreSize = coreSize; }
    public int getKeepDays() { return keepDays; }
    public void setKeepDays(int keepDays) { this.keepDays = keepDays; }
    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
    public int getItemChunk() { return itemChunk; }
    public void setItemChunk(int itemChunk) { this.itemChunk = itemChunk; }
}
