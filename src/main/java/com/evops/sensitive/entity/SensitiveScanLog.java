package com.evops.sensitive.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 加工单文本敏感词扫描留痕（只追加）。每次扫描按加工单按字段留一行，
 * 含无命中的 PASS——反向核对以 t_process_order 为准时据此确认每单都扫过。
 * action：PASS 无命中 / MASK 打码放行 / BLOCK 拦截 / RECHECK 反向核对快照。
 */
@Getter
@Setter
@TableName("t_sensitive_scan_log")
public class SensitiveScanLog extends BaseEntity {
    /** 被拦截（BLOCK）时加工单未建档，可空；核对与展示按 order_no 关联 */
    private Long orderId;
    private String orderNo;
    /** customerName / remark */
    private String fieldName;
    /** PASS / MASK / BLOCK / RECHECK */
    private String action;
    private Integer hitCount;
    /** JSON：[{"word","level","start","end"}]，原文坐标 */
    private String hitDetail;
    /** 扫描时词库版本（内容 MD5 短串） */
    private String dictVersion;
    /** CREATE / UPDATE / RECHECK */
    private String operType;
}
