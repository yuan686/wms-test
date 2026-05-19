package com.wms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 出库申请缓存相关配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "wms.outbound.cache")
public class OutboundCacheProperties {

    /** 库存缓存基础 TTL（秒） */
    private long stockBaseTtlSeconds = 1800;

    /** 库存缓存 TTL 随机抖动上限（秒），用于缓解缓存雪崩 */
    private long stockTtlJitterSeconds = 600;

    /** 空值/不存在标记缓存基础 TTL（秒），用于缓解缓存穿透 */
    private long nullBaseTtlSeconds = 120;

    /** 空值标记 TTL 随机抖动上限（秒） */
    private long nullTtlJitterSeconds = 60;

    /** 元数据（商品/库位）缓存基础 TTL（秒） */
    private long metaBaseTtlSeconds = 3600;

    /** 元数据缓存 TTL 随机抖动上限（秒） */
    private long metaTtlJitterSeconds = 900;

    /** 库存初始化分布式锁等待时间（毫秒） */
    private long lockWaitMillis = 3000;

    /** 库存初始化分布式锁租约（秒） */
    private long lockLeaseSeconds = 10;

    /** 等待其他线程回填缓存时的轮询间隔（毫秒） */
    private long lockPollMillis = 50;
}
