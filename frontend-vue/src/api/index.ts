import api from './client'

// ============ 商品（参考实现） ============

export interface Product {
  id: number
  name: string
  sku: string
  unit: string
  createdAt: string
  updatedAt: string
}

export const getProducts = (keyword?: string) =>
  api.get<any, { code: number; data: Product[] }>('/products', { params: { keyword } })

export const getProduct = (id: number) =>
  api.get<any, { code: number; data: Product }>(`/products/${id}`)

export const createProduct = (data: { name: string; sku: string; unit?: string }) =>
  api.post('/products', data)

export const updateProduct = (id: number, data: { name: string; unit?: string }) =>
  api.put(`/products/${id}`, data)

export const deleteProduct = (id: number) =>
  api.delete(`/products/${id}`)


// ============ 仓库 & 库位 ============

export interface Warehouse {
  id: number
  code: string
  name: string
}

export interface Location {
  id: number
  warehouseId: number
  code: string
  status: string
}

export const getWarehouses = () =>
  api.get<any, { code: number; data: Warehouse[] }>('/warehouses')

export const getLocations = (warehouseId: number) =>
  api.get<any, { code: number; data: Location[] }>(`/warehouses/${warehouseId}/locations`)


// ============ 库存查询（候选人实现） ============

export interface InventoryItem {
  id: number
  productId: number
  productName: string
  sku: string
  locationCode: string
  warehouseName: string
  quantity: number
  updatedAt: string
}

export interface InventoryPageData {
  list: InventoryItem[]
  total: number | null
  page: number
  pageSize: number
  nextCursor: number | null
  hasMore: boolean
}

export const getInventory = (params: {
  keyword?: string
  warehouseId?: number
  locationCode?: string
  /** 已缓存的游标，顺序翻页时优先使用 */
  cursor?: number
  /** 页码跳转（无游标时走偏移分页） */
  page?: number
  pageSize?: number
}) =>
  api.get<any, { code: number; data: InventoryPageData }>('/inventory', { params })


// ============ 入库单（候选人实现） ============

export interface InboundItemRequest {
  productId: number
  quantity: number
  locationCode: string
}

export interface InboundOrderResponse {
  id: number
  orderNo: string
  supplierName: string
  status: string
  items: Array<{
    productId: number
    productName: string
    quantity: number
    locationCode: string
  }>
  createdAt: string
}

export const createInboundOrder = (data: {
  supplierName: string
  items: InboundItemRequest[]
}) =>
  api.post<any, { code: number; message: string; data: InboundOrderResponse }>('/inbound-orders', data)
