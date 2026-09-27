package com.evops.order.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.math.BigDecimal;

/**
 * 单眼处方。度数（球镜/柱镜/下加光）按 0.25 一档，在 Service 中统一校验。
 */
@Getter
@Setter
public class RxRequest {
    /** L 左眼 / R 右眼 */
    private String eye;
    /** 球镜 S */
    private BigDecimal sphere;
    /** 柱镜 C（散光） */
    private BigDecimal cylinder;
    /** 轴位 */
    @Min(value = 1, message = "轴位必须在 1 到 180 之间")
    @Max(value = 180, message = "轴位必须在 1 到 180 之间")
    private Integer axis;
    /** 下加光 ADD */
    private BigDecimal addPower;
    /** 瞳距 mm */
    @Min(value = 20, message = "瞳距必须在 20 到 90 之间")
    @Max(value = 90, message = "瞳距必须在 20 到 90 之间")
    private Integer pd;
}
