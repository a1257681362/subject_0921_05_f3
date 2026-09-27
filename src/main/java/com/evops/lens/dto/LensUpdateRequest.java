package com.evops.lens.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

@Getter
@Setter
public class LensUpdateRequest {
    @NotNull(message = "不能为空")
    private Long id;

    @Size(max = 128, message = "长度不能超过128")
    private String lensName;

    @Size(max = 64)
    private String brand;

    @Size(max = 32)
    private String lensType;

    @Size(max = 16)
    private String refractiveIndex;

    @DecimalMin(value = "0.00", message = "不能为负")
    private BigDecimal price;

    @Size(max = 255)
    private String remark;

    /** 乐观锁版本号，必传 */
    @NotNull(message = "不能为空")
    private Long versionNo;
}
