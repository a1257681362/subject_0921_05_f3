package com.evops.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 加工单主表。orderStatus：
 * CREATED 已建档 / PROCESSING 加工中 / READY 待取镜 / PICKED_UP 已取镜 / CANCELLED 已冲销。
 * 禁止物理删除；冲销以状态体现并写流转日志。
 */
@Getter
@Setter
@TableName("t_process_order")
public class ProcessOrder extends BaseEntity {
    private String orderNo;
    private String customerName;
    private String customerPhone;
    private String storeCode;
    private Long lensId;
    private String orderStatus;
    private String remark;
}
