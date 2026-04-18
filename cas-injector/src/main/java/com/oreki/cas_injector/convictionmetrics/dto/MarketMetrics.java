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
    double navPercentile1yr,
    double navPercentile3yr,
    double drawdownFromAth,
    double returnZScore,          // Legacy point-in-time Z-Score
    LocalDate lastBuyDate,
    LocalDate lastSellDate,       // Added for informative warnings

    // ── Conviction Sub-scores (7-Factor) ──
    double yieldScore,
    double riskScore,
    double valueScore,
    double painScore,
    double regimeScore,
    double frictionScore,
    double expenseScore,
    
    // ── Metadata ──
    double expenseRatio,
    double aumCr,

    // ── NEW fields ───────────
    double rollingZScore252,      // Rolling 252-day Z-Score (primary trigger)
    double hurstExponent,         // H value from R/S analysis
    double volatilityTax,         // Annual variance drag = 2σ²
    String hurstRegime,           // "MEAN_REVERTING" | "TRENDING" | "RANDOM_WALK"
    double historicalRarityPct,   // % of days with equally extreme Z-Score

    // ── OU fields ──────────────────────────────────────────────────────────
    double ouHalfLife,
    boolean ouValid,

    // ── HMM fields ──────────────────────────────────────────────────────────
    String hmmState,               // "CALM_BULL" | "STRESSED_NEUTRAL" | "VOLATILE_BEAR"
    double hmmBullProb,
    double hmmBearProb,
    double hmmTransitionBearProb
) {
    /** Backward-compatible factory: creates a MarketMetrics from the old 9-field query result. */
    public static MarketMetrics fromLegacy(
        int convictionScore, double sortinoRatio, double cvar5, double winRate,
        double maxDrawdown, double navPercentile1yr, double drawdownFromAth,
        double returnZScore, LocalDate lastBuyDate
    ) {
        return new MarketMetrics(
            convictionScore, sortinoRatio, cvar5, winRate, maxDrawdown,
            navPercentile1yr, navPercentile1yr, drawdownFromAth, returnZScore, lastBuyDate, null,
            50, 50, 50, 50, 50, 50, 50, // Sub-scores
            0, 0, // Metadata
            returnZScore,   // rollingZScore252: fall back to legacy z-score until first engine run
            0.5,            // hurstExponent: neutral
            0.0,            // volatilityTax
            "RANDOM_WALK",  // hurstRegime
            50.0,           // historicalRarityPct: neutral
            0.0, false,     // OU defaults
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
                getSafeDouble(r.get("nav_percentile_1yr")),
                getSafeDouble(r.get("nav_percentile_3yr")),
                getSafeDouble(r.get("drawdown_from_ath")),
                getSafeDouble(r.get("return_z_score")),
                getSafeDate(r.get("last_buy")),
                getSafeDate(r.get("last_sell")),
                getSafeDouble(r.get("yield_score")),
                getSafeDouble(r.get("risk_score")),
                getSafeDouble(r.get("value_score")),
                getSafeDouble(r.get("pain_score")),
                getSafeDouble(r.get("regime_score")),
                getSafeDouble(r.get("friction_score")),
                getSafeDouble(r.get("expense_score")),
                getSafeDouble(r.get("expense_ratio")),
                getSafeDouble(r.get("aum_cr")),
                getSafeDouble(r.get("rolling_z_score_252")),
                getSafeDouble(r.get("hurst_exponent")),
                getSafeDouble(r.get("volatility_tax")),
                String.valueOf(r.getOrDefault("hurst_regime", "RANDOM_WALK")),
                getSafeDouble(r.get("historical_rarity_pct")),
                getSafeDouble(r.get("ou_half_life")),
                getSafeBoolean(r.get("ou_valid")),
                String.valueOf(r.getOrDefault("hmm_state", "STRESSED_NEUTRAL")),
                getSafeDouble(r.get("hmm_bull_prob")),
                getSafeDouble(r.get("hmm_bear_prob")),
                getSafeDouble(r.get("hmm_transition_bear"))
            ),
            (a, b) -> a 
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
