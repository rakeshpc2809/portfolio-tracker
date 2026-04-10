package com.oreki.cas_injector.convictionmetrics.dto;

import java.time.LocalDate;

public record MarketMetrics(
    int    convictionScore,       // 0-100 (legacy, still used for BUY gate)
    double sortinoRatio,
    double cvar5,
    double winRate,
    double maxDrawdown,
    double navPercentile3yr,
    double drawdownFromAth,
    double returnZScore,          // Legacy point-in-time Z-Score
    LocalDate lastBuyDate,

    // ── NEW fields (default to safe neutrals if not yet computed) ───────────
    double rollingZScore252,      // Rolling 252-day Z-Score (primary trigger)
    double hurstExponent,         // H value from R/S analysis
    double volatilityTax,         // Annual variance drag = 2σ²
    String hurstRegime,           // "MEAN_REVERTING" | "TRENDING" | "RANDOM_WALK"
    double historicalRarityPct    // % of days with equally extreme Z-Score
) {
    /** Backward-compatible factory: creates a MarketMetrics from the old 9-field query result. */
    public static MarketMetrics fromLegacy(
        int convictionScore, double sortinoRatio, double cvar5, double winRate,
        double maxDrawdown, double navPercentile3yr, double drawdownFromAth,
        double returnZScore, LocalDate lastBuyDate
    ) {
        return new MarketMetrics(
            convictionScore, sortinoRatio, cvar5, winRate, maxDrawdown,
            navPercentile3yr, drawdownFromAth, returnZScore, lastBuyDate,
            returnZScore,   // rollingZScore252: fall back to legacy z-score until first engine run
            0.5,            // hurstExponent: neutral
            0.0,            // volatilityTax
            "RANDOM_WALK",  // hurstRegime
            50.0            // historicalRarityPct: neutral
        );
    }
}