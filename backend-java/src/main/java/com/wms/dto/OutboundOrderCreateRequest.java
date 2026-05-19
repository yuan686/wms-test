package com.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

/**
 * 出库单创建请求
 */
@Data
public class OutboundOrderCreateRequest {

    @NotEmpty(message = "客户名称不能为空")
    private String customerName;

    @Valid
    @NotEmpty(message = "出库明细不能为空")
    private List<OutboundItemRequest> items;

    /**
     * 出库单明细请求
     */
    @Data
    public static class OutboundItemRequest {

        @NotNull(message = "商品ID不能为空")
        private Long productId;

        @NotNull(message = "数量不能为空")
        @Positive(message = "数量必须大于0")
        private Integer quantity;

        @NotEmpty(message = "库位编码不能为空")
        private String locationCode;
    }
}
