package com.oreki.cas_injector.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
@EnableScheduling
@Slf4j
public class CacheConfig {

  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    
    manager.registerCustomCache("dashboardSummary",
        Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(50)
            .recordStats()
            .build());
            
    manager.registerCustomCache("portfolioCache",
        Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(50)
            .recordStats()
            .build());
            
    manager.registerCustomCache("navCache",
        Caffeine.newBuilder()
            .expireAfterWrite(6, TimeUnit.HOURS)
            .maximumSize(500)
            .recordStats()
            .build());
            
    manager.registerCustomCache("schemeDetailsCache",
        Caffeine.newBuilder()
            .expireAfterWrite(6, TimeUnit.HOURS)
            .maximumSize(500)
            .recordStats()
            .build());
            
    return manager;
  }

  // Manual evict job
  @CacheEvict(value = {"dashboardSummary", "portfolioCache"}, allEntries = true)
  @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
  public void evictMorning() {
    log.info("🌅 Morning cache eviction — fresh data for the trading day.");
  }
}
