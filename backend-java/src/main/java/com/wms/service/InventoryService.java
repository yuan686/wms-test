package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.common.CursorPageResult;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InboundOrderResponse;
import com.wms.dto.InventoryResponse;
import com.wms.dto.OutboundOrderCreateRequest;
import com.wms.dto.OutboundOrderResponse;
import com.wms.entity.InboundOrder;
import com.wms.entity.InboundOrderItem;
import com.wms.entity.Inventory;
import com.wms.entity.OutboundOrder;
import com.wms.entity.OutboundOrderItem;
import com.wms.entity.Product;
import com.wms.repository.InboundOrderItemRepository;
import com.wms.repository.InboundOrderRepository;
import com.wms.repository.InventoryRepository;
import com.wms.repository.LocationRepository;
import com.wms.repository.OutboundOrderItemRepository;
import com.wms.repository.OutboundOrderRepository;
import com.wms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存业务 Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final DateTimeFormatter ORDER_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final long LUA_STOCK_MISSING = -2L;
    private static final long LUA_STOCK_NOT_ENOUGH = -1L;
    private static final String STOCK_KEY_PREFIX = "inventory:stock:";
    private static final String STOCK_DEDUCT_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            if not current then
                return -2
            end
            local stock = tonumber(current)
            local quantity = tonumber(ARGV[1])
            if stock < quantity then
                return -1
            end
            return redis.call('DECRBY', KEYS[1], quantity)
            """;

    private final InventoryRepository inventoryRepository;
    private final InboundOrderRepository inboundOrderRepository;
    private final InboundOrderItemRepository inboundOrderItemRepository;
    private final OutboundOrderRepository outboundOrderRepository;
    private final OutboundOrderItemRepository outboundOrderItemRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboundApplyCacheService outboundApplyCacheService;

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
     * 创建出库单并通过 Redis Lua 预扣减库存
     */
    public OutboundOrderResponse createOutboundOrder(OutboundOrderCreateRequest request) {
        validateOutboundRequest(request);
        List<PreparedOutboundItem> items = buildPreparedOutboundItems(request);
        List<StockDeduction> deductions = new ArrayList<>();
        try {
            preDeductStock(items, deductions);
            return saveOutboundOrderAfterPreDeduction(request, items);
        } catch (RuntimeException e) {
            rollbackRedisDeduction(deductions);
            throw e;
        }
    }

    /**
     * 混合分页查询库存
     */
    public CursorPageResult<InventoryResponse> queryInventory(String keyword, Long warehouseId,
                                                              String locationCode, Long cursor,
                                                              int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        Long safeCursor = normalizeCursor(cursor);
        return inventoryRepository.queryInventory(
                keyword, warehouseId, locationCode, safeCursor, safePage, safePageSize);
    }

    /**
     * 校验游标合法性
     */
    private Long normalizeCursor(Long cursor) {
        if (cursor == null || cursor < 0) {
            return null;
        }
        return cursor;
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
     * 校验出库单请求基础参数
     */
    private void validateOutboundRequest(OutboundOrderCreateRequest request) {
        if (request == null) {
            throw new BusinessException("出库单请求不能为空");
        }
        if (request.getCustomerName() == null || request.getCustomerName().trim().isEmpty()) {
            throw new BusinessException("客户名称不能为空");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("出库明细不能为空");
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
     * 生成出库单号
     */
    private String generateOutboundOrderNo() {
        String prefix = "OUT-" + LocalDate.now().format(ORDER_DATE_FORMAT) + "-";
        int nextSequence = outboundOrderRepository.findTopByOrderNoStartingWithOrderByOrderNoDesc(prefix)
                .map(order -> parseOrderSequence(order.getOrderNo()) + 1)
                .orElse(1);
        return prefix + String.format("%03d", nextSequence);
    }

    /**
     * 解析单号中的序号
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
            Product product = findProduct(validateProductId(item.getProductId()));
            String locationCode = validateLocation(item.getLocationCode());
            Integer quantity = validateQuantity(item.getQuantity());
            saveInboundItem(order.getId(), product.getId(), quantity, locationCode);
            increaseInventory(product.getId(), quantity, locationCode);
            responses.add(buildInboundItemResponse(product, quantity, locationCode));
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
     * 校验数量为正整数
     */
    private Integer validateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("数量必须大于0");
        }
        return quantity;
    }

    /**
     * 校验商品 ID 不为空
     */
    private Long validateProductId(Long productId) {
        if (productId == null) {
            throw new BusinessException("商品ID不能为空");
        }
        return productId;
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
        outboundApplyCacheService.evictStockCache(buildRedisStockKey(productId, locationCode));
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
     * 合并同商品同库位的出库明细
     */
    private List<PreparedOutboundItem> buildPreparedOutboundItems(OutboundOrderCreateRequest request) {
        Map<String, PreparedOutboundItem> itemMap = new LinkedHashMap<>();
        for (OutboundOrderCreateRequest.OutboundItemRequest item : request.getItems()) {
            Product product = outboundApplyCacheService.requireProduct(validateProductId(item.getProductId()));
            String locationCode = outboundApplyCacheService.requireLocationCode(item.getLocationCode());
            Integer quantity = validateQuantity(item.getQuantity());
            String itemKey = buildStockIdentity(product.getId(), locationCode);
            mergePreparedOutboundItem(itemMap, itemKey, product, locationCode, quantity);
        }
        return new ArrayList<>(itemMap.values());
    }

    /**
     * 合并单行出库明细并检查数量溢出
     */
    private void mergePreparedOutboundItem(Map<String, PreparedOutboundItem> itemMap, String itemKey,
                                           Product product, String locationCode, Integer quantity) {
        PreparedOutboundItem currentItem = itemMap.get(itemKey);
        if (currentItem == null) {
            itemMap.put(itemKey, new PreparedOutboundItem(product, locationCode, quantity));
            return;
        }
        int mergedQuantity = calculateNewQuantity(currentItem.quantity(), quantity);
        itemMap.put(itemKey, new PreparedOutboundItem(product, locationCode, mergedQuantity));
    }

    /**
     * 对所有出库明细执行 Redis 预扣减
     */
    private void preDeductStock(List<PreparedOutboundItem> items, List<StockDeduction> deductions) {
        for (PreparedOutboundItem item : items) {
            preDeductSingleStock(item, deductions);
        }
    }

    /**
     * 对单个商品库位执行 Redis Lua 原子预扣减
     */
    private void preDeductSingleStock(PreparedOutboundItem item, List<StockDeduction> deductions) {
        String stockKey = buildRedisStockKey(item.product().getId(), item.locationCode());
        long result = executeDeductScript(stockKey, item.quantity());
        if (result == LUA_STOCK_MISSING) {
            outboundApplyCacheService.ensureStockInitialized(
                    stockKey, item.product().getId(), item.locationCode());
            result = executeDeductScript(stockKey, item.quantity());
        }
        handleDeductResult(item, deductions, result);
    }

    /**
     * 执行 Redis Lua 库存预扣减脚本
     */
    private long executeDeductScript(String stockKey, Integer quantity) {
        RedisScript<Long> script = RedisScript.of(STOCK_DEDUCT_SCRIPT, Long.class);
        Long result = stringRedisTemplate.execute(script, List.of(stockKey), quantity.toString());
        if (result == null) {
            throw new BusinessException("库存预扣减失败");
        }
        return result;
    }

    /**
     * 处理 Redis 预扣减结果并记录成功扣减项
     */
    private void handleDeductResult(PreparedOutboundItem item, List<StockDeduction> deductions, long result) {
        if (result == LUA_STOCK_MISSING) {
            throw new BusinessException("库存初始化失败，请稍后重试");
        }
        if (result == LUA_STOCK_NOT_ENOUGH) {
            throw new BusinessException("库存不足: " + item.product().getName() + " / " + item.locationCode());
        }
        deductions.add(new StockDeduction(item.product().getId(), item.locationCode(), item.quantity()));
    }

    /**
     * 回滚已经完成的 Redis 预扣减
     */
    private void rollbackRedisDeduction(List<StockDeduction> deductions) {
        for (StockDeduction deduction : deductions) {
            String stockKey = buildRedisStockKey(deduction.productId(), deduction.locationCode());
            stringRedisTemplate.opsForValue().increment(stockKey, deduction.quantity());
        }
    }

    /**
     * Redis 预扣成功后在数据库事务内创建出库单
     */
    private OutboundOrderResponse saveOutboundOrderAfterPreDeduction(OutboundOrderCreateRequest request,
                                                                     List<PreparedOutboundItem> items) {
        OutboundOrderResponse response = transactionTemplate.execute(status -> saveOutboundOrderInDatabase(request, items));
        if (response == null) {
            throw new BusinessException("出库单创建失败");
        }
        log.info("创建出库单成功: orderNo={}, itemCount={}", response.getOrderNo(), items.size());
        return response;
    }

    /**
     * 保存出库单并扣减数据库库存
     */
    private OutboundOrderResponse saveOutboundOrderInDatabase(OutboundOrderCreateRequest request,
                                                              List<PreparedOutboundItem> items) {
        String orderNo = generateOutboundOrderNo();
        OutboundOrder order = saveOutboundOrder(request, orderNo);
        List<OutboundOrderResponse.OutboundOrderItemResponse> itemResponses =
                saveOutboundItemsAndDecreaseInventory(order, items);
        return buildOutboundOrderResponse(order, itemResponses);
    }

    /**
     * 保存出库单主表
     */
    private OutboundOrder saveOutboundOrder(OutboundOrderCreateRequest request, String orderNo) {
        OutboundOrder order = OutboundOrder.builder()
                .orderNo(orderNo)
                .customerName(request.getCustomerName().trim())
                .status("COMPLETED")
                .build();
        return outboundOrderRepository.save(order);
    }

    /**
     * 保存出库明细并同步扣减数据库库存
     */
    private List<OutboundOrderResponse.OutboundOrderItemResponse> saveOutboundItemsAndDecreaseInventory(
            OutboundOrder order, List<PreparedOutboundItem> items) {
        List<OutboundOrderResponse.OutboundOrderItemResponse> responses = new ArrayList<>();
        for (PreparedOutboundItem item : items) {
            saveOutboundItem(order.getId(), item);
            decreaseInventoryInDatabase(item);
            responses.add(buildOutboundItemResponse(item));
        }
        return responses;
    }

    /**
     * 保存出库单明细
     */
    private void saveOutboundItem(Long orderId, PreparedOutboundItem item) {
        OutboundOrderItem orderItem = OutboundOrderItem.builder()
                .orderId(orderId)
                .productId(item.product().getId())
                .quantity(item.quantity())
                .locationCode(item.locationCode())
                .build();
        outboundOrderItemRepository.save(orderItem);
    }

    /**
     * 使用数据库条件更新扣减库存
     */
    private void decreaseInventoryInDatabase(PreparedOutboundItem item) {
        int updatedRows = inventoryRepository.decreaseWhenEnough(
                item.product().getId(), item.locationCode(), item.quantity());
        if (updatedRows != 1) {
            throw new BusinessException("库存不足: " + item.product().getName() + " / " + item.locationCode());
        }
    }

    /**
     * 构建入库单明细响应
     */
    private InboundOrderResponse.InboundOrderItemResponse buildInboundItemResponse(
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

    /**
     * 构建出库单明细响应
     */
    private OutboundOrderResponse.OutboundOrderItemResponse buildOutboundItemResponse(PreparedOutboundItem item) {
        return OutboundOrderResponse.OutboundOrderItemResponse.builder()
                .productId(item.product().getId())
                .productName(item.product().getName())
                .quantity(item.quantity())
                .locationCode(item.locationCode())
                .build();
    }

    /**
     * 构建出库单响应
     */
    private OutboundOrderResponse buildOutboundOrderResponse(
            OutboundOrder order, List<OutboundOrderResponse.OutboundOrderItemResponse> items) {
        return OutboundOrderResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .customerName(order.getCustomerName())
                .status(order.getStatus())
                .items(items)
                .createdAt(order.getCreatedAt())
                .build();
    }

    /**
     * 构建库存业务唯一标识
     */
    private String buildStockIdentity(Long productId, String locationCode) {
        return productId + "|" + locationCode;
    }

    /**
     * 构建 Redis 库存 key
     */
    private String buildRedisStockKey(Long productId, String locationCode) {
        return STOCK_KEY_PREFIX + productId + ":" + locationCode;
    }

    /**
     * 出库前完成校验和合并的明细
     */
    private record PreparedOutboundItem(Product product, String locationCode, Integer quantity) {
    }

    /**
     * 已完成 Redis 预扣减的库存记录
     */
    private record StockDeduction(Long productId, String locationCode, Integer quantity) {
    }
}
