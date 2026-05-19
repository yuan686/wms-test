/**
 * 入库/出库明细维度：商品 + 仓库 + 库位
 */
export interface OrderItemDimension {
  productId?: number
  warehouseId?: number
  locationCode: string
}

/**
 * 构建明细唯一维度 key
 */
export function buildOrderItemDimensionKey(
  productId: number,
  warehouseId: number,
  locationCode: string,
): string {
  return `${productId}|${warehouseId}|${locationCode.trim()}`
}

/**
 * 判断该维度组合是否已被其他明细行占用
 */
export function isOrderItemDimensionTaken(
  items: OrderItemDimension[],
  excludeIndex: number,
  productId: number,
  warehouseId: number,
  locationCode: string,
): boolean {
  const key = buildOrderItemDimensionKey(productId, warehouseId, locationCode)
  return items.some((row, index) => {
    if (index === excludeIndex) {
      return false
    }
    const rowKey = buildOrderItemDimensionKeyFromRow(row)
    return rowKey != null && rowKey === key
  })
}

/**
 * 从明细行构建维度 key，三要素未齐时返回 null
 */
function buildOrderItemDimensionKeyFromRow(item: OrderItemDimension): string | null {
  if (item.productId == null || item.warehouseId == null) {
    return null
  }
  const locationCode = item.locationCode?.trim()
  if (!locationCode) {
    return null
  }
  return buildOrderItemDimensionKey(item.productId, item.warehouseId, locationCode)
}

/**
 * 商品选项是否应禁用（当前行仓库、库位已选时判断）
 */
export function isProductOptionDisabled(
  items: OrderItemDimension[],
  row: OrderItemDimension,
  rowIndex: number,
  productId: number,
): boolean {
  if (row.warehouseId == null || !row.locationCode?.trim()) {
    return false
  }
  return isOrderItemDimensionTaken(
    items,
    rowIndex,
    productId,
    row.warehouseId,
    row.locationCode,
  )
}

/**
 * 仓库选项是否应禁用（当前行商品、库位已选时判断）
 */
export function isWarehouseOptionDisabled(
  items: OrderItemDimension[],
  row: OrderItemDimension,
  rowIndex: number,
  warehouseId: number,
): boolean {
  if (row.productId == null || !row.locationCode?.trim()) {
    return false
  }
  return isOrderItemDimensionTaken(
    items,
    rowIndex,
    row.productId,
    warehouseId,
    row.locationCode,
  )
}

/**
 * 库位选项是否应禁用（当前行商品、仓库已选时判断）
 */
export function isLocationOptionDisabled(
  items: OrderItemDimension[],
  row: OrderItemDimension,
  rowIndex: number,
  locationCode: string,
): boolean {
  if (row.productId == null || row.warehouseId == null) {
    return false
  }
  return isOrderItemDimensionTaken(
    items,
    rowIndex,
    row.productId,
    row.warehouseId,
    locationCode,
  )
}

/**
 * 校验明细列表是否存在重复维度（提交兜底）
 */
export function validateOrderItemDuplicates(items: OrderItemDimension[]): string | null {
  const seen = new Map<string, number>()
  for (let index = 0; index < items.length; index += 1) {
    const key = buildOrderItemDimensionKeyFromRow(items[index])
    if (!key) {
      continue
    }
    const existingIndex = seen.get(key)
    if (existingIndex !== undefined) {
      return `第 ${existingIndex + 1} 行与第 ${index + 1} 行商品、仓库、库位重复，请合并数量或删除其中一行`
    }
    seen.set(key, index)
  }
  return null
}
