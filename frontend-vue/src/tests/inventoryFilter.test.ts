import { ref } from 'vue'
import { describe, it, expect } from 'vitest'

interface InventoryItem {
  id: number
  productId: number
  productName: string
  sku: string
  locationCode: string
  warehouseId: number
  warehouseName: string
  quantity: number
  updatedAt: string
}

interface Warehouse {
  id: number
  name: string
  code: string
}

interface QueryParams {
  keyword?: string
  warehouseId?: number
  locationCode?: string
  page: number
  pageSize: number
  cursor?: number
}

interface InventoryFilters {
  keyword: string
  locationCode: string
  warehouseId: number | undefined
}

const pageCursors = ref<(number | undefined)[]>([undefined])

function buildQueryParams(targetPage: number, filters: InventoryFilters, pageSize: number): QueryParams {
  const base: QueryParams = {
    keyword: filters.keyword.trim() || undefined,
    warehouseId: filters.warehouseId,
    locationCode: filters.locationCode.trim() || undefined,
    page: targetPage,
    pageSize: pageSize,
  }
  const cursor = targetPage > 1 ? pageCursors.value[targetPage - 1] : undefined
  if (cursor != null) {
    return { ...base, cursor }
  }
  return base
}

function resetPagination(): { page: number; pageCursors: (number | undefined)[] } {
  return { page: 1, pageCursors: [undefined] }
}

function getRowClassName(row: InventoryItem): string {
  return row.quantity < 10 ? 'low-stock-row' : ''
}

function isLowStock(quantity: number): boolean {
  return quantity < 10
}

function shouldHighlightRow(inventory: InventoryItem): boolean {
  return isLowStock(inventory.quantity)
}

function filterInventoryList(list: InventoryItem[], filters: InventoryFilters): InventoryItem[] {
  return list.filter(item => {
    const matchesKeyword = !filters.keyword ||
      item.productName.toLowerCase().includes(filters.keyword.toLowerCase()) ||
      item.sku.toLowerCase().includes(filters.keyword.toLowerCase())
    const matchesLocation = !filters.locationCode ||
      item.locationCode.startsWith(filters.locationCode)
    const matchesWarehouse = !filters.warehouseId || item.warehouseId === filters.warehouseId
    return matchesKeyword && matchesLocation && matchesWarehouse
  })
}

