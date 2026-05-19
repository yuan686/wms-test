package com.wms.repository;

import com.wms.entity.InboundOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 入库单 Repository
 */
@Repository
public interface InboundOrderRepository extends JpaRepository<InboundOrder, Long> {

    /**
     * 查询指定日期前缀下最新的入库单号
     */
    Optional<InboundOrder> findTopByOrderNoStartingWithOrderByOrderNoDesc(String orderNoPrefix);
}
