package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.config.OutboundCacheProperties;
import com.wms.entity.Product;
import com.wms.repository.InventoryRepository;
import com.wms.repository.LocationRepository;
import com.wms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
/**
 * 出库申请缓存服务：防御缓存穿透、击穿、雪崩
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundApplyCacheService {

    private static final String NULL_MARKER = "1";
    private static final String PRODUCT_NULL_PREFIX = "wms:outbound:null:product:";
    private static final String LOCATION_NULL_PREFIX = "wms:outbound:null:location:";
    private static final String STOCK_LOCK_SUFFIX = ":init-lock";
    private static final String UNLOCK_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final StringRedisTemplate stringRedisTemplate;
    private final OutboundCacheProperties cacheProperties;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * 校验并获取出库商品（空值缓存防穿透）
     */
    public Product requireProduct(Long productId) {
        if (productId == null) {
            throw new BusinessException("商品ID不能为空");
        }
        if (hasNullMarker(PRODUCT_NULL_PREFIX + productId)) {
            throw new BusinessException(404, "商品不存在: " + productId);
        }
        Optional<Product> product = productRepository.findById(productId);
        if (product.isEmpty()) {
            cacheNullMarker(PRODUCT_NULL_PREFIX + productId);
            throw new BusinessException(404, "商品不存在: " + productId);
        }
        return product.get();
    }

    /**
     * 校验库位是否存在（空值缓存防穿透）
     */
    public String requireLocationCode(String locationCode) {
        String trimmedCode = locationCode == null ? "" : locationCode.trim();
        if (trimmedCode.isEmpty()) {
            throw new BusinessException("库位编码不能为空");
        }
        if (hasNullMarker(LOCATION_NULL_PREFIX + trimmedCode)) {
            throw new BusinessException(404, "库位不存在: " + trimmedCode);
        }
        if (!locationRepository.existsByCode(trimmedCode)) {
            cacheNullMarker(LOCATION_NULL_PREFIX + trimmedCode);
            throw new BusinessException(404, "库位不存在: " + trimmedCode);
        }
        return trimmedCode;
    }

    /**
     * 确保 Redis 库存缓存已加载（互斥锁防击穿，随机 TTL 防雪崩）
     */
    public void ensureStockInitialized(String stockKey, Long productId, String locationCode) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            return;
        }
        String lockKey = stockKey + STOCK_LOCK_SUFFIX;
        String lockValue = buildLockValue();
        boolean locked = tryAcquireLock(lockKey, lockValue);
        try {
            if (locked) {
                loadStockCacheIfAbsent(stockKey, productId, locationCode);
                return;
            }
            waitForStockCache(stockKey, lockKey, productId, locationCode);
        } finally {
            if (locked) {
                releaseLock(lockKey, lockValue);
            }
        }
    }

    /**
     * 入库或库存变更后删除 Redis 库存缓存
     */
    public void evictStockCache(String stockKey) {
        stringRedisTemplate.delete(stockKey);
    }

    /**
     * 从数据库加载库存并写入 Redis
     */
    private void loadStockCacheIfAbsent(String stockKey, Long productId, String locationCode) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            return;
        }
        int quantity = inventoryRepository.findByProductIdAndLocationCode(productId, locationCode)
                .map(inventory -> inventory.getQuantity() == null ? 0 : inventory.getQuantity())
                .orElse(0);
        Duration ttl = buildStockTtl();
        Boolean cached = stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(quantity), ttl);
        if (Boolean.FALSE.equals(cached)) {
            log.debug("库存缓存已由其他线程写入: key={}", stockKey);
        }
    }

    /**
     * 等待其他线程完成库存缓存回填
     */
    private void waitForStockCache(String stockKey, String lockKey, Long productId, String locationCode) {
        long deadline = System.currentTimeMillis() + cacheProperties.getLockWaitMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
                return;
            }
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey))) {
                loadStockCacheIfAbsent(stockKey, productId, locationCode);
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
                    return;
                }
                break;
            }
            sleepQuietly(cacheProperties.getLockPollMillis());
        }
        throw new BusinessException("库存缓存加载超时，请稍后重试");
    }

    /**
     * 尝试获取分布式锁
     */
    private boolean tryAcquireLock(String lockKey, String lockValue) {
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                Duration.ofSeconds(cacheProperties.getLockLeaseSeconds()));
        return Boolean.TRUE.equals(locked);
    }

    /**
     * 释放分布式锁
     */
    private void releaseLock(String lockKey, String lockValue) {
        RedisScript<Long> script = RedisScript.of(UNLOCK_SCRIPT, Long.class);
        stringRedisTemplate.execute(script, List.of(lockKey), lockValue);
    }

    /**
     * 判断是否命中空值缓存
     */
    private boolean hasNullMarker(String nullKey) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(nullKey));
    }

    /**
     * 写入空值缓存，拦截不存在数据的重复穿透查询
     */
    private void cacheNullMarker(String nullKey) {
        stringRedisTemplate.opsForValue().set(
                nullKey,
                NULL_MARKER,
                buildNullTtl());
    }

    /**
     * 生成库存缓存 TTL（基础值 + 随机抖动）
     */
    private Duration buildStockTtl() {
        return Duration.ofSeconds(randomTtl(
                cacheProperties.getStockBaseTtlSeconds(),
                cacheProperties.getStockTtlJitterSeconds()));
    }

    /**
     * 生成空值缓存 TTL（基础值 + 随机抖动）
     */
    private Duration buildNullTtl() {
        return Duration.ofSeconds(randomTtl(
                cacheProperties.getNullBaseTtlSeconds(),
                cacheProperties.getNullTtlJitterSeconds()));
    }

    /**
     * 计算带随机抖动的过期秒数
     */
    private long randomTtl(long baseSeconds, long jitterSeconds) {
        long jitter = jitterSeconds <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
        return Math.max(1L, baseSeconds + jitter);
    }

    /**
     * 生成锁令牌
     */
    private String buildLockValue() {
        return Thread.currentThread().getId() + "-" + System.nanoTime();
    }

    /**
     * 安全休眠，避免忙等占用 CPU
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("库存缓存加载被中断");
        }
    }
}
