package com.evops.task;

import java.util.List;

/**
 * 任务项规划产物：处理对象标识 + 目标 id（排序/游标用）。
 */
public class TaskItemSeed {
    private final String itemRef;
    private final Long targetId;

    public TaskItemSeed(String itemRef, Long targetId) {
        this.itemRef = itemRef;
        this.targetId = targetId;
    }

    public String getItemRef() { return itemRef; }
    public Long getTargetId() { return targetId; }
}
