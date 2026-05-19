package com.wms.repository;

import com.wms.common.CursorPageResult;
import com.wms.dto.InventoryResponse;

/**
 * 库存动态查询 Repository
 */
public interface InventoryQueryRepository {

    /**
     * 分页查询库存：有 cursor 时走主键游标，否则按 page 偏移（支持页码跳转）
     */
    CursorPageResult<InventoryResponse> queryInventory(String keyword, Long warehouseId,
                                                       String locationCode, Long cursor,
                                                       int page, int pageSize);
}
