package com.moniewise.moniewise_backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@Slf4j
public class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("banks");
        // Simple memory cache. Every time you restart the server on Render, 
        // it clears and fetches fresh. Perfect for bank lists.
        return cacheManager;
    }

    // 🔥 OPTIONAL: A "Cleanup" task that clears the cache every 12 hours
    @CacheEvict(value = "banks", allEntries = true)
    @Scheduled(fixedRate = 43200000) // 12 hours in ms
    public void emptyBanksCache() {
        log.info("🧹 Clearing bank cache to ensure fresh data...");
    }
}