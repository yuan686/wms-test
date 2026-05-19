package com.wms.repository;

import com.wms.common.PageResult;
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
 * 库存动态查询 Repository 实现（显式 INNER JOIN，避免笛卡尔积）
 */
public class InventoryQueryRepositoryImpl implements InventoryQueryRepository {

    private static final String SELECT_COLUMNS = """
            SELECT p.id AS product_id,
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
    public PageResult<InventoryResponse> queryInventory(String keyword, Long warehouseId,
                                                        String locationCode, int page, int pageSize) {
        FilterParams filters = buildFilterParams(keyword, warehouseId, locationCode);
        String whereClause = buildWhereClause(filters);
        Map<String, Object> params = filters.getParams();

        String listSql = SELECT_COLUMNS + FROM_JOIN + whereClause + " ORDER BY i.id ASC";
        Query listQuery = entityManager.createNativeQuery(listSql);
        bindParams(listQuery, params);
        listQuery.setFirstResult((page - 1) * pageSize);
        listQuery.setMaxResults(pageSize);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = listQuery.getResultList();
        List<InventoryResponse> list = mapRows(rows);

        long total = countInventory(whereClause, params);
        return new PageResult<>(list, total, page, pageSize);
    }

    /**
     * 统计符合条件的库存行数
     */
    private long countInventory(String whereClause, Map<String, Object> params) {
        String countSql = "SELECT COUNT(i.id) " + FROM_JOIN + whereClause;
        Query countQuery = entityManager.createNativeQuery(countSql);
        bindParams(countQuery, params);
        Number total = (Number) countQuery.getSingleResult();
        return total == null ? 0L : total.longValue();
    }

    /**
     * 组装 WHERE 子句（固定以 WHERE 1=1 开头，便于拼接动态条件）
     */
    private String buildWhereClause(FilterParams filters) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
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
                    .productId(toLong(row[0]))
                    .productName((String) row[1])
                    .sku((String) row[2])
                    .locationCode((String) row[3])
                    .warehouseName((String) row[4])
                    .quantity(toInteger(row[5]))
                    .updatedAt(toLocalDateTime(row[6]))
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
