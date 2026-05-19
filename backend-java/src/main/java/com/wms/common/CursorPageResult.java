package com.wms.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 游标分页结果（基于主键定位，避免大偏移量扫描）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CursorPageResult<T> {
    private List<T> list;
    /** 符合条件的总条数，仅 page=1 时返回 */
    private Long total;
    /** 当前页码 */
    private int page;
    private int pageSize;
    /** 下一页游标：本页最后一条记录的 inventory.id，无下一页时为 null */
    private Long nextCursor;
    /** 是否还有下一页 */
    private boolean hasMore;
}
