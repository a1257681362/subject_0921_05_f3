package com.evops.lens.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 镜片基础信息。lens_status=ACTIVE/DISCARDED，禁止物理删除。
 */
@Getter
@Setter
@TableName("t_lens")
public class Lens extends BaseEntity {
    /** 镜片编码，全库唯一 */
    private String lensCode;
    /** 镜片名称 */
    private String lensName;
    /** 品牌 */
    private String brand;
    /** 品类（单光/渐进/变色等） */
    private String lensType;
    /** 折射率 */
    private String refractiveIndex;
    /** 挂牌价 */
    private BigDecimal price;
    /** ACTIVE / DISCARDED */
    private String lensStatus;
    private String remark;
}
