package com.wms.repository;

import com.wms.common.PageResult;
import com.wms.dto.InventoryResponse;

/**
 * 库存动态查询 Repository
 */
public interface InventoryQueryRepository {

    /**
     * 分页查询库存列表
     */
    PageResult<InventoryResponse> queryInventory(String keyword, Long warehouseId,
                                                 String locationCode, int page, int pageSize);
}
