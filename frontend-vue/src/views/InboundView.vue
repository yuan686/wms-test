<script setup lang="ts">
/**
 * 入库单创建页面
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createInboundOrder,
  getLocations,
  getProducts,
  getWarehouses,
  type InboundItemRequest,
  type Location,
  type Product,
  type Warehouse,
} from '@/api'

interface InboundFormItem {
  productId?: number
  warehouseId?: number
  locationCode: string
  quantity: number
  locations: Location[]
  locationLoading: boolean
}

const supplierName = ref('')
const items = ref<InboundFormItem[]>([])
const products = ref<Product[]>([])
const warehouses = ref<Warehouse[]>([])
const productLoading = ref(false)
const warehouseLoading = ref(false)
const submitting = ref(false)

/**
 * 创建空的入库明细行
 */
const createEmptyItem = (): InboundFormItem => ({
  productId: undefined,
  warehouseId: undefined,
  locationCode: '',
  quantity: 1,
  locations: [],
  locationLoading: false,
})

/**
 * 加载页面初始化数据
 */
const loadInitialData = async () => {
  productLoading.value = true
  warehouseLoading.value = true
  try {
    const [productRes, warehouseRes] = await Promise.all([
      getProducts(),
      getWarehouses(),
    ])
    products.value = productRes.data
    warehouses.value = warehouseRes.data
  } catch (e: any) {
    ElMessage.error(getErrorMessage(e, '初始化数据加载失败'))
  } finally {
    productLoading.value = false
    warehouseLoading.value = false
  }
}

/**
 * 远程搜索商品列表
 */
const searchProducts = async (keyword: string) => {
  productLoading.value = true
  try {
    const res = await getProducts(keyword || undefined)
    products.value = res.data
  } catch (e: any) {
    ElMessage.error(getErrorMessage(e, '商品搜索失败'))
  } finally {
    productLoading.value = false
  }
}

/**
 * 新增一行入库明细
 */
const addItem = () => {
  items.value.push(createEmptyItem())
}

/**
 * 删除指定入库明细行
 */
const removeItem = (index: number) => {
  items.value.splice(index, 1)
}

/**
 * 仓库变化后刷新该行库位列表
 */
const handleWarehouseChange = async (item: InboundFormItem) => {
  item.locationCode = ''
  item.locations = []
  if (!item.warehouseId) {
    return
  }
  await loadLocationsByWarehouse(item)
}

/**
 * 按仓库加载库位列表
 */
const loadLocationsByWarehouse = async (item: InboundFormItem) => {
  item.locationLoading = true
  try {
    const res = await getLocations(item.warehouseId as number)
    item.locations = res.data
  } catch (e: any) {
    ElMessage.error(getErrorMessage(e, '库位加载失败'))
  } finally {
    item.locationLoading = false
  }
}

/**
 * 校验表单并返回错误信息
 */
const validateForm = (): string | null => {
  if (!supplierName.value.trim()) {
    return '请输入供应商名称'
  }
  if (items.value.length === 0) {
    return '请至少添加一条入库明细'
  }
  for (let index = 0; index < items.value.length; index += 1) {
    const error = validateItem(items.value[index], index)
    if (error) {
      return error
    }
  }
  return null
}

/**
 * 校验单行入库明细
 */
const validateItem = (item: InboundFormItem, index: number): string | null => {
  const rowName = `第 ${index + 1} 行`
  if (!item.productId) {
    return `${rowName}请选择商品`
  }
  if (!item.locationCode) {
    return `${rowName}请选择目标库位`
  }
  if (!Number.isInteger(item.quantity) || item.quantity <= 0) {
    return `${rowName}数量必须为正整数`
  }
  return null
}

/**
 * 提交入库单
 */
const handleSubmit = async () => {
  const error = validateForm()
  if (error) {
    ElMessage.warning(error)
    return
  }
  submitting.value = true
  try {
    const res = await createInboundOrder(buildSubmitData())
    ElMessage.success(`入库单创建成功：${res.data.orderNo}`)
    resetForm()
  } catch (e: any) {
    ElMessage.error(getErrorMessage(e, '入库单创建失败'))
  } finally {
    submitting.value = false
  }
}

/**
 * 组装提交给后端的数据
 */
const buildSubmitData = () => ({
  supplierName: supplierName.value.trim(),
  items: items.value.map<InboundItemRequest>((item) => ({
    productId: item.productId as number,
    quantity: item.quantity,
    locationCode: item.locationCode,
  })),
})

/**
 * 重置入库单表单
 */
const resetForm = () => {
  supplierName.value = ''
  items.value = [createEmptyItem()]
}

/**
 * 获取接口错误提示
 */
const getErrorMessage = (error: any, fallback: string) => {
  return error?.response?.data?.message || error?.message || fallback
}

onMounted(async () => {
  resetForm()
  await loadInitialData()
})
</script>

<template>
  <div class="inbound-page">
    <h3>入库单创建</h3>

    <el-form label-width="100px" class="inbound-form">
      <el-form-item label="供应商名称" required>
        <el-input
          v-model="supplierName"
          maxlength="200"
          placeholder="请输入供应商名称"
          clearable
        />
      </el-form-item>

      <el-form-item label="入库明细" required>
        <el-button type="primary" @click="addItem">添加明细</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="items" border class="item-table">
      <el-table-column label="商品" min-width="220">
        <template #default="{ row }">
          <el-select
            v-model="row.productId"
            filterable
            remote
            clearable
            reserve-keyword
            placeholder="搜索商品名称/SKU"
            :remote-method="searchProducts"
            :loading="productLoading"
            class="full-width"
          >
            <el-option
              v-for="product in products"
              :key="product.id"
              :label="`${product.name}（${product.sku}）`"
              :value="product.id"
            />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="目标仓库" min-width="180">
        <template #default="{ row }">
          <el-select
            v-model="row.warehouseId"
            clearable
            filterable
            placeholder="选择仓库"
            :loading="warehouseLoading"
            class="full-width"
            @change="handleWarehouseChange(row)"
          >
            <el-option
              v-for="warehouse in warehouses"
              :key="warehouse.id"
              :label="`${warehouse.name}（${warehouse.code}）`"
              :value="warehouse.id"
            />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="目标库位" min-width="180">
        <template #default="{ row }">
          <el-select
            v-model="row.locationCode"
            clearable
            filterable
            placeholder="选择库位"
            :disabled="!row.warehouseId"
            :loading="row.locationLoading"
            class="full-width"
          >
            <el-option
              v-for="location in row.locations"
              :key="location.id"
              :label="location.code"
              :value="location.code"
            />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="数量" width="160">
        <template #default="{ row }">
          <el-input-number
            v-model="row.quantity"
            :min="1"
            :precision="0"
            controls-position="right"
            class="quantity-input"
          />
        </template>
      </el-table-column>

      <el-table-column label="操作" width="100" align="center">
        <template #default="{ $index }">
          <el-button type="danger" size="small" @click="removeItem($index)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="form-actions">
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        提交入库单
      </el-button>
      <el-button @click="resetForm">重置</el-button>
    </div>

    <el-empty
      v-if="items.length === 0"
      description="请点击添加明细按钮添加入库商品"
    />
  </div>
</template>

<style scoped>
.inbound-page {
  padding: 4px;
}

.inbound-form {
  max-width: 720px;
}

.item-table {
  width: 100%;
  margin-top: 8px;
}

.full-width {
  width: 100%;
}

.quantity-input {
  width: 130px;
}

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}
</style>
