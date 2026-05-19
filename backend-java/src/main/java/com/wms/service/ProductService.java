package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.common.PageResult;
import com.wms.dto.ProductCreateRequest;
import com.wms.dto.ProductResponse;
import com.wms.dto.ProductUpdateRequest;
import com.wms.entity.Product;
import com.wms.repository.InventoryRepository;
import com.wms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品管理 Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * 分页查询商品列表
     */
    public PageResult<ProductResponse> list(String keyword, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        Pageable pageable = PageRequest.of(safePage - 1, safePageSize);
        Page<Product> productPage = productRepository.search(keyword, pageable);
        List<ProductResponse> list = productPage.getContent().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return new PageResult<>(list, productPage.getTotalElements(), safePage, safePageSize);
    }

    /**
     * 根据ID查询商品详情
     */
    public ProductResponse getById(Long id) {
        if (id == null) {
            throw new BusinessException(400, "商品ID不能为空");
        }
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "商品不存在"));
        return toResponse(product);
    }

    /**
     * 创建商品
     */
    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        if (request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException(400, "商品名称不能为空");
        }
        if (request.getSku() == null || request.getSku().trim().isEmpty()) {
            throw new BusinessException(400, "SKU不能为空");
        }
        if (productRepository.existsBySku(request.getSku().trim())) {
            throw new BusinessException("SKU已存在: " + request.getSku());
        }
        Product product = Product.builder()
                .name(request.getName().trim())
                .sku(request.getSku().trim())
                .unit(request.getUnit() != null ? request.getUnit().trim() : "个")
                .build();
        product = productRepository.save(product);
        log.info("创建商品成功: id={}, sku={}", product.getId(), product.getSku());
        return toResponse(product);
    }

    /**
     * 更新商品信息
     */
    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        if (id == null) {
            throw new BusinessException(400, "商品ID不能为空");
        }
        if (request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException(400, "商品名称不能为空");
        }
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "商品不存在"));
        product.setName(request.getName().trim());
        if (request.getUnit() != null && !request.getUnit().trim().isEmpty()) {
            product.setUnit(request.getUnit().trim());
        }
        product = productRepository.save(product);
        return toResponse(product);
    }

    /**
     * 删除商品
     */
    @Transactional
    public void delete(Long id) {
        if (id == null) {
            throw new BusinessException(400, "商品ID不能为空");
        }
        if (!productRepository.existsById(id)) {
            throw new BusinessException(404, "商品不存在");
        }
        if (inventoryRepository.existsByProductId(id)) {
            throw new BusinessException("该商品存在关联库存记录，无法删除");
        }
        productRepository.deleteById(id);
        log.info("删除商品: id={}", id);
    }

    private ProductResponse toResponse(Product product) {
        if (product == null) {
            return null;
        }
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .sku(product.getSku())
                .unit(product.getUnit())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}