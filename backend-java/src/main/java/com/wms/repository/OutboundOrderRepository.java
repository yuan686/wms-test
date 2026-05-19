package com.wms.repository;

import com.wms.entity.OutboundOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 出库单 Repository
 */
@Repository
public interface OutboundOrderRepository extends JpaRepository<OutboundOrder, Long> {

    /**
     * 查询指定日期前缀下最新的出库单号
     */
    Optional<OutboundOrder> findTopByOrderNoStartingWithOrderByOrderNoDesc(String orderNoPrefix);
}
