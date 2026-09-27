package com.evops.task;

/**
 * 批量任务状态。
 * <pre>
 * PENDING ──启动──▶ RUNNING ──全部成功──▶ SUCCESS
 *                      │                    │
 *                      ├─ 有失败项 ─▶ FAILED ┤（FAILED 可重跑 → RUNNING）
 *                      │                    │
 *                      └─收到取消─▶ CANCEL_REQUESTED ─当前批收尾─▶ CANCELLED
 * 终态（SUCCESS/FAILED/CANCELLED）超过保留期（默认 21 天）由定时清理置 ARCHIVED（不物理删除）
 * </pre>
 */
public final class TaskStatus {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String ARCHIVED = "ARCHIVED";

    private TaskStatus() {}

    /** 任务是否已结束（成功/失败/取消；归档是清理态，也算终态） */
    public static boolean isFinished(String status) {
        return SUCCESS.equals(status) || FAILED.equals(status)
                || CANCELLED.equals(status) || ARCHIVED.equals(status);
    }

    /** 取消请求后，当前批处理收尾即落 CANCELLED；只有这些态允许请求取消 */
    public static boolean isCancellable(String status) {
        return PENDING.equals(status) || RUNNING.equals(status) || FAILED.equals(status);
    }

    /** 仅这些态允许断点重跑 */
    public static boolean isRunnable(String status) {
        return PENDING.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }
}
