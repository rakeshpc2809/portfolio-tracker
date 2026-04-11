package com.oreki.cas_injector.backfill.controller;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.cache.CacheManager;

import com.oreki.cas_injector.backfill.service.HistoricalBackfillerService;
import com.oreki.cas_injector.convictionmetrics.service.ConvictionScoringService;
import com.oreki.cas_injector.convictionmetrics.service.QuantitativeEngineService;

import lombok.RequiredArgsConstructor;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class BackfillController {

    private final HistoricalBackfillerService backfillerService;
    private final QuantitativeEngineService quantitativeEngineService;
    private final ConvictionScoringService convictionScoringService;
    private final CacheManager cacheManager;

    @PostMapping("/trigger-historical-backfill")
    public ResponseEntity<String> triggerBackfill() {
        if (backfillerService.getIsRunning().get()) {
            return ResponseEntity.badRequest().body("Backfill is already in progress.");
        }
        new Thread(() -> backfillerService.executeOneShotBackfill()).start();
        return ResponseEntity.ok("Historical backfill started.");
    }

    @PostMapping("/force-sync")
    public ResponseEntity<String> forceSync(@RequestParam(required = false, defaultValue = "CFXPR4533R") String pan) {
        if (quantitativeEngineService.getIsRunning().get()) {
            return ResponseEntity.badRequest().body("Engine sync is already in progress.");
        }
        new Thread(() -> {
            quantitativeEngineService.runNightlyMathEngine();
            convictionScoringService.calculateAndSaveFinalScores(pan);
            
            // Clear caches so frontend gets fresh data
            if (cacheManager.getCache("portfolioCache") != null) {
                cacheManager.getCache("portfolioCache").clear();
            }
            if (cacheManager.getCache("dashboardSummary") != null) {
                cacheManager.getCache("dashboardSummary").clear();
            }
        }).start();
        return ResponseEntity.ok("Quantitative engine sync started for PAN: " + pan);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
            "backfill", Map.of(
                "isRunning", backfillerService.getIsRunning().get(),
                "progress", backfillerService.getCurrentProgress().get(),
                "total", backfillerService.getTotalToProcess().get(),
                "message", backfillerService.getLastStatusMessage()
            ),
            "engine", Map.of(
                "isRunning", quantitativeEngineService.getIsRunning().get(),
                "step", quantitativeEngineService.getCurrentStep().get(),
                "message", quantitativeEngineService.getLastStatusMessage()
            )
        ));
    }
}
