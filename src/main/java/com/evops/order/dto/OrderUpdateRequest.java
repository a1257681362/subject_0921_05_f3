package com.evops.order.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

/**
 * 加工单修改：仅 CREATED 状态可改。处方整组替换（旧行置 VOID，新行 ACTIVE），
 * 与主表同一事务；任一处方不合法则全部不见。
 */
@Getter
@Setter
public class OrderUpdateRequest {
    @NotNull(message = "加工单ID不能为空")
    private Long id;

    @Size(max = 64)
    private String customerName;

    @Size(max = 32)
    private String customerPhone;

    @Size(max = 32)
    private String storeCode;

    private Long lensId;

    @Size(max = 255)
    private String remark;

    @Valid
    @NotNull(message = "左右眼处方不能为空")
    @Size(min = 2, max = 2, message = "必须且只能包含左右眼两条处方")
    private List<RxRequest> prescriptions;

    @NotNull(message = "版本号不能为空")
    private Long versionNo;
}
