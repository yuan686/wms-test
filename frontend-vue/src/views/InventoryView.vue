<script setup lang="ts">
/**
 * 库存查询页面
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getInventory, getWarehouses, type InventoryItem, type Warehouse } from '@/api'

const keyword = ref('')
const locationCode = ref('')
const warehouseId = ref<number | undefined>()
const warehouses = ref<Warehouse[]>([])
const loading = ref(false)
const warehouseLoading = ref(false)
const inventoryList = ref<InventoryItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
let searchTimer: number | undefined

/**
 * 加载仓库下拉列表
 */
const loadWarehouses = async () => {
  warehouseLoading.value = true
  try {
    const res = await getWarehouses()
    warehouses.value = res.data
  } catch (e: any) {
    ElMessage.error(getErrorMessage(e, '仓库加载失败'))
  } finally {
    warehouseLoading.value = false
  }
}

/**
 * 加载库存分页数据
 */
const loadInventory = async () => {
  loading.value = true
  try {
    const res = await getInventory(buildQueryParams())
    inventoryList.value = res.data.list
    total.value = res.data.total
    page.value = res.data.page
    pageSize.value = res.data.pageSize
  } catch (e: any) {
    ElMessage.error(getErrorMessage(e, '库存加载失败'))
  } finally {
    loading.value = false
  }
}

/**
 * 组装库存查询参数
 */
const buildQueryParams = () => ({
  keyword: keyword.value.trim() || undefined,
  warehouseId: warehouseId.value,
  locationCode: locationCode.value.trim() || undefined,
  page: page.value,
  pageSize: pageSize.value,
})

/**
 * 防抖触发库存搜索
 */
const debounceSearch = () => {
  if (searchTimer) {
    window.clearTimeout(searchTimer)
  }
  searchTimer = window.setTimeout(() => {
    handleSearch()
  }, 400)
}

/**
 * 执行搜索并回到第一页
 */
const handleSearch = async () => {
  page.value = 1
  await loadInventory()
}

/**
 * 清空筛选条件并重新加载
 */
const resetFilters = async () => {
  keyword.value = ''
  locationCode.value = ''
  warehouseId.value = undefined
  page.value = 1
  await loadInventory()
}

/**
 * 分页页码变化时加载数据
 */
const handlePageChange = async (nextPage: number) => {
  page.value = nextPage
  await loadInventory()
}

/**
 * 分页大小变化时加载数据
 */
const handlePageSizeChange = async (nextPageSize: number) => {
  pageSize.value = nextPageSize
  page.value = 1
  await loadInventory()
}

/**
 * 返回库存行样式类名
 */
const getRowClassName = ({ row }: { row: InventoryItem }) => {
  return row.quantity < 10 ? 'low-stock-row' : ''
}

/**
 * 获取接口错误提示
 */
const getErrorMessage = (error: any, fallback: string) => {
  return error?.response?.data?.message || error?.message || fallback
}

onMounted(async () => {
  await loadWarehouses()
  await loadInventory()
})

onBeforeUnmount(() => {
  if (searchTimer) {
    window.clearTimeout(searchTimer)
  }
})
</script>

<template>
  <div class="inventory-page">
    <h3>库存查询</h3>

    <div class="search-bar">
      <el-input
        v-model="keyword"
        placeholder="搜索商品名称/SKU..."
        clearable
        class="search-input"
        @input="debounceSearch"
        @clear="handleSearch"
        @keyup.enter="handleSearch"
      />
      <el-select
        v-model="warehouseId"
        placeholder="选择仓库"
        clearable
        filterable
        class="warehouse-select"
        :loading="warehouseLoading"
        @change="handleSearch"
        @clear="handleSearch"
      >
        <el-option
          v-for="warehouse in warehouses"
          :key="warehouse.id"
          :label="`${warehouse.name}（${warehouse.code}）`"
          :value="warehouse.id"
        />
      </el-select>
      <el-input
        v-model="locationCode"
        placeholder="库位编码前缀..."
        clearable
        class="location-input"
        @input="debounceSearch"
        @clear="handleSearch"
        @keyup.enter="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <el-table
      :data="inventoryList"
      v-loading="loading"
      border
      stripe
      :row-class-name="getRowClassName"
    >
      <el-table-column prop="productName" label="商品名称" min-width="180" />
      <el-table-column prop="sku" label="SKU" width="150" />
      <el-table-column prop="locationCode" label="库位编码" width="160" />
      <el-table-column prop="warehouseName" label="仓库" width="160" />
      <el-table-column prop="quantity" label="库存数量" width="110" />
      <el-table-column prop="updatedAt" label="最后更新时间" width="190" />
    </el-table>

    <div class="pagination-bar">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="handlePageChange"
        @size-change="handlePageSizeChange"
      />
    </div>

    <el-empty
      v-if="!loading && inventoryList.length === 0"
      description="暂无库存数据"
    />
  </div>
</template>

<style scoped>
.inventory-page {
  padding: 4px;
}

.search-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.search-input {
  width: 280px;
}

.warehouse-select,
.location-input {
  width: 220px;
}

.pagination-bar {
  margin-top: 16px;
  text-align: right;
}

:deep(.low-stock-row) {
  --el-table-tr-bg-color: #fff1f0;
  color: #c0392b;
}
</style>
