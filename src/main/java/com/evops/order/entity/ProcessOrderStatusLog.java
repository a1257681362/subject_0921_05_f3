package com.evops.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 加工单取镜状态流转日志（只追加）：
 * CREATE / START / FINISH / PICKUP / CANCEL / RESTORE。
 */
@Getter
@Setter
@TableName("t_process_order_status_log")
public class ProcessOrderStatusLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String operType;
    private String fromStatus;
    private String toStatus;
    private String operRemark;
    private Long operBy;
    private String requestNo;
    private LocalDateTime bizTime;
    private Long versionNo;
}