describe('库存列表筛选逻辑测试', () => {

  const mockInventoryList: InventoryItem[] = [
    { id: 1, productId: 1, productName: '商品A', sku: 'SKU001', locationCode: 'A-01-01', warehouseId: 1, warehouseName: '仓库1', quantity: 5, updatedAt: '2024-01-01' },
    { id: 2, productId: 2, productName: '商品B', sku: 'SKU002', locationCode: 'A-01-02', warehouseId: 1, warehouseName: '仓库1', quantity: 15, updatedAt: '2024-01-01' },
    { id: 3, productId: 3, productName: '商品C', sku: 'SKU003', locationCode: 'B-02-01', warehouseId: 2, warehouseName: '仓库2', quantity: 8, updatedAt: '2024-01-01' },
    { id: 4, productId: 4, productName: '商品D', sku: 'SKU004', locationCode: 'B-02-02', warehouseId: 2, warehouseName: '仓库2', quantity: 25, updatedAt: '2024-01-01' },
  ]

  describe('用例1：buildQueryParams - 组装查询参数', () => {
    it('第一页查询应包含基础分页参数', () => {
      const filters: InventoryFilters = { keyword: '', locationCode: '', warehouseId: undefined }
      const result = buildQueryParams(1, filters, 20)

      expect(result.page).toBe(1)
      expect(result.pageSize).toBe(20)
      expect(result.keyword).toBeUndefined()
      expect(result.locationCode).toBeUndefined()
      expect(result.warehouseId).toBeUndefined()
      expect(result.cursor).toBeUndefined()
    })

    it('带关键词搜索应正确设置keyword参数', () => {
      const filters: InventoryFilters = { keyword: '商品A', locationCode: '', warehouseId: undefined }
      const result = buildQueryParams(1, filters, 20)

      expect(result.keyword).toBe('商品A')
    })

    it('带仓库筛选应正确设置warehouseId参数', () => {
      const filters: InventoryFilters = { keyword: '', locationCode: '', warehouseId: 1 }
      const result = buildQueryParams(1, filters, 20)

      expect(result.warehouseId).toBe(1)
    })

    it('带库位筛选应正确设置locationCode参数', () => {
      const filters: InventoryFilters = { keyword: '', locationCode: 'A-01', warehouseId: undefined }
      const result = buildQueryParams(1, filters, 20)

      expect(result.locationCode).toBe('A-01')
    })

    it('关键词首尾空格应被trim', () => {
      const filters: InventoryFilters = { keyword: '  商品A  ', locationCode: '', warehouseId: undefined }
      const result = buildQueryParams(1, filters, 20)

      expect(result.keyword).toBe('商品A')
    })
  })

  describe('用例2：resetPagination - 重置分页状态', () => {
    it('应重置页码为1', () => {
      const result = resetPagination()

      expect(result.page).toBe(1)
      expect(result.pageCursors).toEqual([undefined])
    })
  })

  describe('用例3：getRowClassName - 低库存行样式判断', () => {
    it('库存数量低于10应返回low-stock-row类名', () => {
      const item: InventoryItem = mockInventoryList[0]
      expect(item.quantity).toBe(5)
      expect(getRowClassName(item)).toBe('low-stock-row')
    })

    it('库存数量等于10不应返回low-stock-row类名', () => {
      const item: InventoryItem = { ...mockInventoryList[0], quantity: 10 }
      expect(getRowClassName(item)).toBe('')
    })

    it('库存数量高于10不应返回low-stock-row类名', () => {
      const item: InventoryItem = mockInventoryList[1]
      expect(item.quantity).toBe(15)
      expect(getRowClassName(item)).toBe('')
    })
  })

  describe('用例4：filterInventoryList - 前端筛选过滤', () => {
    it('空筛选条件应返回全部数据', () => {
      const filters: InventoryFilters = { keyword: '', locationCode: '', warehouseId: undefined }
      const result = filterInventoryList(mockInventoryList, filters)

      expect(result.length).toBe(4)
    })

    it('按关键词筛选应返回匹配的商品', () => {
      const filters: InventoryFilters = { keyword: '商品A', locationCode: '', warehouseId: undefined }
      const result = filterInventoryList(mockInventoryList, filters)

      expect(result.length).toBe(1)
      expect(result[0].productName).toBe('商品A')
    })

    it('按SKU关键词筛选应返回匹配的商品', () => {
      const filters: InventoryFilters = { keyword: 'SKU003', locationCode: '', warehouseId: undefined }
      const result = filterInventoryList(mockInventoryList, filters)

      expect(result.length).toBe(1)
      expect(result[0].sku).toBe('SKU003')
    })

    it('按库位前缀筛选应返回匹配的库位商品', () => {
      const filters: InventoryFilters = { keyword: '', locationCode: 'A-01', warehouseId: undefined }
      const result = filterInventoryList(mockInventoryList, filters)

      expect(result.length).toBe(2)
      expect(result.every(item => item.locationCode.startsWith('A-01'))).toBe(true)
    })

    it('按仓库ID筛选应返回该仓库的商品', () => {
      const filters: InventoryFilters = { keyword: '', locationCode: '', warehouseId: 1 }
      const result = filterInventoryList(mockInventoryList, filters)

      expect(result.length).toBe(2)
      expect(result.every(item => item.warehouseId === 1)).toBe(true)
    })

    it('组合筛选条件应正确过滤', () => {
      const filters: InventoryFilters = { keyword: '商品', locationCode: 'A-01', warehouseId: 1 }
      const result = filterInventoryList(mockInventoryList, filters)

      expect(result.length).toBe(1)
      expect(result[0].productName).toBe('商品A')
    })

    it('关键词大小写不敏感', () => {
      const filters: InventoryFilters = { keyword: 'sku001', locationCode: '', warehouseId: undefined }
      const result = filterInventoryList(mockInventoryList, filters)

      expect(result.length).toBe(1)
      expect(result[0].sku).toBe('SKU001')
    })
  })

  describe('用例5：isLowStock - 低库存判断', () => {
    it('库存为9应判定为低库存', () => {
      expect(isLowStock(9)).toBe(true)
    })

    it('库存为0应判定为低库存', () => {
      expect(isLowStock(0)).toBe(true)
    })

    it('库存为10或以上不应判定为低库存', () => {
      expect(isLowStock(10)).toBe(false)
      expect(isLowStock(100)).toBe(false)
    })

    it('库存为负数应判定为低库存', () => {
      expect(isLowStock(-1)).toBe(true)
    })
  })

  describe('用例6：shouldHighlightRow - 行高亮判断', () => {
    it('低库存商品应返回true', () => {
      const lowStockItem = mockInventoryList[0]
      expect(shouldHighlightRow(lowStockItem)).toBe(true)
    })

    it('正常库存商品应返回false', () => {
      const normalStockItem = mockInventoryList[1]
      expect(shouldHighlightRow(normalStockItem)).toBe(false)
    })
  })
})