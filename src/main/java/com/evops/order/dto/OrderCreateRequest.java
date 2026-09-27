package com.evops.order.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

/**
 * 加工单建档。左右眼处方必须各一条，主子表同一事务，要么全部生效要么全部不见。
 */
@Getter
@Setter
public class OrderCreateRequest {
    @NotBlank(message = "加工单号不能为空")
    @Size(max = 64)
    private String orderNo;

    @Size(max = 64)
    private String customerName;

    @Size(max = 32)
    private String customerPhone;

    @Size(max = 32)
    private String storeCode;

    @NotNull(message = "镜片不能为空")
    private Long lensId;

    @Size(max = 255)
    private String remark;

    @Valid
    @NotNull(message = "左右眼处方不能为空")
    @Size(min = 2, max = 2, message = "必须且只能包含左右眼两条处方")
    private List<RxRequest> prescriptions;
}
