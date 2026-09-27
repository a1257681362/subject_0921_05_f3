package com.evops.lens.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 镜片操作记录（只追加）：CREATE / UPDATE / DISCARD / RESTORE。
 */
@Getter
@Setter
@TableName("t_lens_oper_log")
public class LensOperLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long lensId;
    private String operType;
    private String operRemark;
    private Long operBy;
    private String requestNo;
    private LocalDateTime bizTime;
    private Long versionNo;
}
