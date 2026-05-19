package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InboundOrderResponse;
import com.wms.entity.InboundOrder;
import com.wms.entity.Inventory;
import com.wms.entity.Product;
import com.wms.repository.InboundOrderItemRepository;
import com.wms.repository.InboundOrderRepository;
import com.wms.repository.InventoryRepository;
import com.wms.repository.LocationRepository;
import com.wms.repository.OutboundOrderItemRepository;
import com.wms.repository.OutboundOrderRepository;
import com.wms.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("入库单创建 Service 层单元测试")
class InboundOrderServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InboundOrderRepository inboundOrderRepository;
    @Mock
    private InboundOrderItemRepository inboundOrderItemRepository;
    @Mock
    private OutboundOrderRepository outboundOrderRepository;
    @Mock
    private OutboundOrderItemRepository outboundOrderItemRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private OutboundApplyCacheService outboundApplyCacheService;

    @InjectMocks
    private InventoryService inventoryService;

    private Product testProduct;
    private InboundOrderCreateRequest testRequest;

    @BeforeEach
    void setUp() {
        testProduct = Product.builder()
                .id(1L)
                .name("测试商品")
                .sku("SKU001")
                .unit("个")
                .build();

        List<InboundOrderCreateRequest.InboundItemRequest> items = new ArrayList<>();
        InboundOrderCreateRequest.InboundItemRequest itemRequest = new InboundOrderCreateRequest.InboundItemRequest();
        itemRequest.setProductId(1L);
        itemRequest.setQuantity(100);
        itemRequest.setLocationCode("A-01-01");
        items.add(itemRequest);

        testRequest = new InboundOrderCreateRequest();
        testRequest.setSupplierName("测试供应商");
        testRequest.setItems(items);
    }

    @Test
    @DisplayName("用例1：正常创建入库单 - 应成功返回入库单信息")
    void createInboundOrder_Success() {
        String orderNoPrefix = "IN-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        when(inboundOrderRepository.findTopByOrderNoStartingWithOrderByOrderNoDesc(orderNoPrefix))
                .thenReturn(Optional.empty());
        when(locationRepository.findByCode("A-01-01"))
                .thenReturn(Optional.of(com.wms.entity.Location.builder().code("A-01-01").build()));
        when(productRepository.findById(1L))
                .thenReturn(Optional.of(testProduct));
        when(inventoryRepository.findByProductIdAndLocationCode(1L, "A-01-01"))
                .thenReturn(Optional.empty());
        when(inboundOrderRepository.save(any(InboundOrder.class)))
                .thenAnswer(invocation -> {
                    InboundOrder order = invocation.getArgument(0);
                    order.setId(100L);
                    return order;
                });
        when(inboundOrderItemRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(inventoryRepository.save(any(Inventory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InboundOrderResponse response = inventoryService.createInboundOrder(testRequest);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertTrue(response.getOrderNo().startsWith(orderNoPrefix));
        assertEquals("测试供应商", response.getSupplierName());
        assertEquals("COMPLETED", response.getStatus());
        assertEquals(1, response.getItems().size());
        assertEquals(100, response.getItems().get(0).getQuantity());
        assertEquals("A-01-01", response.getItems().get(0).getLocationCode());
    }

    @Test
    @DisplayName("用例2：入库单请求为空 - 应抛出业务异常")
    void createInboundOrder_NullRequest_ThrowsException() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> inventoryService.createInboundOrder(null));

        assertEquals("入库单请求不能为空", exception.getMessage());
    }

    @Test
    @DisplayName("用例3：入库明细为空 - 应抛出业务异常")
    void createInboundOrder_EmptyItems_ThrowsException() {
        testRequest.setItems(new ArrayList<>());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> inventoryService.createInboundOrder(testRequest));

        assertEquals("入库明细不能为空", exception.getMessage());
    }

    @Test
    @DisplayName("用例4：商品不存在 - 应抛出业务异常")
    void createInboundOrder_ProductNotFound_ThrowsException() {
        String orderNoPrefix = "IN-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        when(inboundOrderRepository.findTopByOrderNoStartingWithOrderByOrderNoDesc(orderNoPrefix))
                .thenReturn(Optional.empty());
        when(locationRepository.findByCode("A-01-01"))
                .thenReturn(Optional.of(com.wms.entity.Location.builder().code("A-01-01").build()));
        when(productRepository.findById(1L))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> inventoryService.createInboundOrder(testRequest));

        assertTrue(exception.getMessage().contains("商品不存在"));
    }

    @Test
    @DisplayName("用例5：库位不存在 - 应抛出业务异常")
    void createInboundOrder_LocationNotFound_ThrowsException() {
        String orderNoPrefix = "IN-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        when(inboundOrderRepository.findTopByOrderNoStartingWithOrderByOrderNoDesc(orderNoPrefix))
                .thenReturn(Optional.empty());
        when(locationRepository.findByCode("A-01-01"))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> inventoryService.createInboundOrder(testRequest));

        assertTrue(exception.getMessage().contains("库位不存在"));
    }

    @Test
    @DisplayName("用例6：数量为负数 - 应抛出业务异常")
    void createInboundOrder_NegativeQuantity_ThrowsException() {
        List<InboundOrderCreateRequest.InboundItemRequest> items = new ArrayList<>();
        InboundOrderCreateRequest.InboundItemRequest itemRequest = new InboundOrderCreateRequest.InboundItemRequest();
        itemRequest.setProductId(1L);
        itemRequest.setQuantity(-10);
        itemRequest.setLocationCode("A-01-01");
        items.add(itemRequest);
        testRequest.setItems(items);

        String orderNoPrefix = "IN-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        when(inboundOrderRepository.findTopByOrderNoStartingWithOrderByOrderNoDesc(orderNoPrefix))
                .thenReturn(Optional.empty());
        when(locationRepository.findByCode("A-01-01"))
                .thenReturn(Optional.of(com.wms.entity.Location.builder().code("A-01-01").build()));
        when(productRepository.findById(1L))
                .thenReturn(Optional.of(testProduct));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> inventoryService.createInboundOrder(testRequest));

        assertEquals("数量必须大于0", exception.getMessage());
    }

    @Test
    @DisplayName("用例7：库存累加计算 - 原有库存加上新入库数量应正确")
    void createInboundOrder_InventoryAccumulation_CorrectCalculation() {
        String orderNoPrefix = "IN-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Inventory existingInventory = Inventory.builder()
                .productId(1L)
                .locationCode("A-01-01")
                .quantity(50)
                .build();

        when(inboundOrderRepository.findTopByOrderNoStartingWithOrderByOrderNoDesc(orderNoPrefix))
                .thenReturn(Optional.empty());
        when(locationRepository.findByCode("A-01-01"))
                .thenReturn(Optional.of(com.wms.entity.Location.builder().code("A-01-01").build()));
        when(productRepository.findById(1L))
                .thenReturn(Optional.of(testProduct));
        when(inventoryRepository.findByProductIdAndLocationCode(1L, "A-01-01"))
                .thenReturn(Optional.of(existingInventory));
        when(inboundOrderRepository.save(any(InboundOrder.class)))
                .thenAnswer(invocation -> {
                    InboundOrder order = invocation.getArgument(0);
                    order.setId(100L);
                    return order;
                });
        when(inboundOrderItemRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(inventoryRepository.save(any(Inventory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InboundOrderResponse response = inventoryService.createInboundOrder(testRequest);

        ArgumentCaptor<Inventory> inventoryCaptor = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepository).save(inventoryCaptor.capture());
        Inventory savedInventory = inventoryCaptor.getValue();
        assertEquals(150, savedInventory.getQuantity());
    }
}