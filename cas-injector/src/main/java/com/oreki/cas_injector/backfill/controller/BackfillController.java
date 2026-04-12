package com.oreki.cas_injector.backfill.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;

import com.oreki.cas_injector.backfill.service.HistoricalBackfillerService;
import com.oreki.cas_injector.convictionmetrics.service.ConvictionScoringService;
import com.oreki.cas_injector.convictionmetrics.service.QuantitativeEngineService;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.core.model.Investor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class BackfillController {

    private final HistoricalBackfillerService backfillerService;
    private final QuantitativeEngineService quantitativeEngineService;
    private final ConvictionScoringService convictionScoringService;
    private final CacheManager cacheManager;
    private final InvestorRepository investorRepository;

    @PostMapping("/trigger-historical-backfill")
    public ResponseEntity<String> triggerBackfill() {
        if (backfillerService.getIsRunning().get()) {
            return ResponseEntity.badRequest().body("Backfill is already in progress.");
        }
        new Thread(() -> backfillerService.executeOneShotBackfill()).start();
        return ResponseEntity.ok("Historical backfill started.");
    }

    @PostMapping("/force-sync")
    public ResponseEntity<String> forceSync(@RequestParam(required = false) String pan) {
        if (quantitativeEngineService.getIsRunning().get()) {
            return ResponseEntity.badRequest().body("Engine sync is already in progress.");
        }
        
        new Thread(() -> {
            // Step 1: Run the global quant engine (market-wide metrics)
            quantitativeEngineService.runNightlyMathEngine();
            
            // Step 2: Run per-investor scoring
            List<String> pansToScore = (pan != null && !pan.isBlank())
                ? List.of(pan.trim().toUpperCase())
                : investorRepository.findAll().stream()
                    .map(Investor::getPan)
                    .toList();
            
            for (String investorPan : pansToScore) {
                try {
                    log.info("🧮 Running conviction scoring for PAN: {}", investorPan);
                    convictionScoringService.calculateAndSaveFinalScores(investorPan);
                } catch (Exception e) {
                    log.error("❌ Conviction scoring failed for PAN {}: {}", investorPan, e.getMessage());
                }
            }
            
            // Step 3: Evict cache so next dashboard load is fresh
            cacheManager.getCacheNames().forEach(name -> {
                Cache c = cacheManager.getCache(name);
                if (c != null) c.clear();
            });
            log.info("✅ Full sync complete for {} investors.", pansToScore.size());
        }).start();
        
        return ResponseEntity.ok("Full sync started for " + 
            (pan != null ? pan : "all investors") + ".");
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
