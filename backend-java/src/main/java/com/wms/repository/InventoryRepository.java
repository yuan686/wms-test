package com.wms.repository;

import com.wms.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 库存 Repository
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long>, InventoryQueryRepository {

    /**
     * 根据商品和库位查询库存
     */
    Optional<Inventory> findByProductIdAndLocationCode(Long productId, String locationCode);

    boolean existsByProductId(Long productId);
}