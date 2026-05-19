package com.wms.controller;

import com.wms.common.ApiResponse;
import com.wms.common.CursorPageResult;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InboundOrderResponse;
import com.wms.dto.InventoryResponse;
import com.wms.dto.OutboundOrderCreateRequest;
import com.wms.dto.OutboundOrderResponse;
import com.wms.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存和出入库单 Controller
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    /**
     * 创建入库单
     */
    @PostMapping("/inbound-orders")
    public ResponseEntity<ApiResponse<InboundOrderResponse>> createInboundOrder(
            @Valid @RequestBody InboundOrderCreateRequest request) {
        InboundOrderResponse response = inventoryService.createInboundOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(201, "入库单创建成功", response));
    }

    /**
     * 创建出库单
     */
    @PostMapping("/outbound-orders")
    public ResponseEntity<ApiResponse<OutboundOrderResponse>> createOutboundOrder(
            @Valid @RequestBody OutboundOrderCreateRequest request) {
        OutboundOrderResponse response = inventoryService.createOutboundOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(201, "出库单创建成功", response));
    }

    /**
     * 混合分页查询库存
     */
    @GetMapping("/inventory")
    public ApiResponse<CursorPageResult<InventoryResponse>> queryInventory(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String locationCode,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(
                inventoryService.queryInventory(keyword, warehouseId, locationCode, cursor, page, pageSize));
    }
}
