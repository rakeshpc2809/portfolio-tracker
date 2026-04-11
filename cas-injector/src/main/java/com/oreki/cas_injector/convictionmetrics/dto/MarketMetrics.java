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
    double historicalRarityPct,   // % of days with equally extreme Z-Score
    double hurst20d,
    double hurst60d,
    String multiScaleRegime,

    // ── OU fields (Task 2B) ──────────────────────────────────────────────────
    double ouTheta,
    double ouMu,
    double ouSigma,
    double ouHalfLife,
    boolean ouValid,
    double ouBuyThreshold,
    double ouSellThreshold,

    // ── HMM fields (Task 3C) ──────────────────────────────────────────────────
    String hmmState,               // "CALM_BULL" | "STRESSED_NEUTRAL" | "VOLATILE_BEAR"
    double hmmBullProb,
    double hmmBearProb,
    double hmmTransitionBearProb
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
            50.0,           // historicalRarityPct: neutral
            0.5,            // hurst20d
            0.5,            // hurst60d
            "RANDOM_WALK",  // multiScaleRegime
            0.0, 0.0, 0.0, 0.0, false, 0.0, 0.0, // OU defaults
            "STRESSED_NEUTRAL", 0.33, 0.33, 0.33 // HMM defaults
        );
    }
}
