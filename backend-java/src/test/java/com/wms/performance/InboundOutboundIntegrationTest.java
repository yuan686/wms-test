package com.wms.performance;

import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InboundOrderResponse;
import com.wms.dto.OutboundOrderCreateRequest;
import com.wms.dto.OutboundOrderResponse;
import com.wms.entity.*;
import com.wms.repository.*;
import com.wms.service.InventoryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 入库出库集成压力测试
 * 测试流程：
 * 1. 查询数据库现有信息（商品、仓库、库位、库存）
 * 2. 排列组合生成入库单信息
 * 3. 调用入库接口创建入库单
 * 4. 查询库存信息
 * 5. 基于现有库存进行出库压力测试
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("入库出库集成压力测试")
class InboundOutboundIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private InboundOrderRepository inboundOrderRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY_PREFIX = "inventory:stock:";
    private List<Product> allProducts;
    private List<Location> allLocations;
    private List<Warehouse> allWarehouses;
    private List<Inventory> initialInventories;
    private List<InboundOrderResponse> createdInboundOrders = new ArrayList<>();

    @BeforeAll
    @Transactional
    void setUpAll() {
        System.out.println("===== 步骤1：查询数据库现有信息 =====");
        queryDatabaseInfo();
        initializeRedisStock();
    }

    /**
     * 查询数据库现有信息
     */
    private void queryDatabaseInfo() {
        allProducts = productRepository.findAll();
        System.out.printf("商品数量: %d%n", allProducts.size());
        for (Product p : allProducts) {
            System.out.printf("  - 商品ID: %d, 名称: %s, SKU: %s%n", p.getId(), p.getName(), p.getSku());
        }

        allWarehouses = warehouseRepository.findAll();
        System.out.printf("%n仓库数量: %d%n", allWarehouses.size());
        for (Warehouse w : allWarehouses) {
            System.out.printf("  - 仓库ID: %d, 编码: %s, 名称: %s%n", w.getId(), w.getCode(), w.getName());
        }

        allLocations = locationRepository.findAll();
        System.out.printf("%n库位数量: %d%n", allLocations.size());
        for (Location l : allLocations) {
            System.out.printf("  - 库位ID: %d, 编码: %s, 仓库ID: %d, 状态: %s%n",
                    l.getId(), l.getCode(), l.getWarehouseId(), l.getStatus());
        }

        initialInventories = inventoryRepository.findAll();
        System.out.printf("%n初始库存数量: %d%n", initialInventories.size());
        for (Inventory inv : initialInventories) {
            System.out.printf("  - 商品ID: %d, 库位: %s, 数量: %d%n",
                    inv.getProductId(), inv.getLocationCode(), inv.getQuantity());
        }
    }

    /**
     * 初始化 Redis 库存
     */
    private void initializeRedisStock() {
        System.out.println("%n===== 初始化 Redis 库存 =====");
        for (Inventory inv : initialInventories) {
            String stockKey = STOCK_KEY_PREFIX + inv.getProductId() + ":" + inv.getLocationCode();
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(inv.getQuantity()));
            System.out.printf("  - 设置库存: %s = %d%n", stockKey, inv.getQuantity());
        }
    }

    @Test
    @DisplayName("步骤2：排列组合生成入库单并创建")
    void generateAndCreateInboundOrders() {
        System.out.println("%n===== 步骤2：排列组合生成入库单 =====");

        List<InboundOrderCreateRequest> inboundRequests = generateInboundOrderRequests();
        System.out.printf("生成入库单数量: %d%n", inboundRequests.size());

        for (int i = 0; i < inboundRequests.size(); i++) {
            InboundOrderCreateRequest request = inboundRequests.get(i);
            try {
                InboundOrderResponse response = inventoryService.createInboundOrder(request);
                createdInboundOrders.add(response);
                System.out.printf("  入库单 %d 创建成功: %s, 供应商: %s, 明细数: %d%n",
                        i + 1, response.getOrderNo(), response.getSupplierName(), response.getItems().size());
            } catch (Exception e) {
                System.out.printf("  入库单 %d 创建失败: %s%n", i + 1, e.getMessage());
            }
        }
    }

    /**
     * 排列组合生成入库单请求
     * 策略：每个商品与每个库位组合生成入库单
     */
    private List<InboundOrderCreateRequest> generateInboundOrderRequests() {
        List<InboundOrderCreateRequest> requests = new ArrayList<>();
        String[] suppliers = {"深圳电子科技有限公司", "广州数码配件厂", "东莞塑料制品公司"};

        for (int supplierIdx = 0; supplierIdx < suppliers.length; supplierIdx++) {
            InboundOrderCreateRequest request = new InboundOrderCreateRequest();
            request.setSupplierName(suppliers[supplierIdx]);

            List<InboundOrderCreateRequest.InboundItemRequest> items = new ArrayList<>();

            // 每个供应商对应不同的商品和库位组合
            for (int productIdx = supplierIdx; productIdx < Math.min(supplierIdx + 2, allProducts.size()); productIdx++) {
                Product product = allProducts.get(productIdx);
                int locationStart = Math.min(supplierIdx * 2, allLocations.size());
                int locationEnd = Math.min(locationStart + 2, allLocations.size());

                for (int locIdx = locationStart; locIdx < locationEnd; locIdx++) {
                    Location location = allLocations.get(locIdx);
                    InboundOrderCreateRequest.InboundItemRequest item = new InboundOrderCreateRequest.InboundItemRequest();
                    item.setProductId(product.getId());
                    item.setQuantity(100 + (supplierIdx * 50) + (productIdx * 20));
                    item.setLocationCode(location.getCode());
                    items.add(item);
                }
            }

            request.setItems(items);
            requests.add(request);
        }

        return requests;
    }

    @Test
    @DisplayName("步骤3：查询库存信息")
    @Transactional
    void queryInventoryInfo() {
        System.out.println("%n===== 步骤3：查询库存信息 =====");

        List<Inventory> currentInventories = inventoryRepository.findAll();
        System.out.printf("当前库存数量: %d%n", currentInventories.size());

        int totalStock = 0;
        for (Inventory inv : currentInventories) {
            String stockKey = STOCK_KEY_PREFIX + inv.getProductId() + ":" + inv.getLocationCode();
            String redisStock = stringRedisTemplate.opsForValue().get(stockKey);
            int redisStockValue = redisStock != null ? Integer.parseInt(redisStock) : 0;

            Product product = productRepository.findById(inv.getProductId()).orElse(null);
            String productName = product != null ? product.getName() : "未知";

            System.out.printf("  - 商品: %s, 库位: %s, 数据库库存: %d, Redis库存: %d%n",
                    productName, inv.getLocationCode(), inv.getQuantity(), redisStockValue);
            totalStock += inv.getQuantity();
        }

        System.out.printf("%n总库存数量: %d%n", totalStock);
    }

    @Test
    @DisplayName("步骤4：出库压力测试 - 基于库存信息")
    void outboundPressureTest() throws Exception {
        System.out.println("%n===== 步骤4：出库压力测试 =====");

        List<Inventory> availableInventories = inventoryRepository.findAll();
        if (availableInventories.isEmpty()) {
            System.out.println("没有可用库存，跳过出库测试");
            return;
        }

        // 选择有库存的商品和库位组合
        List<Inventory> validInventories = availableInventories.stream()
                .filter(inv -> inv.getQuantity() > 0)
                .toList();

        if (validInventories.isEmpty()) {
            System.out.println("没有可用库存（数量>0），跳过出库测试");
            return;
        }

        System.out.printf("可用库存组合数: %d%n", validInventories.size());

        // 运行不同并发级别的测试
        runOutboundConcurrencyTest(10, validInventories, 100);
        runOutboundConcurrencyTest(50, validInventories, 500);
        runOutboundConcurrencyTest(100, validInventories, 1000);
    }

    /**
     * 运行出库并发测试
     */
    private void runOutboundConcurrencyTest(int threadCount, List<Inventory> validInventories, int totalRequests) throws Exception {
        int requestsPerThread = totalRequests / threadCount;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Long>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                long threadStartTime = System.currentTimeMillis();
                Random random = new Random();

                for (int j = 0; j < requestsPerThread; j++) {
                    try {
                        Inventory inventory = validInventories.get(random.nextInt(validInventories.size()));
                        executeOutboundOrder(inventory.getProductId(), inventory.getLocationCode(),
                                Math.min(5, inventory.getQuantity() > 0 ? inventory.getQuantity() : 1));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                }
                return System.currentTimeMillis() - threadStartTime;
            }));
        }

        for (Future<Long> future : futures) {
            future.get();
        }

        long endTime = System.currentTimeMillis();
        executor.shutdown();

        System.out.println();
        System.out.println("===== " + threadCount + " 并发出库测试 =====");
        System.out.printf("总请求数: %d%n", totalRequests);
        System.out.printf("成功请求: %d%n", successCount.get());
        System.out.printf("失败请求: %d%n", failCount.get());
        System.out.printf("成功率: %.2f%%%n", (double) successCount.get() / totalRequests * 100);
        System.out.printf("总耗时: %d ms%n", endTime - startTime);
        System.out.printf("吞吐量: %.2f req/s%n", (double) successCount.get() / (endTime - startTime) * 1000);
        System.out.printf("平均响应时间: %.2f ms%n", (double) (endTime - startTime) / threadCount);
    }

    /**
     * 执行出库单创建
     */
    private OutboundOrderResponse executeOutboundOrder(Long productId, String locationCode, int quantity) {
        OutboundOrderCreateRequest request = new OutboundOrderCreateRequest();
        request.setCustomerName("测试客户-" + UUID.randomUUID().toString().substring(0, 8));

        List<OutboundOrderCreateRequest.OutboundItemRequest> items = new ArrayList<>();
        OutboundOrderCreateRequest.OutboundItemRequest item = new OutboundOrderCreateRequest.OutboundItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        item.setLocationCode(locationCode);
        items.add(item);

        request.setItems(items);
        return inventoryService.createOutboundOrder(request);
    }
}
