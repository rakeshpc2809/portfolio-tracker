package com.oreki.cas_injector.rebalancing.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;


@Service
public class RebalanceEngine {

    public TacticalSignal evaluate(AggregatedHolding holding, 
                                   StrategyTarget target, 
                                   MarketMetrics metrics, // Kept for future V2 expansion
                                   double totalPortfolioValue) {
        
        double targetPct = target.targetPortfolioPct(); 
        double sipPct = target.sipPct();
        
        double actualPct = (totalPortfolioValue > 0) 
                ? (holding.getCurrentValue() / totalPortfolioValue) * 100.0 
                : 0.0;
        
        // --- 1. CLASSIFICATION STATE MACHINE ---
        String status = "ACTIVE";
        
        if (targetPct == 0.0 && sipPct == 0.0 && actualPct > 0.0) {
            status = "DROPPED"; 
        } else if (sipPct > 0.0 && actualPct < targetPct) {
            status = "ACCUMULATOR"; 
        }

        // --- 2. THE GAP CALCULATOR ---
        double targetValueInRupees = (targetPct / 100.0) * totalPortfolioValue;
        double diffAmount = targetValueInRupees - holding.getCurrentValue();
        
        // --- 3. STRATEGIC & TAX RULES (±2.5% Tolerance) ---
        String action = "HOLD";
        List<String> justifications = new ArrayList<>();

        if ("DROPPED".equals(status)) {
            action = "EXIT";
            justifications.add("Strategic: Explicitly marked as DROPPED. Target is 0%.");
            
            // TAX OVERLAY FOR EXITS
            if (holding.getLtcgAmount() > 0) {
                justifications.add(String.format(Locale.US, "Tax Benefit: Exiting unlocks ₹%.2f in LTCG. Ensure you stay under your ₹1.25L annual limit.", holding.getLtcgAmount()));
            }
            if (holding.getStcgAmount() > 0) {
                String cat = holding.getAssetCategory() != null ? holding.getAssetCategory().toUpperCase() : "";
                if (cat.contains("DEBT")) {
                    justifications.add(String.format(Locale.US, "Tax Warning: Debt STCG of ₹%.2f will be taxed at your income slab rate.", holding.getStcgAmount()));
                } else {
                    justifications.add(String.format(Locale.US, "Tax Warning: Exiting incurs STCG tax on ₹%.2f. Consider waiting if units are nearing the LTCG threshold.", holding.getStcgAmount()));
                }
            }
        } 
        else if (actualPct > (targetPct + 2.5)) {
            action = "TRIM";
            justifications.add(String.format(Locale.US, "Strategic: Overweight by %.2f%%. Reallocate to Accumulators.", actualPct - targetPct));
            
            // TAX OVERLAY FOR TRIMS (The Smart Override)
            if (holding.getLtcgAmount() > 0) {
                justifications.add(String.format(Locale.US, "Tax Strategy: Trim oldest units first to utilize ₹%.2f of available Long-Term Capital Gains.", holding.getLtcgAmount()));
            } 
            else if (holding.getStcgAmount() > 0) {
                action = "HOLD"; // Smart Override
                justifications.add("Action overridden to HOLD to prevent Short-Term Capital Gains tax. Consider pausing the SIP instead of selling to fix the overweight drift.");
            }
        } 
        else if (actualPct < (targetPct - 2.5)) {
            action = "BUY";
            justifications.add(String.format(Locale.US, "Strategic: Underweight by %.2f%%. Status: %s.", targetPct - actualPct, status));
        } 
        else {
            justifications.add("Strategic: Weight is within the ±2.5% target tolerance.");
        }

        // 🚀 C. CONFIDENCE RATING
        if (metrics.coveragePct() > 0 && metrics.coveragePct() < 85.0) {
            justifications.add(String.format("⚠️ Low Confidence: Only %.1f%% of holdings were identified for valuation. Signal may be less accurate.", metrics.coveragePct()));
        }

        // Format to string to prevent scientific notation (e.g., "10500.50")
        String formattedAmount = String.format(Locale.US, "%.2f", Math.abs(diffAmount));

        return new TacticalSignal(
            holding.getSchemeName(),
            action,
            formattedAmount,
            round(targetPct),
            round(actualPct),
            round(sipPct),
            status,
            calculateStrategicScore(action, status),
            metrics.sortinoRatio(),    
            metrics.maxDrawdown(), 
            metrics.peRatio(),
            metrics.pbRatio(),
            metrics.zScore(),      
            metrics.coveragePct(), 
            metrics.lastBuyDate(), 
            metrics.valuationStatus(), // 🚀 PASS TO SIGNAL
            justifications
        );
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private int calculateStrategicScore(String action, String status) {
        if ("EXIT".equals(action)) return 90; 
        if ("BUY".equals(action) && "ACCUMULATOR".equals(status)) return 75; 
        if ("TRIM".equals(action)) return 60; 
        return 50; // Neutral HOLD
    }
}