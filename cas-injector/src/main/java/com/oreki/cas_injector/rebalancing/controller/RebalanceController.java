package com.oreki.cas_injector.rebalancing.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oreki.cas_injector.dashboard.dto.UnifiedTacticalPayload;
import com.oreki.cas_injector.rebalancing.dto.SipLineItem;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.rebalancing.service.PortfolioOrchestrator;
import com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity;
import com.oreki.cas_injector.taxmanagement.service.TaxLossHarvestingService;

import lombok.AllArgsConstructor;

@RestController
@AllArgsConstructor
@RequestMapping("/portfolio")
@CrossOrigin(origins = "*") // Allows your React frontend to call this endpoint without CORS errors
public class RebalanceController {

    private final PortfolioOrchestrator orchestrator;
    private final TaxLossHarvestingService taxLossHarvestingService;
    /**
     * Endpoint to fetch the daily tactical signals for a specific investor.
     * Example URL: GET /api/portfolio/ABCDE1234F/tactical-signals
     */
    @GetMapping("/{pan}/tactical-signals")
    public ResponseEntity<List<TacticalSignal>> getTacticalSignals(
            @PathVariable("pan") String investorPan,
            @RequestParam(value = "monthlySip", defaultValue = "75000") double monthlySip,
            @RequestParam(value = "lumpsum", defaultValue = "0") double lumpsum) {
        
        UnifiedTacticalPayload payload = orchestrator.generateUnifiedPayload(investorPan, monthlySip, lumpsum);
        
        java.util.List<TacticalSignal> combined = new java.util.ArrayList<>();
        combined.addAll(payload.getOpportunisticSignals());
        combined.addAll(payload.getActiveSellSignals());
        combined.addAll(payload.getExitQueue());

        if (combined.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        
        return ResponseEntity.ok(combined);
    }

    @GetMapping("/{pan}/tax-loss-harvesting")
        public List<TlhOpportunity> getTlhOpportunities(@PathVariable String pan) {
            return taxLossHarvestingService.scanForOpportunities(pan);
        }

    @GetMapping("/{pan}/unified-dashboard")
    public ResponseEntity<UnifiedTacticalPayload> getUnifiedDashboard(
            @PathVariable String pan,
            @RequestParam(defaultValue = "75000") double monthlySip,
            @RequestParam(defaultValue = "0") double lumpsum) {
        
        return ResponseEntity.ok(orchestrator.generateUnifiedPayload(pan, monthlySip, lumpsum));
    }

    private double parseAmount(String amt) {
        try {
            return Double.parseDouble(amt);
        } catch (Exception e) {
            return 0;
        }
    }
}