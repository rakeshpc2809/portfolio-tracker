package com.oreki.cas_injector.rebalancing.service;

import org.springframework.stereotype.Service;
import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * SIMPLIFIED PROPORTIONAL GAP SIZING
 * 
 * Replaces complex Kelly logic with a conservative graduated entry approach.
 * buyAmount = gap * (deficitPct / 100)
 * 
 * This ensures we buy more when we are further from the target, but never 
 * the full gap in one go, providing natural rupee-cost averaging.
 */
@Service
@Slf4j
public class PositionSizingService {

    /** 
     * Calculates the actual rupee amount to deploy
     * @param gap              absolute rupee gap (targetValue - actualValue)
     * @param deficitPct       the percentage point difference (targetPct - actualPct)
     * @param availableBudget  total available capital (SIP + lumpsum)
     */
    public double calculateExecutionAmount(double gap, double deficitPct, double availableBudget) {
        if (gap <= 0 || deficitPct <= 0) return 0;
        
        // Buy a fraction of the gap proportional to the deficit itself
        // e.g., if 10% underweight, buy 10% of the gap.
        double proportionalFactor = Math.min(1.0, deficitPct / 100.0);
        double buyAmount = gap * proportionalFactor;
        
        if (availableBudget <= 0) return buyAmount;
        return Math.min(buyAmount, availableBudget);
    }

    /**
     * Compatibility method for RebalanceEngine
     */
    public double calculateAdjustedBuySize(double gap, double deficitPct) {
        return calculateExecutionAmount(gap, deficitPct, 0);
    }
}
