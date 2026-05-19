package com.wms.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出库单创建响应
 */
@Data
@Builder
public class OutboundOrderResponse {

    private Long id;
    private String orderNo;
    private String customerName;
    private String status;
    private List<OutboundOrderItemResponse> items;
    private LocalDateTime createdAt;

    /**
     * 出库单明细响应
     */
    @Data
    @Builder
    public static class OutboundOrderItemResponse {
        private Long productId;
        private String productName;
        private Integer quantity;
        private String locationCode;
    }
}
