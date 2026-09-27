package com.evops.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 加工单处方明细（左右眼各一行）。编辑时旧行置 VOID、新行 ACTIVE，不物理删除。
 */
@Getter
@Setter
@TableName("t_process_order_rx")
public class ProcessOrderRx extends BaseEntity {
    private Long orderId;
    /** 眼别：L 左眼 / R 右眼 */
    private String eye;
    /** 球镜 S（度数，0.25 一档） */
    private BigDecimal sphere;
    /** 柱镜 C（散光，0.25 一档） */
    private BigDecimal cylinder;
    /** 轴位 1~180 */
    private Integer axis;
    /** 下加光 ADD（0.25 一档） */
    private BigDecimal addPower;
    /** 瞳距 mm */
    private Integer pd;
    /** ACTIVE / VOID */
    private String recordStatus;
}
