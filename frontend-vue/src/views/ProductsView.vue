<script setup lang="ts">
/**
 * 商品管理页 — 后端分页查询
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getProducts, createProduct, updateProduct, deleteProduct, type Product } from '@/api'

const products = ref<Product[]>([])
const keyword = ref('')
const loading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('新增商品')
const form = ref({ id: 0, name: '', sku: '', unit: '个' })
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

/**
 * 加载商品列表（后端分页）
 */
const loadProducts = async (targetPage = currentPage.value) => {
  loading.value = true
  try {
    const res = await getProducts({
      keyword: keyword.value.trim() || undefined,
      page: targetPage,
      pageSize: pageSize.value,
    })
    products.value = res.data.list
    total.value = res.data.total
    currentPage.value = res.data.page
    pageSize.value = res.data.pageSize
  } catch (e: any) {
    ElMessage.error('加载失败: ' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}

/**
 * 搜索时回到第一页
 */
const handleSearch = async () => {
  currentPage.value = 1
  await loadProducts(1)
}

/**
 * 页码变化
 */
const handlePageChange = async (page: number) => {
  await loadProducts(page)
}

onMounted(() => loadProducts(1))

/**
 * 打开新增弹窗
 */
const handleAdd = () => {
  dialogTitle.value = '新增商品'
  form.value = { id: 0, name: '', sku: '', unit: '个' }
  dialogVisible.value = true
}

/**
 * 打开编辑弹窗
 */
const handleEdit = (product: Product) => {
  dialogTitle.value = '编辑商品'
  form.value = { id: product.id, name: product.name, sku: product.sku, unit: product.unit }
  dialogVisible.value = true
}

/**
 * 提交新增或编辑
 */
const handleSubmit = async () => {
  try {
    if (form.value.id) {
      await updateProduct(form.value.id, { name: form.value.name, unit: form.value.unit })
      ElMessage.success('更新成功')
    } else {
      await createProduct({ name: form.value.name, sku: form.value.sku, unit: form.value.unit })
      ElMessage.success('创建成功')
      currentPage.value = 1
    }
    dialogVisible.value = false
    await loadProducts(currentPage.value)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

/**
 * 删除商品
 */
const handleDelete = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定删除该商品吗？', '确认删除', { type: 'warning' })
    await deleteProduct(id)
    ElMessage.success('删除成功')
    const maxPage = Math.max(1, Math.ceil((total.value - 1) / pageSize.value))
    const nextPage = Math.min(currentPage.value, maxPage)
    await loadProducts(nextPage)
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.response?.data?.message || '删除失败')
    }
  }
}
</script>

<template>
  <div>
    <!-- 搜索栏 -->
    <div style="display: flex; gap: 12px; margin-bottom: 16px">
      <el-input
        v-model="keyword"
        placeholder="搜索商品名称/SKU..."
        style="width: 300px"
        clearable
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <el-button type="success" @click="handleAdd">新增商品</el-button>
    </div>

    <!-- 表格 -->
    <el-table :data="products" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="商品名称" />
      <el-table-column prop="sku" label="SKU" width="150" />
      <el-table-column prop="unit" label="单位" width="80" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" @click="handleEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div style="margin-top: 16px; text-align: right">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        :disabled="loading"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="商品名称">
          <el-input v-model="form.name" maxlength="200" />
        </el-form-item>
        <el-form-item label="SKU" v-if="!form.id">
          <el-input v-model="form.sku" maxlength="50" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="form.unit" maxlength="20" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
