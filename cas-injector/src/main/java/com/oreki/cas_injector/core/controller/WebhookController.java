package com.oreki.cas_injector.core.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/webhook")
@CrossOrigin(origins = "*")
@Slf4j
public class WebhookController {

    @PostMapping("/amfi-sync")
    @CacheEvict(value = {"portfolioState", "portfolioSignals", "portfolioCache", "dashboardSummary"}, allEntries = true)
    public ResponseEntity<Void> handleAmfiSyncWebhook() {
        log.info("🔄 AMFI Sync Webhook triggered. Evicting portfolio, signals, and dashboard caches.");
        return ResponseEntity.ok().build();
    }
}
