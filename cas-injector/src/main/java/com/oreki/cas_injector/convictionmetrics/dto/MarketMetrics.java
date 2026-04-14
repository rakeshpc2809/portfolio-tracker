package com.oreki.cas_injector.convictionmetrics.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    LocalDate lastSellDate,       // Added for informative warnings

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
            navPercentile3yr, drawdownFromAth, returnZScore, lastBuyDate, null,
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

    public static Map<String, MarketMetrics> fromRows(List<Map<String, Object>> rows) {
        return rows.stream().collect(Collectors.toMap(
            r -> (String) r.get("amfi_code"),
            r -> new MarketMetrics(
                getSafeInt(r.get("conviction_score")),
                getSafeDouble(r.get("sortino_ratio")),
                getSafeDouble(r.get("cvar_5")),
                getSafeDouble(r.get("win_rate")),
                getSafeDouble(r.get("max_drawdown")),
                getSafeDouble(r.get("nav_percentile_3yr")),
                getSafeDouble(r.get("drawdown_from_ath")),
                getSafeDouble(r.get("return_z_score")),
                getSafeDate(r.get("last_buy")),
                getSafeDate(r.get("last_sell")),
                getSafeDouble(r.get("rolling_z_score_252")),
                getSafeDouble(r.get("hurst_exponent")),
                getSafeDouble(r.get("volatility_tax")),
                String.valueOf(r.getOrDefault("hurst_regime", "RANDOM_WALK")),
                getSafeDouble(r.get("historical_rarity_pct")),
                getSafeDouble(r.get("hurst_20d")),
                getSafeDouble(r.get("hurst_60d")),
                String.valueOf(r.getOrDefault("multi_scale_regime", "RANDOM_WALK")),
                getSafeDouble(r.get("ou_theta")),
                getSafeDouble(r.get("ou_mu")),
                getSafeDouble(r.get("ou_sigma")),
                getSafeDouble(r.get("ou_half_life")),
                getSafeBoolean(r.get("ou_valid")),
                getSafeDouble(r.get("ou_buy_threshold")),
                getSafeDouble(r.get("ou_sell_threshold")),
                String.valueOf(r.getOrDefault("hmm_state", "STRESSED_NEUTRAL")),
                getSafeDouble(r.get("hmm_bull_prob")),
                getSafeDouble(r.get("hmm_bear_prob")),
                getSafeDouble(r.get("hmm_transition_bear"))
            ),
            (a, b) -> a // Merge function to handle potential (though unexpected) duplicate AMFI codes
        ));
    }

    private static int getSafeInt(Object obj) {
        if (obj == null) return 0;
        return ((Number) obj).intValue();
    }

    private static double getSafeDouble(Object obj) {
        if (obj == null) return 0.0;
        return ((Number) obj).doubleValue();
    }

    private static boolean getSafeBoolean(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Boolean) return (Boolean) obj;
        if (obj instanceof Number) return ((Number) obj).intValue() != 0;
        return false;
    }

    private static LocalDate getSafeDate(Object obj) {
        if (obj == null) return null;
        if (obj instanceof java.sql.Date d) return d.toLocalDate();
        if (obj instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        if (obj instanceof java.time.LocalDate ld) return ld;
        return null;
    }
}
