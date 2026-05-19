create database if not exists wms;
use wms;

-- 商品表
CREATE TABLE products (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    sku VARCHAR(50) NOT NULL UNIQUE,
    unit VARCHAR(20) DEFAULT '个',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 优化商品名称模糊搜索
    INDEX idx_products_name (name),
    -- 优化SKU查询和排序
    INDEX idx_products_sku (sku)
);

-- 仓库表
CREATE TABLE warehouses (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL
);

-- 库位表
CREATE TABLE locations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(20) DEFAULT 'FREE',
    FOREIGN KEY (warehouse_id) REFERENCES warehouses(id)
);

-- 库存表
CREATE TABLE inventory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    location_code VARCHAR(50) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (product_id) REFERENCES products(id),
    -- 唯一约束：商品+库位组合（同时服务 product_id 等值 JOIN）
    UNIQUE KEY uk_product_location (product_id, location_code)
    -- -- 库位编码前缀筛选 + 关联 locations.code
    -- INDEX idx_inventory_location_code (location_code)
);

-- 入库单主表
CREATE TABLE inbound_orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(50) NOT NULL UNIQUE,
    supplier_name VARCHAR(200),
    status VARCHAR(20) DEFAULT 'DRAFT',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    -- 优化按订单号前缀查询
    INDEX idx_inbound_orders_order_no (order_no)
);

-- 入库单明细表
CREATE TABLE inbound_order_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    location_code VARCHAR(50) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES inbound_orders(id),
    FOREIGN KEY (product_id) REFERENCES products(id)
);

-- 出库单主表
CREATE TABLE outbound_orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(50) NOT NULL UNIQUE,
    customer_name VARCHAR(200),
    status VARCHAR(20) DEFAULT 'DRAFT',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    -- 优化按订单号前缀查询
    INDEX idx_outbound_orders_order_no (order_no)
);

-- 出库单明细表
CREATE TABLE outbound_order_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    location_code VARCHAR(50) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES outbound_orders(id),
    FOREIGN KEY (product_id) REFERENCES products(id)
);