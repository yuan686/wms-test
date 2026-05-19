package com.wms.repository;

import com.wms.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 库存充足时扣减数据库库存，返回更新行数
     */
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.quantity = i.quantity - :quantity
            WHERE i.productId = :productId
              AND i.locationCode = :locationCode
              AND i.quantity >= :quantity
            """)
    int decreaseWhenEnough(@Param("productId") Long productId,
                           @Param("locationCode") String locationCode,
                           @Param("quantity") Integer quantity);
}
