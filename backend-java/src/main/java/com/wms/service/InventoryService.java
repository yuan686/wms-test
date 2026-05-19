package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InboundOrderResponse;
import com.wms.dto.InventoryResponse;
import com.wms.entity.InboundOrder;
import com.wms.entity.InboundOrderItem;
import com.wms.entity.Inventory;
import com.wms.entity.Product;
import com.wms.repository.InboundOrderItemRepository;
import com.wms.repository.InboundOrderRepository;
import com.wms.repository.InventoryRepository;
import com.wms.repository.LocationRepository;
import com.wms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存业务 Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final DateTimeFormatter ORDER_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final InventoryRepository inventoryRepository;
    private final InboundOrderRepository inboundOrderRepository;
    private final InboundOrderItemRepository inboundOrderItemRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;

    /**
     * 创建入库单并累加入库库存
     */
    @Transactional
    public InboundOrderResponse createInboundOrder(InboundOrderCreateRequest request) {
        validateInboundRequest(request);
        String orderNo = generateInboundOrderNo();
        InboundOrder order = saveInboundOrder(request, orderNo);
        List<InboundOrderResponse.InboundOrderItemResponse> itemResponses = saveItemsAndInventory(order, request);
        log.info("创建入库单成功: orderNo={}, itemCount={}", orderNo, itemResponses.size());
        return buildInboundOrderResponse(order, itemResponses);
    }

    /**
     * 库存查询
     */
    public List<InventoryResponse> queryInventory(String keyword, Long warehouseId,
                                                   int page, int pageSize) {
        throw new UnsupportedOperationException("请实现库存查询功能（任务2）");
    }

    /**
     * 校验入库单请求基础参数
     */
    private void validateInboundRequest(InboundOrderCreateRequest request) {
        if (request == null) {
            throw new BusinessException("入库单请求不能为空");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("入库明细不能为空");
        }
    }

    /**
     * 生成入库单号
     */
    private String generateInboundOrderNo() {
        String prefix = "IN-" + LocalDate.now().format(ORDER_DATE_FORMAT) + "-";
        int nextSequence = inboundOrderRepository.findTopByOrderNoStartingWithOrderByOrderNoDesc(prefix)
                .map(order -> parseOrderSequence(order.getOrderNo()) + 1)
                .orElse(1);
        return prefix + String.format("%03d", nextSequence);
    }

    /**
     * 解析入库单号中的序号
     */
    private int parseOrderSequence(String orderNo) {
        if (orderNo == null || orderNo.length() < 3) {
            return 0;
        }
        try {
            return Integer.parseInt(orderNo.substring(orderNo.length() - 3));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 保存入库单主表
     */
    private InboundOrder saveInboundOrder(InboundOrderCreateRequest request, String orderNo) {
        InboundOrder order = InboundOrder.builder()
                .orderNo(orderNo)
                .supplierName(request.getSupplierName().trim())
                .status("COMPLETED")
                .build();
        return inboundOrderRepository.save(order);
    }

    /**
     * 保存入库明细并同步累加库存
     */
    private List<InboundOrderResponse.InboundOrderItemResponse> saveItemsAndInventory(
            InboundOrder order, InboundOrderCreateRequest request) {
        List<InboundOrderResponse.InboundOrderItemResponse> responses = new ArrayList<>();
        for (InboundOrderCreateRequest.InboundItemRequest item : request.getItems()) {
            Product product = findProduct(item.getProductId());
            String locationCode = validateLocation(item.getLocationCode());
            Integer quantity = validateQuantity(item.getQuantity());
            saveInboundItem(order.getId(), product.getId(), quantity, locationCode);
            increaseInventory(product.getId(), quantity, locationCode);
            responses.add(buildItemResponse(product, quantity, locationCode));
        }
        return responses;
    }

    /**
     * 查询并校验商品存在
     */
    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(404, "商品不存在: " + productId));
    }

    /**
     * 校验库位存在并返回标准库位编码
     */
    private String validateLocation(String locationCode) {
        String trimmedCode = locationCode == null ? "" : locationCode.trim();
        locationRepository.findByCode(trimmedCode)
                .orElseThrow(() -> new BusinessException(404, "库位不存在: " + trimmedCode));
        return trimmedCode;
    }

    /**
     * 校验入库数量
     */
    private Integer validateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("数量必须大于0");
        }
        return quantity;
    }

    /**
     * 保存入库单明细
     */
    private void saveInboundItem(Long orderId, Long productId, Integer quantity, String locationCode) {
        InboundOrderItem item = InboundOrderItem.builder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .locationCode(locationCode)
                .build();
        inboundOrderItemRepository.save(item);
    }

    /**
     * 累加指定商品和库位的库存
     */
    private void increaseInventory(Long productId, Integer quantity, String locationCode) {
        Inventory inventory = inventoryRepository.findByProductIdAndLocationCode(productId, locationCode)
                .orElseGet(() -> Inventory.builder()
                        .productId(productId)
                        .locationCode(locationCode)
                        .quantity(0)
                        .build());
        inventory.setQuantity(calculateNewQuantity(inventory.getQuantity(), quantity));
        inventoryRepository.save(inventory);
    }

    /**
     * 计算库存累加后的数量并防止整数溢出
     */
    private Integer calculateNewQuantity(Integer currentQuantity, Integer inboundQuantity) {
        long newQuantity = (long) (currentQuantity == null ? 0 : currentQuantity) + inboundQuantity;
        if (newQuantity > Integer.MAX_VALUE) {
            throw new BusinessException("库存数量超过系统上限");
        }
        return (int) newQuantity;
    }

    /**
     * 构建入库单明细响应
     */
    private InboundOrderResponse.InboundOrderItemResponse buildItemResponse(
            Product product, Integer quantity, String locationCode) {
        return InboundOrderResponse.InboundOrderItemResponse.builder()
                .productId(product.getId())
                .productName(product.getName())
                .quantity(quantity)
                .locationCode(locationCode)
                .build();
    }

    /**
     * 构建入库单响应
     */
    private InboundOrderResponse buildInboundOrderResponse(
            InboundOrder order, List<InboundOrderResponse.InboundOrderItemResponse> items) {
        return InboundOrderResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .supplierName(order.getSupplierName())
                .status(order.getStatus())
                .items(items)
                .createdAt(order.getCreatedAt())
                .build();
    }
}
