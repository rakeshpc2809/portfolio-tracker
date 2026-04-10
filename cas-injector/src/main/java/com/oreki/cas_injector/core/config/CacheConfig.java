package com.oreki.cas_injector.core.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.extern.slf4j.Slf4j;
@Slf4j
@Configuration
public class CacheConfig {
@CacheEvict(value = "navCache", allEntries = true)
@Scheduled(cron = "0 0 0 * * *") // Runs at midnight
public void evictNavCache() {
    log.info("Nav Cache cleared for the new day.");
}

@Bean
    public CacheManager cacheManager() {
        // This creates a simple in-memory cache provider
return new ConcurrentMapCacheManager("schemeDetailsCache", "navCache", "portfolioCache", "dashboardSummary");    }
}
