package com.oreki.cas_injector.rebalancing.service;

import org.springframework.stereotype.Service;
import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * HALF-KELLY POSITION SIZING WITH CVaR PENALTY
 * 
 * Standard Kelly: f = (b*p - q) / b
 *   where b = odds (expected return / MAR), p = win probability, q = 1-p
 * 
 * We derive p from WinRate (against 7% MAR — already in our metrics!)
 * We derive b from Sortino ratio as a proxy for risk-adjusted odds
 * 
 * Half-Kelly: multiply result by 0.5 (proven to reduce variance, preserve growth)
 * CVaR penalty: scale down if tail risk is severe
 * 
 * Returns a multiplier 0.2 → 1.5 applied against the base drift amount.
 */
@Service
@Slf4j
public class PositionSizingService {

    private static final double MAR = 0.07; // 7% Minimum Acceptable Return

    public double calculateKellyMultiplier(MarketMetrics metrics) {
        double p = Math.max(0.1, Math.min(0.95, metrics.winRate() / 100.0)); // win probability
        double q = 1.0 - p;
        
        // b = expected gain per unit risked (proxy from Sortino)
        // Sortino of 1.0 means exactly MAR. 2.0 = 2× MAR. Scale odds accordingly.
        double b = Math.max(0.5, metrics.sortinoRatio());

        // Full Kelly fraction
        double fullKelly = (b * p - q) / b;

        if (fullKelly <= 0) {
            return 0.10; // Negative edge: Minimum viable position only (10% of drift)
        }

        // Half-Kelly (standard institutional practice)
        double halfKelly = fullKelly * 0.5;

        // CVaR penalty: if CVaR is worse than -3%, apply a dampener
        // CVaR of -3% = 0.9 multiplier. CVaR of -5% = 0.75. CVaR of -7% = 0.6.
        double cvarPenalty = 1.0;
        if (metrics.cvar5() < -3.0) {
            cvarPenalty = Math.max(0.4, 1.0 + (metrics.cvar5() / 20.0)); // linear taper
        }

        double multiplier = halfKelly * cvarPenalty;

        // Clamp to [0.1, 1.5] — never size below 10% or above 150% of drift
        multiplier = Math.max(0.1, Math.min(1.5, multiplier));
        
        log.debug("Kelly sizing: p={:.2f} b={:.2f} fullKelly={:.2f} halfKelly={:.2f} cvarPenalty={:.2f} → mult={:.2f}",
            p, b, fullKelly, halfKelly, cvarPenalty, multiplier);
        
        return multiplier;
    }

    /** 
     * Calculates the actual rupee amount to deploy
     * @param baseDriftAmount  absolute drift gap in rupees (target - actual)
     * @param availableCash    total available capital (SIP + lumpsum)
     * @param metrics          fund's MarketMetrics
     */
    public double calculateExecutionAmount(double baseDriftAmount, double availableCash, MarketMetrics metrics) {
        double kellyMult = calculateKellyMultiplier(metrics);
        double rawAmount = baseDriftAmount * kellyMult;
        
        // If availableCash is 0 or less, we just return the raw sized amount 
        // (PortfolioOrchestrator will handle budget allocation later)
        if (availableCash <= 0) return rawAmount;

        // Hard cap: never deploy more than available cash
        return Math.min(rawAmount, availableCash);
    }

    /**
     * Legacy compatibility method
     */
    public double calculateAdjustedBuySize(double baseAmount, MarketMetrics metrics) {
        return baseAmount * calculateKellyMultiplier(metrics);
    }
}
