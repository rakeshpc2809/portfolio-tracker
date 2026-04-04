package com.oreki.cas_injector.backfill.controller;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oreki.cas_injector.backfill.service.HistoricalBackfillerService;
import com.oreki.cas_injector.convictionmetrics.service.ConvictionScoringService;
import com.oreki.cas_injector.convictionmetrics.service.QuantitativeEngineService;



@RestController
@RequestMapping("/admin")
public class BackfillController {

    private final HistoricalBackfillerService backfillerService;
    private final QuantitativeEngineService quantitativeEngineService;
    private final ConvictionScoringService convictionScoringService;
    private final com.oreki.cas_injector.convictionmetrics.service.MarketClimateService marketClimateService;

    public BackfillController(HistoricalBackfillerService backfillerService,
                             QuantitativeEngineService quantitativeEngineService,
                             ConvictionScoringService convictionScoringService,
                             com.oreki.cas_injector.convictionmetrics.service.MarketClimateService marketClimateService) {
        this.backfillerService = backfillerService;
        this.quantitativeEngineService=quantitativeEngineService;
        this.convictionScoringService=convictionScoringService;
        this.marketClimateService = marketClimateService;
    }

    @PostMapping("/sync-market-climate")
    public ResponseEntity<String> syncMarketClimate() {
        marketClimateService.syncMarketClimateData();
        return ResponseEntity.ok("Market climate sync triggered! Check logs for details.");
    }

    @PostMapping("/trigger-historical-backfill")
    public ResponseEntity<String> triggerBackfill() {
        // Note: Because this takes ~4 minutes, the HTTP request might timeout in Postman, 
        // but the Spring Boot logs will show it running perfectly in the background.
        // For a production app you'd run this Async, but for this one-time script, sync is fine.
        
        new Thread(() -> backfillerService.executeOneShotBackfill()).start();
        
        return ResponseEntity.ok("🚀 Backsfill started in the background! Check your Spring Boot console logs.");
    }

    @GetMapping("/force-sync")
public String forceSync() {
    quantitativeEngineService.runNightlyMathEngine();
    convictionScoringService.calculateAndSaveFinalScores("CFXPR4533R");
    return "Engine Processed Successfully";
}
}