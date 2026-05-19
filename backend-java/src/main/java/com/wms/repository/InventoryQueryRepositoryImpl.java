package com.wms.repository;

import com.wms.common.CursorPageResult;
import com.wms.dto.InventoryResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存动态查询 Repository 实现（游标 + 偏移混合分页）
 */
public class InventoryQueryRepositoryImpl implements InventoryQueryRepository {

    private static final String SELECT_COLUMNS = """
            SELECT i.id AS inventory_id,
                   p.id AS product_id,
                   p.name AS product_name,
                   p.sku AS sku,
                   i.location_code AS location_code,
                   w.name AS warehouse_name,
                   i.quantity AS quantity,
                   i.updated_at AS updated_at
            """;

    private static final String FROM_JOIN = """
            FROM inventory i
            INNER JOIN products p ON p.id = i.product_id
            INNER JOIN locations l ON l.code = i.location_code
            INNER JOIN warehouses w ON w.id = l.warehouse_id
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 分页查询库存列表
     */
    @Override
    public CursorPageResult<InventoryResponse> queryInventory(String keyword, Long warehouseId,
                                                              String locationCode, Long cursor,
                                                              int page, int pageSize) {
        FilterParams filters = buildFilterParams(keyword, warehouseId, locationCode);
        if (cursor != null) {
            return queryByCursor(filters, cursor, page, pageSize);
        }
        return queryByOffset(filters, page, pageSize);
    }

    /**
     * 主键游标分页（顺序翻页，避免大 offset）
     */
    private CursorPageResult<InventoryResponse> queryByCursor(FilterParams filters, Long cursor,
                                                              int page, int pageSize) {
        String whereClause = buildWhereClause(filters, cursor);
        Map<String, Object> params = new HashMap<>(filters.getParams());
        params.put("cursor", cursor);

        int fetchSize = pageSize + 1;
        String listSql = SELECT_COLUMNS + FROM_JOIN + whereClause
                + " ORDER BY i.id ASC LIMIT :limit";
        List<Object[]> rows = executeListQuery(listSql, params, fetchSize, null);

        return buildPageResult(rows, page, pageSize, page == 1 ? countTotal(filters) : null);
    }

    /**
     * 偏移分页（支持任意页码跳转）
     */
    private CursorPageResult<InventoryResponse> queryByOffset(FilterParams filters, int page, int pageSize) {
        String whereClause = buildWhereClause(filters, null);
        Map<String, Object> params = filters.getParams();
        long offset = (long) (page - 1) * pageSize;

        int fetchSize = pageSize + 1;
        String listSql = SELECT_COLUMNS + FROM_JOIN + whereClause
                + " ORDER BY i.id ASC LIMIT :limit OFFSET :offset";
        List<Object[]> rows = executeListQuery(listSql, params, fetchSize, offset);

        Long total = page == 1 ? countTotal(filters) : null;
        return buildPageResult(rows, page, pageSize, total);
    }

    /**
     * 执行列表查询
     */
    private List<Object[]> executeListQuery(String sql, Map<String, Object> params,
                                            int fetchSize, Long offset) {
        Query listQuery = entityManager.createNativeQuery(sql);
        bindParams(listQuery, params);
        listQuery.setParameter("limit", fetchSize);
        if (offset != null) {
            listQuery.setParameter("offset", offset);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = listQuery.getResultList();
        return rows;
    }

    /**
     * 组装分页结果
     */
    private CursorPageResult<InventoryResponse> buildPageResult(List<Object[]> rows, int page,
                                                                int pageSize, Long total) {
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<InventoryResponse> list = mapRows(rows);
        Long nextCursor = !list.isEmpty() ? list.get(list.size() - 1).getId() : null;
        if (!hasMore) {
            nextCursor = null;
        }
        return new CursorPageResult<>(list, total, page, pageSize, nextCursor, hasMore);
    }

    /**
     * 统计符合条件的库存行数
     */
    private Long countTotal(FilterParams filters) {
        String countSql = "SELECT COUNT(i.id) " + FROM_JOIN + buildWhereClause(filters, null);
        Query countQuery = entityManager.createNativeQuery(countSql);
        bindParams(countQuery, filters.getParams());
        Number total = (Number) countQuery.getSingleResult();
        return total == null ? 0L : total.longValue();
    }

    /**
     * 组装 WHERE 子句
     */
    private String buildWhereClause(FilterParams filters, Long cursor) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (cursor != null) {
            where.append(" AND i.id > :cursor");
        }
        if (filters.hasKeyword()) {
            where.append(" AND (LOWER(p.name) LIKE :keyword OR LOWER(p.sku) LIKE :keyword)");
        }
        if (filters.hasWarehouseId()) {
            where.append(" AND l.warehouse_id = :warehouseId");
        }
        if (filters.hasLocationCode()) {
            where.append(" AND i.location_code LIKE :locationCode");
        }
        return where.toString();
    }

    /**
     * 构建筛选参数
     */
    private FilterParams buildFilterParams(String keyword, Long warehouseId, String locationCode) {
        FilterParams filters = new FilterParams();
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword != null) {
            filters.setKeyword("%" + normalizedKeyword.toLowerCase() + "%");
        }
        if (warehouseId != null) {
            filters.setWarehouseId(warehouseId);
        }
        String normalizedLocation = normalize(locationCode);
        if (normalizedLocation != null) {
            filters.setLocationCode(normalizedLocation + "%");
        }
        return filters;
    }

    /**
     * 绑定命名查询参数
     */
    private void bindParams(Query query, Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 将原生查询结果映射为 DTO
     */
    private List<InventoryResponse> mapRows(List<Object[]> rows) {
        List<InventoryResponse> list = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            list.add(InventoryResponse.builder()
                    .id(toLong(row[0]))
                    .productId(toLong(row[1]))
                    .productName((String) row[2])
                    .sku((String) row[3])
                    .locationCode((String) row[4])
                    .warehouseName((String) row[5])
                    .quantity(toInteger(row[6]))
                    .updatedAt(toLocalDateTime(row[7]))
                    .build());
        }
        return list;
    }

    /**
     * 安全转换为 Long
     */
    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    /**
     * 安全转换为 Integer
     */
    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    /**
     * 将数据库时间类型转为 LocalDateTime
     */
    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new IllegalArgumentException("不支持的时间类型: " + value.getClass().getName());
    }

    /**
     * 标准化查询文本
     */
    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 动态筛选参数容器
     */
    private static class FilterParams {
        private String keyword;
        private Long warehouseId;
        private String locationCode;

        boolean hasKeyword() {
            return keyword != null;
        }

        boolean hasWarehouseId() {
            return warehouseId != null;
        }

        boolean hasLocationCode() {
            return locationCode != null;
        }

        void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        void setWarehouseId(Long warehouseId) {
            this.warehouseId = warehouseId;
        }

        void setLocationCode(String locationCode) {
            this.locationCode = locationCode;
        }

        Map<String, Object> getParams() {
            Map<String, Object> params = new HashMap<>();
            if (hasKeyword()) {
                params.put("keyword", keyword);
            }
            if (hasWarehouseId()) {
                params.put("warehouseId", warehouseId);
            }
            if (hasLocationCode()) {
                params.put("locationCode", locationCode);
            }
            return params;
        }
    }
}
