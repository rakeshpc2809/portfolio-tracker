# Design Document: Rebalancing Strategy Revamp v3.0
## Rolling Z-Score · Hurst Exponent Safety Gate · Volatility Tax · Explanation Engine

**Author:** Quantitative Full-Stack Team  
**Status:** Implementation Blueprint  
**Scope:** `cas-injector` (Backend) + `portfolio-dashboard` (Frontend)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Database Migrations](#3-database-migrations)
4. [Backend: New Services & Calculations](#4-backend-new-services--calculations)
   - 4.1 [HurstExponentService.java (NEW)](#41-hurstexponentservicejava-new)
   - 4.2 [Updated QuantitativeEngineService.java](#42-updated-quantitativeengineservicejava)
   - 4.3 [Updated ConvictionMetricsRepository — New SQL Queries](#43-updated-convictionmetricsrepository--new-sql-queries)
   - 4.4 [New DTOs: ReasoningMetadata & updated MarketMetrics](#44-new-dtos-reasoningmetadata--updated-marketmetrics)
   - 4.5 [Updated TacticalSignal.java](#45-updated-tacticalsignaljava)
   - 4.6 [Revamped RebalanceEngine.java](#46-revamped-rebalanceenginejava)
5. [Frontend: Explanation Engine UI](#5-frontend-explanation-engine-ui)
   - 5.1 [Updated TypeScript Types](#51-updated-typescript-types)
   - 5.2 [New RecommendationDetailCard.tsx](#52-new-recommendationdetailcardtsx)
   - 5.3 [Updated formatters.ts](#53-updated-formattersts)
   - 5.4 [Wiring into RebalanceView.tsx](#54-wiring-into-rebalanceviewtsx)
6. [Signal vs. Noise Display Logic](#6-signal-vs-noise-display-logic)
7. [Visual Metaphor Specification](#7-visual-metaphor-specification)
8. [Migration & Rollout Plan](#8-migration--rollout-plan)

---

## 1. Executive Summary

The current `RebalanceEngine` makes buy/sell decisions using a **static `convictionScore`** (0-100, computed
nightly) and a ±2.5% drift tolerance. This works, but it has two fundamental flaws:

- **Flaw 1 — Static Conviction is Backward-Looking:** It scores a fund on the last N days of performance,
  ignoring whether the current price is *statistically cheap or expensive right now* relative to its own history.
- **Flaw 2 — Drift-Blind:** Overweight signals say "you drifted" but don't explain *why drifting here is
  actually valuable* (the volatility tax argument).

This revamp introduces three new mathematical primitives:

| Primitive | What It Measures | Decision Impact |
|---|---|---|
| **Rolling 252-day Z-Score** | How far today's NAV return is from the fund's 1-year mean, in standard deviations | Primary buy/sell trigger |
| **Hurst Exponent (H)** | Whether the fund's price series is mean-reverting (H<0.5) or trending (H>0.5) | Safety gate that overrides Z-Score logic |
| **Volatility Tax (2σ²)** | The annual return drag caused by variance — quantifies the "free money" in rebalancing | Harvest narrative for SELL signals |

The frontend is simultaneously redesigned so every signal card shows a plain-English "Why" explanation using
visual metaphors instead of quant jargon.

---

## 2. Architecture Overview

```
fund_history (5-yr daily NAV)
        │
        ▼
┌─────────────────────────────────────────────────────────┐
│  QuantitativeEngineService.runNightlyMathEngine()       │
│                                                         │
│  Step 1: Existing Sortino/CVaR/MDD SQL                  │
│  Step 2: Existing NAV Signals (Percentile, ATH, Z-Score)│
│  Step 3 (NEW): Rolling 252-day Z-Score (SQL)            │
│  Step 4 (NEW): Volatility Tax 2σ² (SQL)                 │
│  Step 5 (NEW): Hurst Exponent (Java, HurstExponentSvc)  │
│  Step 6: Bucket CQS Z-Scorer (existing)                 │
└────────────────────────┬────────────────────────────────┘
                         │ writes to fund_conviction_metrics
                         ▼
┌─────────────────────────────────────────────────────────┐
│  RebalanceEngine.evaluate()                             │
│                                                         │
│  1. Load hurstExponent → determine regime               │
│  2. Load rollingZScore252 → primary signal              │
│  3. Load volatilityTax → harvest narrative              │
│  4. Build ReasoningMetadata → rich "Why" object         │
│  5. Return TacticalSignal (now carrying metadata)       │
└────────────────────────┬────────────────────────────────┘
                         │ TacticalSignal.reasoningMetadata
                         ▼
┌─────────────────────────────────────────────────────────┐
│  React Frontend                                         │
│  RecommendationDetailCard → Signal vs. Noise Display   │
│  Visual Metaphors: Rubber Band / Harvest / Wave Rider  │
└─────────────────────────────────────────────────────────┘
```

---

## 3. Database Migrations

Add three new columns to the `fund_conviction_metrics` table. Run these SQL migrations before deploying
the new backend:

```sql
-- Migration V4__add_hurst_volatility_columns.sql

ALTER TABLE fund_conviction_metrics
  ADD COLUMN IF NOT EXISTS rolling_z_score_252   DOUBLE PRECISION DEFAULT 0.0,
  ADD COLUMN IF NOT EXISTS hurst_exponent        DOUBLE PRECISION DEFAULT 0.5,
  ADD COLUMN IF NOT EXISTS volatility_tax        DOUBLE PRECISION DEFAULT 0.0,
  ADD COLUMN IF NOT EXISTS hurst_regime          VARCHAR(20)      DEFAULT 'RANDOM_WALK',
  ADD COLUMN IF NOT EXISTS historical_rarity_pct DOUBLE PRECISION DEFAULT 50.0;

-- Index for fast lookups in the nightly engine
CREATE INDEX IF NOT EXISTS idx_fcm_amfi_calcdate
  ON fund_conviction_metrics (amfi_code, calculation_date DESC);
```

**Column Definitions:**

- `rolling_z_score_252` — Today's daily return expressed as standard deviations from the fund's own
  252-day rolling mean. Range: typically –4.0 to +4.0.
- `hurst_exponent` — R/S analysis result over a 252-day window. Range: 0.0–1.0.
  H < 0.45 → Mean Reverting. H > 0.55 → Trending. 0.45–0.55 → Random Walk.
- `volatility_tax` — Annualised variance drain = `2 * σ²` where σ is the 252-day rolling daily
  standard deviation, annualised. Expressed as a decimal (e.g., 0.032 = 3.2% p.a. drag).
- `hurst_regime` — Enum-style string: `'MEAN_REVERTING'`, `'TRENDING'`, `'RANDOM_WALK'`.
- `historical_rarity_pct` — What percentage of the last 252 trading days had a Z-Score as extreme
  as today's. E.g., a Z of –2.0 maps to ~2.3% rarity (very cheap, very rare).

---

## 4. Backend: New Services & Calculations

### 4.1 `HurstExponentService.java` (NEW)

**Location:** `cas-injector/src/main/java/com/oreki/cas_injector/convictionmetrics/service/HurstExponentService.java`

**Algorithm:** Rescaled Range (R/S) Analysis — the simplest method accurate enough for daily fund NAVs.

**Pseudocode:**
```
For a price series P of length N (e.g., 252 daily NAVs):
  1. Compute log returns: r[i] = ln(P[i] / P[i-1])
  2. Compute mean return: μ = mean(r)
  3. Compute cumulative deviation: Y[i] = Σ(r[j] - μ) for j=1..i
  4. R = max(Y) - min(Y)          ← Range of cumulative deviations
  5. S = std(r)                    ← Standard deviation of returns
  6. H = log(R/S) / log(N)        ← Hurst Exponent
```

```java
package com.oreki.cas_injector.convictionmetrics.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HurstExponentService {

    private final JdbcTemplate jdbcTemplate;

    // Regime thresholds — tuned for mutual fund daily NAV series
    private static final double MEAN_REVERTING_THRESHOLD = 0.45;
    private static final double TRENDING_THRESHOLD       = 0.55;
    private static final int    LOOKBACK_DAYS            = 252;

    /**
     * Computes and persists Hurst Exponent + Volatility Tax for all funds
     * that have at least LOOKBACK_DAYS of history.
     *
     * Called by QuantitativeEngineService as Step 5 of the nightly engine.
     */
    public void computeAndPersistHurstMetrics() {
        // 1. Fetch all AMFI codes with sufficient history
        String amfiSql = """
            SELECT DISTINCT amfi_code
            FROM fund_history
            GROUP BY amfi_code
            HAVING COUNT(*) >= ?
            """;
        List<String> amfiCodes = jdbcTemplate.queryForList(amfiSql, String.class, LOOKBACK_DAYS);
        log.info("🔬 Computing Hurst Exponent for {} funds...", amfiCodes.size());

        int success = 0;
        for (String amfi : amfiCodes) {
            try {
                // 2. Fetch last LOOKBACK_DAYS NAV values in ascending date order
                String navSql = """
                    SELECT nav FROM fund_history
                    WHERE amfi_code = ?
                    ORDER BY nav_date DESC
                    LIMIT ?
                    """;
                List<Double> navs = jdbcTemplate.queryForList(navSql, Double.class, amfi, LOOKBACK_DAYS + 1);
                if (navs.size() < LOOKBACK_DAYS + 1) continue;

                // Convert to log daily returns (newest-first → reverse for chronological order)
                double[] returns = new double[LOOKBACK_DAYS];
                for (int i = 0; i < LOOKBACK_DAYS; i++) {
                    double todayNav = navs.get(i);
                    double prevNav  = navs.get(i + 1);
                    if (prevNav > 0) {
                        returns[LOOKBACK_DAYS - 1 - i] = Math.log(todayNav / prevNav);
                    }
                }

                double hurst       = calculateHurst(returns);
                double volTax      = calculateVolatilityTax(returns);
                String regime      = classifyRegime(hurst);
                double rarityPct   = calculateHistoricalRarityPct(returns);

                // 3. Persist to fund_conviction_metrics (latest calculation_date row)
                jdbcTemplate.update("""
                    UPDATE fund_conviction_metrics
                    SET hurst_exponent        = ?,
                        volatility_tax        = ?,
                        hurst_regime          = ?,
                        historical_rarity_pct = ?
                    WHERE amfi_code = ?
                    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
                    """, hurst, volTax, regime, rarityPct, amfi);

                success++;
            } catch (Exception e) {
                log.warn("⚠️ Hurst calculation failed for AMFI {}: {}", amfi, e.getMessage());
            }
        }
        log.info("✅ Hurst Engine complete. Processed {}/{} funds.", success, amfiCodes.size());
    }

    /**
     * R/S Analysis — Hurst Exponent calculation.
     * @param returns  Array of log daily returns (chronological order, length = LOOKBACK_DAYS)
     * @return         Hurst exponent H ∈ [0, 1]
     */
    double calculateHurst(double[] returns) {
        int n = returns.length;
        double mean = Arrays.stream(returns).average().orElse(0);

        // Cumulative deviation series
        double[] Y = new double[n];
        double cumDev = 0;
        for (int i = 0; i < n; i++) {
            cumDev += (returns[i] - mean);
            Y[i] = cumDev;
        }

        // Range (R) = max cumulative deviation − min cumulative deviation
        double max = Arrays.stream(Y).max().orElse(0);
        double min = Arrays.stream(Y).min().orElse(0);
        double R = max - min;

        // Standard deviation (S) of the return series
        double variance = Arrays.stream(returns)
            .map(r -> (r - mean) * (r - mean))
            .average().orElse(0);
        double S = Math.sqrt(variance);

        if (S == 0 || R == 0) return 0.5; // degenerate series → assume random walk

        // H = log(R/S) / log(N)
        return Math.log(R / S) / Math.log(n);
    }

    /**
     * Volatility Tax = 2σ² (annualised).
     * This is the Arithmetic-to-Geometric mean conversion gap — the "drag" that
     * variance imposes on compounded returns. Rebalancing partially recovers this.
     *
     * Reference: Luenberger (2013) "Investment Science", Ch. 15 — Volatility Pumping.
     *
     * @param returns  Array of daily log returns
     * @return         Annual volatility tax as a decimal (e.g., 0.032 = 3.2%)
     */
    double calculateVolatilityTax(double[] returns) {
        double mean = Arrays.stream(returns).average().orElse(0);
        double variance = Arrays.stream(returns)
            .map(r -> (r - mean) * (r - mean))
            .average().orElse(0);
        // Annualise: multiply daily variance by 252 trading days
        double annualisedVariance = variance * 252;
        return 2 * annualisedVariance;
    }

    /**
     * Returns what % of days over the lookback period had a return at least
     * as extreme (in absolute value) as the most recent day.
     * Used to label "Statistically Cheap — only happens 2.3% of the time."
     */
    double calculateHistoricalRarityPct(double[] returns) {
        if (returns.length == 0) return 50.0;
        double latestReturn = returns[returns.length - 1];
        double mean = Arrays.stream(returns).average().orElse(0);
        double std  = Math.sqrt(Arrays.stream(returns)
            .map(r -> (r - mean) * (r - mean)).average().orElse(1));
        double latestZ = std > 0 ? Math.abs((latestReturn - mean) / std) : 0;

        long extremeDays = Arrays.stream(returns)
            .filter(r -> std > 0 && Math.abs((r - mean) / std) >= latestZ)
            .count();
        return (double) extremeDays / returns.length * 100.0;
    }

    String classifyRegime(double h) {
        if (h < MEAN_REVERTING_THRESHOLD) return "MEAN_REVERTING";
        if (h > TRENDING_THRESHOLD)       return "TRENDING";
        return "RANDOM_WALK";
    }
}
```

---

### 4.2 Updated `QuantitativeEngineService.java`

Wire in the new `HurstExponentService` as **Step 5** of the nightly engine:

```java
// In QuantitativeEngineService — add new field:
private final HurstExponentService hurstExponentService;

// In runNightlyMathEngine() — add after Step 3 (Bucket CQS):

// Step 4 (NEW): Rolling Z-Score & Volatility Tax via SQL
currentStep.set(4);
lastStatusMessage = "Computing Rolling 252-day Z-Score & Volatility Tax...";
int zScoreRows = convictionMetricsRepository.updateRollingZScoreAndVolatilityTax();
log.info("📈 Rolling Z-Score updated for {} funds.", zScoreRows);

// Step 5 (NEW): Hurst Exponent (Java R/S Analysis)
currentStep.set(5);
lastStatusMessage = "Running Hurst Exponent R/S Analysis...";
hurstExponentService.computeAndPersistHurstMetrics();

// Update Step count in currentStep max from 4→6 for the admin status endpoint
```

---

### 4.3 Updated `ConvictionMetricsRepository` — New SQL Queries

Add these two new `@Query` methods (or native SQL via `JdbcTemplate` in the repository):

```java
// Method 1: Rolling 252-day Z-Score + Volatility Tax via PostgreSQL window functions
// This runs as a single SQL statement and writes back to fund_conviction_metrics.
// Returns: number of rows updated.

@Modifying
@Query(nativeQuery = true, value = """
    WITH daily_returns AS (
        SELECT
            amfi_code,
            nav_date,
            nav,
            LAG(nav) OVER (PARTITION BY amfi_code ORDER BY nav_date) AS prev_nav
        FROM fund_history
        WHERE nav_date >= CURRENT_DATE - INTERVAL '260 days'
    ),
    log_returns AS (
        SELECT
            amfi_code,
            nav_date,
            LN(nav / NULLIF(prev_nav, 0)) AS log_return
        FROM daily_returns
        WHERE prev_nav > 0 AND nav > 0
    ),
    rolling_stats AS (
        SELECT
            amfi_code,
            nav_date,
            log_return,
            -- Rolling mean of last 252 trading days
            AVG(log_return) OVER (
                PARTITION BY amfi_code
                ORDER BY nav_date
                ROWS BETWEEN 251 PRECEDING AND CURRENT ROW
            ) AS rolling_mean_252,
            -- Rolling std of last 252 trading days
            STDDEV_POP(log_return) OVER (
                PARTITION BY amfi_code
                ORDER BY nav_date
                ROWS BETWEEN 251 PRECEDING AND CURRENT ROW
            ) AS rolling_std_252,
            -- Rolling variance for volatility tax (annualised)
            VAR_POP(log_return) OVER (
                PARTITION BY amfi_code
                ORDER BY nav_date
                ROWS BETWEEN 251 PRECEDING AND CURRENT ROW
            ) AS rolling_var_252
        FROM log_returns
    ),
    latest_per_fund AS (
        SELECT DISTINCT ON (amfi_code)
            amfi_code,
            log_return,
            rolling_mean_252,
            rolling_std_252,
            rolling_var_252,
            -- Z-Score: (today's return - rolling mean) / rolling std
            CASE
                WHEN rolling_std_252 > 0
                THEN (log_return - rolling_mean_252) / rolling_std_252
                ELSE 0
            END AS z_score_252,
            -- Volatility Tax = 2 * annualised variance
            2 * (rolling_var_252 * 252) AS volatility_tax_annual
        FROM rolling_stats
        ORDER BY amfi_code, nav_date DESC
    )
    UPDATE fund_conviction_metrics m
    SET
        rolling_z_score_252 = l.z_score_252,
        volatility_tax      = l.volatility_tax_annual
    FROM latest_per_fund l
    WHERE m.amfi_code = l.amfi_code
    AND m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
    """)
int updateRollingZScoreAndVolatilityTax();
```

**Why PostgreSQL Window Functions?** The existing `runNightlyMathEngine()` and `updateNavSignals()` already use
the same pattern. This keeps all heavy mathematics inside the database engine, which is orders of magnitude
faster than pulling raw NAVs into Java for each fund individually.

---

### 4.4 New DTOs: `ReasoningMetadata` & updated `MarketMetrics`

**`ReasoningMetadata.java`** (NEW) — the structured "Why" payload attached to every signal:

```java
// Location: cas-injector/.../rebalancing/dto/ReasoningMetadata.java
package com.oreki.cas_injector.rebalancing.dto;

/**
 * Structured explanation object attached to every TacticalSignal.
 * Designed so the frontend can render a rich "Why Card" without
 * any string-parsing or magic numbers.
 */
public record ReasoningMetadata(

    // ── Primary narrative (one sentence, plain English) ─────────────────────
    String primaryNarrative,

    // ── Technical label shown in the "Signal" column ────────────────────────
    String technicalLabel,        // e.g. "Z-Score: -2.3σ | H=0.42 (Mean Reverting)"

    // ── "Noob" translation (the big headline in the Why Card) ───────────────
    String noobHeadline,          // e.g. "This fund is on a rare discount."

    // ── UI Visual Metaphor key for the frontend to render ───────────────────
    // One of: "RUBBER_BAND" | "VOLATILITY_HARVEST" | "WAVE_RIDER" | "THERMOMETER" | "COOLING_OFF"
    String uiMetaphor,

    // ── Raw signal values ────────────────────────────────────────────────────
    double zScore,                // Rolling 252-day Z-Score (e.g., -2.3)
    double hurstExponent,         // H value (e.g., 0.42)
    double volatilityTax,         // Annual variance drain as decimal (e.g., 0.032)
    String hurstRegime,           // "MEAN_REVERTING" | "TRENDING" | "RANDOM_WALK"

    // ── Human-readable Z-Score classification ───────────────────────────────
    // One of: "STATISTICALLY_CHEAP" | "OVERHEATED" | "NEUTRAL" | "SLIGHTLY_CHEAP" | "SLIGHTLY_RICH"
    String zScoreLabel,

    // ── Rarity context for the UI percentage tag ────────────────────────────
    double historicalRarityPct,   // e.g., 2.3 (meaning: "only 2.3% of days are this cheap")

    // ── Harvest context (only populated for SELL signals) ───────────────────
    double harvestAmountRupees,   // e.g., 12500.0 (the "free money" from rebalancing)
    String harvestExplanation     // e.g., "Capturing ₹12,500 in extra growth to fuel laggards."

) {
    /** Convenience factory: returns a neutral/default metadata when metrics are unavailable. */
    public static ReasoningMetadata neutral(String schemeName) {
        return new ReasoningMetadata(
            schemeName + " is within target range.",
            "Within Tolerance",
            "All good — no action needed.",
            "COOLING_OFF",
            0.0, 0.5, 0.0, "RANDOM_WALK",
            "NEUTRAL",
            50.0, 0.0, ""
        );
    }
}
```

**Updated `MarketMetrics.java`** — add the three new fields (backward-compatible):

```java
// Replace the existing MarketMetrics record with this expanded version.
// Location: cas-injector/.../convictionmetrics/dto/MarketMetrics.java
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
```

> **Migration note for `fetchLiveMetricsMap()` and `fetchMetricsForAmfi()` in `PortfolioOrchestrator`:**
> Update the SQL in both methods to `SELECT` the three new columns and pass them into the `MarketMetrics`
> constructor. Use `COALESCE(rolling_z_score_252, return_z_score, 0)` so the system degrades gracefully
> before the first nightly engine run with the new SQL.

---

### 4.5 Updated `TacticalSignal.java`

Add `reasoningMetadata` as the last field (fully backward-compatible since it's a record append):

```java
// Location: cas-injector/.../rebalancing/dto/TacticalSignal.java
package com.oreki.cas_injector.rebalancing.dto;

import java.time.LocalDate;
import java.util.List;
import com.oreki.cas_injector.core.utils.SignalType;

public record TacticalSignal(
    String schemeName,
    String amfiCode,
    SignalType action,
    String amount,
    double plannedPercentage,
    double actualPercentage,
    double sipPercentage,
    String fundStatus,
    int    convictionScore,
    double sortinoRatio,
    double maxDrawdown,
    double navPercentile3yr,
    double drawdownFromAth,
    double returnZScore,
    LocalDate lastBuyDate,
    List<String> justifications,

    // ── NEW: structured "Why" payload for the Explanation Engine ─────────────
    ReasoningMetadata reasoningMetadata
) {}
```

> **Update `createSignal()` in `PortfolioOrchestrator`** to pass `s.reasoningMetadata()` through.
> Update `buildSignal()` to call `ReasoningMetadataFactory.build(...)` (see next section).

---

### 4.6 Revamped `RebalanceEngine.java`

This is the core change. The new engine introduces the **Hurst Safety Gate** and uses the **Rolling Z-Score**
as the primary signal trigger, while still respecting the existing drift tolerance and tax logic.

```java
// Location: cas-injector/.../rebalancing/service/RebalanceEngine.java
package com.oreki.cas_injector.rebalancing.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.ReasoningMetadata;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;

@Service
public class RebalanceEngine {

    // ── Thresholds ────────────────────────────────────────────────────────────
    private static final double DRIFT_TOLERANCE     = 2.5;   // ±2.5% before action
    private static final int    MIN_BUY_CONVICTION  = 35;    // Legacy guard: don't buy if CQS below this
    private static final int    STCG_WAIT_DAYS      = 45;    // Near-LTCG deferral window

    // Z-Score thresholds (primary signal trigger)
    private static final double Z_BUY_STRONG        = -2.0;  // "Highly Overstretched" — strong buy signal
    private static final double Z_BUY_MILD          = -1.0;  // Mild discount — buy confirmed by drift
    private static final double Z_SELL_STRONG       =  2.0;  // "Overheated" — harvest now
    private static final double Z_SELL_MILD         =  1.0;  // Mild richness — sell confirmed by drift

    // Hurst regime
    private static final double H_TRENDING          = 0.55;  // Above this: "Riding the Wave" override
    private static final double H_MEAN_REVERTING    = 0.45;  // Below this: "Rubber Band" confirmed

    public TacticalSignal evaluate(
            AggregatedHolding holding,
            StrategyTarget    target,
            MarketMetrics     metrics,
            double            totalPortfolioValue,
            String            amfiCode) {

        double targetPct = target.targetPortfolioPct();
        double sipPct    = target.sipPct();
        double actualPct = totalPortfolioValue > 0
            ? (holding.getCurrentValue() / totalPortfolioValue) * 100.0
            : 0.0;

        double targetValueRs  = (targetPct / 100.0) * totalPortfolioValue;
        double diffAmount     = targetValueRs - holding.getCurrentValue();
        double overweightPct  = actualPct - targetPct;

        // ── Extract new metrics (with safe fallbacks) ─────────────────────────
        double  z   = metrics.rollingZScore252();   // Primary signal trigger
        double  H   = metrics.hurstExponent();      // Safety gate
        double  vt  = metrics.volatilityTax();      // Harvest explanation
        String  regime    = metrics.hurstRegime();
        double  rarity    = metrics.historicalRarityPct();

        String status = resolveStatus(targetPct, sipPct, actualPct);
        SignalType action = SignalType.HOLD;
        List<String> justifications = new ArrayList<>();

        // ══════════════════════════════════════════════════════════════════════
        // CASE 1: DROPPED FUND — full exit logic (unchanged from v2)
        // ══════════════════════════════════════════════════════════════════════
        if ("DROPPED".equals(status)) {
            action = SignalType.EXIT;
            justifications.add("Strategic: Explicitly marked as DROPPED. Target is 0%.");
            applyTaxJustifications(holding, justifications, STCG_WAIT_DAYS);
            if (action == SignalType.EXIT && holding.getDaysToNextLtcg() > 0
                    && holding.getDaysToNextLtcg() <= STCG_WAIT_DAYS) {
                action = SignalType.HOLD;
            }

            ReasoningMetadata meta = buildDroppedMetadata(holding, z, H, vt, regime);
            return buildSignal(holding, amfiCode, action, diffAmount, targetPct, actualPct,
                    sipPct, status, metrics, justifications, meta);
        }

        // ══════════════════════════════════════════════════════════════════════
        // CASE 2: OVERWEIGHT — potential SELL / HARVEST
        // ══════════════════════════════════════════════════════════════════════
        if (actualPct > targetPct + DRIFT_TOLERANCE) {

            // ── HURST SAFETY GATE: Trending fund — don't cut winners early ───
            if (H > H_TRENDING && z < Z_SELL_STRONG) {
                // Fund is trending upward AND not yet at the "overheated" Z-threshold.
                // Override sell to HOLD: "Riding the Wave."
                action = SignalType.HOLD;
                justifications.add(String.format(Locale.US,
                    "Wave Rider Override: H=%.2f indicates a trending regime. " +
                    "Z-Score %.2fσ has not yet reached overheated territory (>+%.1fσ). " +
                    "Keeping position open to capture remaining momentum.",
                    H, z, Z_SELL_STRONG));

                ReasoningMetadata meta = buildWaveRiderMetadata(holding, z, H, vt, regime, overweightPct);
                return buildSignal(holding, amfiCode, action, 0, targetPct, actualPct,
                        sipPct, status, metrics, justifications, meta);
            }

            // ── Primary Z-Score SELL trigger ─────────────────────────────────
            justifications.add(String.format(Locale.US,
                "Overweight by %.2f%% (actual %.2f%% vs target %.2f%%). " +
                "Z-Score: %.2fσ. Hurst: %.2f (%s).",
                overweightPct, actualPct, targetPct, z, H, regime));

            // Calculate Volatility Harvest amount:
            // harvestAmount = overweight fraction × totalPortfolio × volatilityTax
            double harvestAmount = (overweightPct / 100.0) * totalPortfolioValue * vt;

            if (z >= Z_SELL_STRONG) {
                // Statistically overheated — strong harvest signal
                justifications.add(String.format(Locale.US,
                    "Volatility Harvest: Z-Score +%.2fσ — fund is statistically overheated " +
                    "(only %.1f%% of days are this expensive). Trim now to capture ₹%,.0f " +
                    "in 'extra' growth before mean reversion.",
                    z, rarity, harvestAmount));
                action = SignalType.SELL;
            } else {
                // Drift-triggered, not yet overheated — standard rebalance
                justifications.add(String.format(Locale.US,
                    "Volatility Harvest: Trim drifted position. " +
                    "Estimated ₹%,.0f rebalancing bonus from variance drag (VT=%.2f%% p.a.).",
                    harvestAmount, vt * 100));
                action = SignalType.SELL;
            }

            // Apply STCG/LTCG tax override (logic from v2, unchanged)
            action = applyTaxOverride(holding, action, justifications, totalPortfolioValue,
                    overweightPct, STCG_WAIT_DAYS);

            ReasoningMetadata meta = buildHarvestMetadata(holding, z, H, vt, regime, rarity,
                    harvestAmount, action);
            return buildSignal(holding, amfiCode, action, diffAmount, targetPct, actualPct,
                    sipPct, status, metrics, justifications, meta);
        }

        // ══════════════════════════════════════════════════════════════════════
        // CASE 3: UNDERWEIGHT — potential BUY
        // ══════════════════════════════════════════════════════════════════════
        if (actualPct < targetPct - DRIFT_TOLERANCE) {
            double deficit = targetPct - actualPct;

            // ── HURST SAFETY GATE: Trending downward — don't buy falling knives ─
            if (H > H_TRENDING && z > Z_BUY_MILD) {
                // Fund is trending AND price hasn't yet reached discount territory.
                // "Don't buy losers yet" — wait for Z-Score confirmation.
                action = SignalType.WATCH;
                justifications.add(String.format(Locale.US,
                    "Trending Caution: H=%.2f indicates downward momentum. " +
                    "Z-Score %.2fσ has not yet reached discount territory (<%.1fσ). " +
                    "Fund is underweight by %.2f%% but buying into a trend is risky. " +
                    "Wait for Z-Score to confirm the dip is over.",
                    H, z, Z_BUY_MILD, deficit));

                ReasoningMetadata meta = buildWatchMetadata(holding, z, H, regime, deficit);
                return buildSignal(holding, amfiCode, action, 0, targetPct, actualPct,
                        sipPct, status, metrics, justifications, meta);
            }

            // ── MEAN REVERTING + CHEAP: Classic "Rubber Band" buy ─────────────
            if (H < H_MEAN_REVERTING && z <= Z_BUY_STRONG) {
                justifications.add(String.format(Locale.US,
                    "Rubber Band Buy: H=%.2f (Mean Reverting) + Z-Score %.2fσ. " +
                    "This fund is statistically cheap in only %.1f%% of historical days. " +
                    "Mean-reverting funds snap back — buying the dip is safe here.",
                    H, z, rarity));
                action = SignalType.BUY;
            }
            // ── Standard deficit buy ──────────────────────────────────────────
            else if (metrics.convictionScore() > 0 && metrics.convictionScore() < MIN_BUY_CONVICTION) {
                action = SignalType.WATCH;
                justifications.add(String.format(Locale.US,
                    "BUY suppressed: Conviction %d < threshold %d. " +
                    "Underweight by %.2f%% but fundamentals are deteriorating. Review fund.",
                    metrics.convictionScore(), MIN_BUY_CONVICTION, deficit));
            } else {
                justifications.add(String.format(Locale.US,
                    "Underweight by %.2f%%. Z-Score %.2fσ (%.1f%% historical rarity). " +
                    "Conviction: %d/100. Status: %s.",
                    deficit, z, rarity, metrics.convictionScore(), status));
                action = SignalType.BUY;
            }

            ReasoningMetadata meta = buildRubberBandMetadata(holding, z, H, regime, rarity,
                    deficit, action);
            return buildSignal(holding, amfiCode, action, diffAmount, targetPct, actualPct,
                    sipPct, status, metrics, justifications, meta);
        }

        // ══════════════════════════════════════════════════════════════════════
        // CASE 4: WITHIN TOLERANCE — HOLD
        // ══════════════════════════════════════════════════════════════════════
        justifications.add(String.format(Locale.US,
            "Within ±%.1f%% target tolerance (actual %.2f%%, target %.2f%%). Z=%.2fσ, H=%.2f.",
            DRIFT_TOLERANCE, actualPct, targetPct, z, H));

        ReasoningMetadata meta = ReasoningMetadata.neutral(holding.getSchemeName());
        return buildSignal(holding, amfiCode, SignalType.HOLD, 0, targetPct, actualPct,
                sipPct, status, metrics, justifications, meta);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String resolveStatus(double targetPct, double sipPct, double actualPct) {
        if (targetPct == 0.0 && sipPct == 0.0 && actualPct > 0.0) return "DROPPED";
        if (sipPct > 0.0 && actualPct < targetPct) return "ACCUMULATOR";
        return "ACTIVE";
    }

    /** Applies the existing STCG/LTCG tax override logic from v2. Returns (possibly overridden) action. */
    private SignalType applyTaxOverride(AggregatedHolding h, SignalType action,
            List<String> justs, double totalValue, double overweightPct, int stcgWaitDays) {
        if (h.getLtcgAmount() > 0) {
            justs.add(String.format(Locale.US,
                "Tax Strategy: Selling oldest lots first — ₹%.2f in LTCG gains. Use HIFO.",
                h.getLtcgAmount()));
            return SignalType.SELL;
        }
        if (h.getStcgValue() > 0 && h.getDaysToNextLtcg() > 0 && h.getDaysToNextLtcg() <= stcgWaitDays) {
            justs.add(String.format(Locale.US,
                "Action overridden to HOLD: %d days until LTCG on ₹%.2f. Redirect SIP instead.",
                h.getDaysToNextLtcg(), h.getStcgValue()));
            return SignalType.HOLD;
        }
        if (h.getStcgValue() > 0 && h.getDaysToNextLtcg() > stcgWaitDays) {
            double stcgTax  = h.getStcgAmount() * 0.20;
            double driftCost = (overweightPct / 100.0) * totalValue * 0.015;
            if (stcgTax >= driftCost) {
                justs.add(String.format(Locale.US,
                    "HOLD: STCG tax ₹%.2f exceeds drift drag ₹%.2f. Pause SIP instead.",
                    stcgTax, driftCost));
                return SignalType.HOLD;
            }
            justs.add(String.format(Locale.US,
                "STCG override lifted: Tax ₹%.2f < drift drag ₹%.2f. Trim now.",
                stcgTax, driftCost));
        }
        return action;
    }

    private void applyTaxJustifications(AggregatedHolding h, List<String> justs, int stcgWaitDays) {
        if (h.getLtcgAmount() > 0)
            justs.add(String.format(Locale.US,
                "Tax Benefit: Exiting unlocks ₹%.2f in LTCG. Stay within ₹1.25L annual limit.",
                h.getLtcgAmount()));
        if (h.getStcgAmount() > 0 && h.getDaysToNextLtcg() > 0 && h.getDaysToNextLtcg() <= stcgWaitDays)
            justs.add(String.format(Locale.US,
                "Exit deferred: %d days until LTCG on ₹%.2f. Waiting saves 20%% STCG.",
                h.getDaysToNextLtcg(), h.getStcgValue()));
    }

    // ── ReasoningMetadata builders ─────────────────────────────────────────────

    private ReasoningMetadata buildRubberBandMetadata(AggregatedHolding h, double z,
            double H, String regime, double rarity, double deficit, SignalType action) {
        boolean isStrong = z <= Z_BUY_STRONG;
        String zLabel    = z <= Z_BUY_STRONG ? "STATISTICALLY_CHEAP" : "SLIGHTLY_CHEAP";
        String metaphor  = (H < H_MEAN_REVERTING && isStrong) ? "RUBBER_BAND" : "RUBBER_BAND";
        String noob = isStrong
            ? String.format("This fund is on a rare %.0f%%-of-days discount. It's a stretched rubber band — a snapback is statistically due.", rarity)
            : String.format("The fund is %.2f%% underweight and showing a mild price dip.", deficit);
        return new ReasoningMetadata(
            h.getSchemeName() + " is underweight by " + String.format("%.2f%%", deficit),
            String.format("Z-Score: %.2fσ | H=%.2f (%s)", z, H, regime),
            noob, metaphor, z, H, 0.0, regime, zLabel, rarity, 0.0, ""
        );
    }

    private ReasoningMetadata buildHarvestMetadata(AggregatedHolding h, double z,
            double H, double vt, String regime, double rarity, double harvest, SignalType action) {
        String zLabel   = z >= Z_SELL_STRONG ? "OVERHEATED" : "SLIGHTLY_RICH";
        String metaphor = z >= Z_SELL_STRONG ? "THERMOMETER" : "VOLATILITY_HARVEST";
        String noob = z >= Z_SELL_STRONG
            ? String.format("This fund is statistically expensive (top %.0f%% of days). Time to lock in gains before the heat breaks.", 100 - rarity)
            : String.format("We are capturing ₹%,.0f in 'extra' growth to fuel your laggards. Free money from chaos.", harvest);
        String harvestExpl = String.format(
            "Rebalancing captures ₹%,.0f in variance drag (VT=%.2f%% p.a.) that would otherwise erode returns.",
            harvest, vt * 100);
        return new ReasoningMetadata(
            "Trim " + h.getSchemeName() + " — drifted overweight",
            String.format("Z-Score: +%.2fσ | H=%.2f (%s) | VT=%.2f%%", z, H, regime, vt * 100),
            noob, metaphor, z, H, vt, regime, zLabel, rarity, harvest, harvestExpl
        );
    }

    private ReasoningMetadata buildWaveRiderMetadata(AggregatedHolding h, double z,
            double H, double vt, String regime, double overweightPct) {
        return new ReasoningMetadata(
            h.getSchemeName() + " is trending upward — holding overweight position",
            String.format("H=%.2f (Trending) | Z=%.2fσ — not yet overheated", H, z),
            String.format("Riding the Wave — this fund is in a strong uptrend (H=%.2f). We won't cut profits yet. Wait for Z>+%.1fσ to harvest.", H, Z_SELL_STRONG),
            "WAVE_RIDER", z, H, vt, regime, "NEUTRAL", 50.0, 0.0, ""
        );
    }

    private ReasoningMetadata buildWatchMetadata(AggregatedHolding h, double z,
            double H, String regime, double deficit) {
        return new ReasoningMetadata(
            h.getSchemeName() + " is underweight but in a downtrend — waiting",
            String.format("H=%.2f (Trending Down) | Z=%.2fσ — no discount yet", H, z),
            String.format("Don't catch a falling knife. The fund is %.2f%% underweight but still trending down (H=%.2f). We'll buy once Z-Score confirms the dip is done.", deficit, H),
            "COOLING_OFF", z, H, 0.0, regime, "NEUTRAL", 50.0, 0.0, ""
        );
    }

    private ReasoningMetadata buildDroppedMetadata(AggregatedHolding h, double z,
            double H, double vt, String regime) {
        return new ReasoningMetadata(
            h.getSchemeName() + " has been removed from the strategy",
            "Status: DROPPED — Strategic Exit",
            "This fund is no longer part of your investment plan. Time to exit cleanly.",
            "COOLING_OFF", z, H, vt, regime, "NEUTRAL", 50.0, 0.0, ""
        );
    }

    private TacticalSignal buildSignal(AggregatedHolding h, String amfi, SignalType action,
            double amount, double targetPct, double actualPct, double sipPct, String status,
            MarketMetrics m, List<String> justs, ReasoningMetadata meta) {
        return new TacticalSignal(
            h.getSchemeName(), amfi, action,
            String.format(Locale.US, "%.2f", Math.abs(amount)),
            round(targetPct), round(actualPct), round(sipPct), status,
            m.convictionScore(), m.sortinoRatio(), m.maxDrawdown(),
            m.navPercentile3yr(), m.drawdownFromAth(), m.returnZScore(),
            m.lastBuyDate(), justs, meta);
    }

    private static final double Z_BUY_MILD  = -1.0;
    private static final double Z_SELL_STRONG = 2.0;
    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
```

---

## 5. Frontend: Explanation Engine UI

### 5.1 Updated TypeScript Types

**`portfolio-dashboard/src/types/signals.ts`** (new file — extract from existing inline `any` casts):

```typescript
// portfolio-dashboard/src/types/signals.ts

export type HurstRegime = 'MEAN_REVERTING' | 'TRENDING' | 'RANDOM_WALK';
export type ZScoreLabel = 'STATISTICALLY_CHEAP' | 'SLIGHTLY_CHEAP' | 'NEUTRAL' | 'SLIGHTLY_RICH' | 'OVERHEATED';
export type UIMetaphor  = 'RUBBER_BAND' | 'VOLATILITY_HARVEST' | 'WAVE_RIDER' | 'THERMOMETER' | 'COOLING_OFF';

export interface ReasoningMetadata {
  primaryNarrative:     string;
  technicalLabel:       string;
  noobHeadline:         string;
  uiMetaphor:           UIMetaphor;
  zScore:               number;
  hurstExponent:        number;
  volatilityTax:        number;
  hurstRegime:          HurstRegime;
  zScoreLabel:          ZScoreLabel;
  historicalRarityPct:  number;
  harvestAmountRupees:  number;
  harvestExplanation:   string;
}

export interface TacticalSignal {
  schemeName:          string;
  amfiCode:            string;
  action:              'BUY' | 'SELL' | 'HOLD' | 'WATCH' | 'EXIT';
  amount:              string;
  plannedPercentage:   number;
  actualPercentage:    number;
  sipPercentage:       number;
  fundStatus:          string;
  convictionScore:     number;
  sortinoRatio:        number;
  maxDrawdown:         number;
  navPercentile3yr:    number;
  drawdownFromAth:     number;
  returnZScore:        number;
  lastBuyDate:         string | null;
  justifications:      string[];
  reasoningMetadata:   ReasoningMetadata | null; // null = legacy signal, render fallback
}
```

---

### 5.2 New `RecommendationDetailCard.tsx`

This is the "Why Card" — the centrepiece of the Explanation Engine. It renders the `reasoningMetadata`
object into a human-readable, visually rich card. Drop one card per signal in `RebalanceView`.

```tsx
// portfolio-dashboard/src/components/ui/RecommendationDetailCard.tsx

import React from 'react';
import { motion } from 'framer-motion';
import { TacticalSignal, ReasoningMetadata, UIMetaphor } from '../../types/signals';
import CurrencyValue from './CurrencyValue';
import { formatCurrency } from '../../utils/formatters';

// ── Visual Metaphor Components ─────────────────────────────────────────────────

/**
 * RUBBER BAND — used for BUY signals where the fund is mean-reverting + statistically cheap.
 * Shows a stretched rubber-band SVG with the Z-Score "stretch" label.
 */
const RubberBandVisual: React.FC<{ zScore: number; rarityPct: number }> = ({ zScore, rarityPct }) => {
  const stretch = Math.min(Math.abs(zScore) / 4, 1); // 0 → 1 normalised to 0-4σ
  const stretchPx = Math.round(stretch * 80); // max 80px visual stretch

  const label = zScore <= -2.0
    ? `Highly Overstretched — Snapback Expected`
    : zScore <= -1.0
    ? `Moderately Stretched — Dip Opportunity`
    : `Mildly Below Average`;

  return (
    <div className="flex flex-col items-center gap-3 py-4">
      {/* Rubber band SVG */}
      <svg width="200" height="60" viewBox="0 0 200 60" className="overflow-visible">
        {/* Anchor point (left) */}
        <circle cx="10" cy="30" r="5" fill="#818cf8" />
        {/* Stretched band */}
        <path
          d={`M 10 30 Q 100 ${30 + stretchPx} 190 30`}
          fill="none"
          stroke="#34d399"
          strokeWidth="2.5"
          strokeDasharray="none"
          className="drop-shadow-[0_0_6px_rgba(52,211,153,0.5)]"
        />
        {/* Equilibrium line */}
        <line x1="10" y1="30" x2="190" y2="30" stroke="rgba(255,255,255,0.1)" strokeWidth="1" strokeDasharray="4 4"/>
        {/* Anchor point (right) */}
        <circle cx="190" cy="30" r="5" fill="#818cf8" />
        {/* Z-Score bubble */}
        <text x="100" y={30 + stretchPx + 18} textAnchor="middle" fill="#34d399" fontSize="13" fontWeight="bold">
          {zScore.toFixed(1)}σ
        </text>
      </svg>

      <div className="flex items-center gap-2">
        <span className="px-2 py-0.5 bg-buy/15 border border-buy/30 rounded-full text-[10px] font-bold text-buy uppercase tracking-widest">
          🎯 {label}
        </span>
      </div>
      <p className="text-[11px] text-secondary text-center max-w-[240px]">
        Only <span className="text-buy font-bold">{rarityPct.toFixed(1)}%</span> of historical 
        days were this cheap — statistically rare buying opportunity.
      </p>
    </div>
  );
};

/**
 * VOLATILITY HARVEST — used for SELL signals where we're trimming overweight positions.
 * Shows the harvest amount prominently with grain/wheat emoji.
 */
const VolatilityHarvestVisual: React.FC<{
  harvestAmount: number;
  volatilityTax: number;
  isPrivate: boolean;
}> = ({ harvestAmount, volatilityTax, isPrivate }) => (
  <div className="flex flex-col items-center gap-3 py-4">
    <div className="text-5xl">🌾</div>
    <div className="text-center">
      <p className="text-[10px] text-muted uppercase tracking-widest mb-1">Rebalancing Bonus Captured</p>
      <div className="text-2xl font-medium text-buy tabular-nums">
        <CurrencyValue isPrivate={isPrivate} value={harvestAmount} />
      </div>
    </div>
    <p className="text-[11px] text-secondary text-center max-w-[260px]">
      We are capturing <span className="text-buy font-bold">
        <CurrencyValue isPrivate={isPrivate} value={harvestAmount} />
      </span> in 'extra' growth — money that volatility ({(volatilityTax * 100).toFixed(1)}% p.a. drag)
      would quietly erode. Selling here and redeploying to laggards <em>turns chaos into fuel</em>.
    </p>
  </div>
);

/**
 * THERMOMETER — used for SELL signals when Z-Score is overheated (>+2σ).
 * Red thermometer filling up to the Z-Score level.
 */
const ThermometerVisual: React.FC<{ zScore: number; rarityPct: number }> = ({ zScore, rarityPct }) => {
  const fillPct = Math.min((zScore / 4) * 100, 100); // 4σ = full
  return (
    <div className="flex flex-col items-center gap-3 py-4">
      {/* Thermometer */}
      <div className="relative w-8 h-24 bg-white/5 rounded-full border border-white/10 overflow-hidden">
        <motion.div
          className="absolute bottom-0 left-0 right-0 bg-exit rounded-full shadow-[0_0_12px_rgba(248,113,113,0.6)]"
          initial={{ height: 0 }}
          animate={{ height: `${fillPct}%` }}
          transition={{ duration: 1.2, ease: 'easeOut' }}
        />
        {/* Mercury bulb */}
        <div className="absolute -bottom-1.5 left-1/2 -translate-x-1/2 w-5 h-5 bg-exit rounded-full shadow-[0_0_8px_rgba(248,113,113,0.6)]" />
      </div>
      <div className="flex items-center gap-2">
        <span className="px-2 py-0.5 bg-exit/15 border border-exit/30 rounded-full text-[10px] font-bold text-exit uppercase tracking-widest">
          🌡 Overheated — +{zScore.toFixed(1)}σ
        </span>
      </div>
      <p className="text-[11px] text-secondary text-center max-w-[240px]">
        This fund is in the top <span className="text-exit font-bold">{(100 - rarityPct).toFixed(1)}%</span> of
        expensive days. History shows prices cool down from here.
      </p>
    </div>
  );
};

/**
 * WAVE RIDER — used for HOLD overrides where H>0.55 (trending).
 * Animated wave with surfboard metaphor.
 */
const WaveRiderVisual: React.FC<{ hurstExponent: number }> = ({ hurstExponent }) => (
  <div className="flex flex-col items-center gap-3 py-4">
    <div className="relative w-40 h-16 overflow-hidden">
      <svg viewBox="0 0 160 60" className="w-full h-full">
        <motion.path
          d="M0 40 Q20 20 40 35 Q60 50 80 30 Q100 10 120 25 Q140 40 160 20"
          fill="none"
          stroke="#818cf8"
          strokeWidth="2.5"
          className="drop-shadow-[0_0_6px_rgba(129,140,248,0.5)]"
          animate={{ pathLength: [0, 1] }}
          transition={{ duration: 1.5, ease: 'easeInOut' }}
        />
        {/* Surfer dot riding the wave */}
        <motion.circle
          cx="120" cy="25" r="5"
          fill="#fbbf24"
          className="drop-shadow-[0_0_4px_rgba(251,191,36,0.6)]"
          animate={{ cx: [0, 160], cy: [40, 20, 35, 30, 25] }}
          transition={{ duration: 2, ease: 'easeInOut', repeat: Infinity, repeatType: 'reverse' }}
        />
      </svg>
    </div>
    <div className="flex items-center gap-2">
      <span className="px-2 py-0.5 bg-accent/15 border border-accent/30 rounded-full text-[10px] font-bold text-accent uppercase tracking-widest">
        🏄 Riding the Wave — H={hurstExponent.toFixed(2)}
      </span>
    </div>
    <p className="text-[11px] text-secondary text-center max-w-[240px]">
      This fund is in a confirmed uptrend (Hurst={hurstExponent.toFixed(2)}). 
      Cutting it now would mean selling a winner too early. We'll let it run.
    </p>
  </div>
);

/**
 * COOLING OFF — neutral / WATCH / HOLD states where no strong signal exists.
 */
const CoolingOffVisual: React.FC<{ zScore: number }> = ({ zScore }) => (
  <div className="flex flex-col items-center gap-3 py-4">
    <div className="text-4xl">🌡️</div>
    <p className="text-[11px] text-secondary text-center max-w-[240px]">
      Z-Score {zScore.toFixed(2)}σ — within the normal range. No statistically significant signal. 
      Holding steady.
    </p>
  </div>
);

// ── Metaphor dispatcher ────────────────────────────────────────────────────────

const MetaphorVisual: React.FC<{
  metaphor: UIMetaphor;
  meta: ReasoningMetadata;
  isPrivate: boolean;
}> = ({ metaphor, meta, isPrivate }) => {
  switch (metaphor) {
    case 'RUBBER_BAND':
      return <RubberBandVisual zScore={meta.zScore} rarityPct={meta.historicalRarityPct} />;
    case 'VOLATILITY_HARVEST':
      return <VolatilityHarvestVisual harvestAmount={meta.harvestAmountRupees} volatilityTax={meta.volatilityTax} isPrivate={isPrivate} />;
    case 'THERMOMETER':
      return <ThermometerVisual zScore={meta.zScore} rarityPct={meta.historicalRarityPct} />;
    case 'WAVE_RIDER':
      return <WaveRiderVisual hurstExponent={meta.hurstExponent} />;
    case 'COOLING_OFF':
    default:
      return <CoolingOffVisual zScore={meta.zScore} />;
  }
};

// ── Signal vs. Noise Table ─────────────────────────────────────────────────────

const SignalNoiseTable: React.FC<{ meta: ReasoningMetadata }> = ({ meta }) => {
  const rows: Array<{ technical: string; noob: string; value: string; color?: string }> = [
    {
      technical: `Z-Score: ${meta.zScore.toFixed(2)}σ`,
      noob: meta.zScoreLabel === 'STATISTICALLY_CHEAP' ? '🟢 Statistically Cheap'
          : meta.zScoreLabel === 'OVERHEATED'           ? '🔴 Overheated'
          : meta.zScoreLabel === 'SLIGHTLY_CHEAP'       ? '🟡 Mild Discount'
          : meta.zScoreLabel === 'SLIGHTLY_RICH'        ? '🟠 Slightly Rich'
          : '⚪ Normal Range',
      value: `${meta.historicalRarityPct.toFixed(1)}% of days`,
      color: meta.zScoreLabel === 'STATISTICALLY_CHEAP' ? 'text-buy'
           : meta.zScoreLabel === 'OVERHEATED'           ? 'text-exit'
           : 'text-warning',
    },
    {
      technical: `Hurst Exponent: ${meta.hurstExponent.toFixed(2)}`,
      noob: meta.hurstRegime === 'MEAN_REVERTING' ? '↩️ Bouncing Back'
          : meta.hurstRegime === 'TRENDING'         ? '🏄 Riding the Wave'
          : '〰️ Unpredictable',
      value: meta.hurstRegime,
      color: meta.hurstRegime === 'MEAN_REVERTING' ? 'text-buy'
           : meta.hurstRegime === 'TRENDING'         ? 'text-accent'
           : 'text-muted',
    },
    {
      technical: `Volatility Tax: ${(meta.volatilityTax * 100).toFixed(2)}% p.a.`,
      noob: '🌾 Free Money',
      value: 'Rebalancing captures this drag',
      color: 'text-buy',
    },
  ];

  return (
    <div className="mt-4 rounded-lg overflow-hidden border border-white/5">
      <table className="w-full text-left">
        <thead>
          <tr className="bg-white/[0.02]">
            <th className="px-4 py-2 text-[9px] font-bold uppercase tracking-widest text-muted">Technical Metric</th>
            <th className="px-4 py-2 text-[9px] font-bold uppercase tracking-widest text-muted">"Noob" Translation</th>
            <th className="px-4 py-2 text-[9px] font-bold uppercase tracking-widest text-muted">Context</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-white/5">
          {rows.map((row, i) => (
            <tr key={i} className="hover:bg-white/[0.01] transition-colors">
              <td className="px-4 py-3 text-[11px] text-secondary font-mono">{row.technical}</td>
              <td className={`px-4 py-3 text-[11px] font-medium ${row.color}`}>{row.noob}</td>
              <td className="px-4 py-3 text-[10px] text-muted">{row.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// ── Main RecommendationDetailCard ─────────────────────────────────────────────

interface Props {
  signal: TacticalSignal;
  isPrivate: boolean;
  defaultExpanded?: boolean;
}

const actionColors: Record<string, string> = {
  BUY:   'text-buy  border-buy/20  bg-buy/5',
  SELL:  'text-exit border-exit/20 bg-exit/5',
  HOLD:  'text-muted border-white/10 bg-white/[0.02]',
  WATCH: 'text-warning border-warning/20 bg-warning/5',
  EXIT:  'text-exit border-exit/20 bg-exit/5',
};

export const RecommendationDetailCard: React.FC<Props> = ({
  signal, isPrivate, defaultExpanded = false
}) => {
  const [expanded, setExpanded] = React.useState(defaultExpanded);
  const meta = signal.reasoningMetadata;
  const colors = actionColors[signal.action] ?? actionColors.HOLD;

  // If no metadata (legacy signal), fall back to a simple justifications list.
  if (!meta) {
    return (
      <div className={`rounded-xl border p-5 ${colors} transition-all duration-200`}>
        <div className="flex items-center justify-between mb-3">
          <span className="text-[10px] font-bold uppercase tracking-widest">{signal.action}</span>
          <span className="text-[13px] font-medium text-primary truncate max-w-[200px]">{signal.schemeName}</span>
        </div>
        <div className="space-y-1">
          {signal.justifications.slice(0, 2).map((j, i) => (
            <p key={i} className="text-[10px] text-secondary border-l border-white/10 pl-2 leading-relaxed">{j}</p>
          ))}
        </div>
      </div>
    );
  }

  return (
    <motion.div
      layout
      className={`rounded-xl border p-5 cursor-pointer select-none ${colors} transition-all duration-200`}
      onClick={() => setExpanded(e => !e)}
    >
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between mb-3">
        <span className="text-[10px] font-bold uppercase tracking-widest opacity-70">{signal.action} SIGNAL</span>
        <div className="flex items-center gap-2">
          {/* Z-Score "Discount" tag */}
          {meta.zScoreLabel === 'STATISTICALLY_CHEAP' && (
            <span className="px-2 py-0.5 bg-buy/15 border border-buy/30 rounded-full text-[9px] font-bold text-buy uppercase tracking-widest">
              🏷️ DISCOUNT — {meta.historicalRarityPct.toFixed(1)}% rarity
            </span>
          )}
          {meta.zScoreLabel === 'OVERHEATED' && (
            <span className="px-2 py-0.5 bg-exit/15 border border-exit/30 rounded-full text-[9px] font-bold text-exit uppercase tracking-widest">
              🌡️ OVERHEATED
            </span>
          )}
          {/* Hurst regime badge */}
          {meta.hurstRegime === 'MEAN_REVERTING' && (
            <span className="px-2 py-0.5 bg-buy/10 border border-buy/20 rounded-full text-[9px] font-bold text-buy uppercase tracking-widest">
              ↩️ BOUNCING BACK
            </span>
          )}
          {meta.hurstRegime === 'TRENDING' && (
            <span className="px-2 py-0.5 bg-accent/10 border border-accent/20 rounded-full text-[9px] font-bold text-accent uppercase tracking-widest">
              🏄 TRENDING
            </span>
          )}
        </div>
      </div>

      {/* ── Fund name ──────────────────────────────────────────────────────── */}
      <p className="text-[14px] font-medium text-primary truncate mb-1">{signal.schemeName}</p>

      {/* ── Amount ─────────────────────────────────────────────────────────── */}
      <div className="text-xl font-medium tabular-nums mb-3">
        {['BUY', 'SELL'].includes(signal.action) ? (
          <CurrencyValue isPrivate={isPrivate} value={parseFloat(signal.amount)} />
        ) : (
          <span className="text-muted text-sm">{signal.action}</span>
        )}
      </div>

      {/* ── "Noob" headline ────────────────────────────────────────────────── */}
      <p className="text-[12px] text-secondary leading-relaxed mb-3 font-medium">
        {meta.noobHeadline}
      </p>

      {/* ── Expand/collapse ────────────────────────────────────────────────── */}
      <button
        className="text-[9px] text-muted uppercase tracking-widest font-bold flex items-center gap-1 hover:text-secondary transition-colors"
        onClick={e => { e.stopPropagation(); setExpanded(x => !x); }}
      >
        {expanded ? '▲ Less detail' : '▼ Show me why'}
      </button>

      {/* ── Expanded "Why" section ─────────────────────────────────────────── */}
      {expanded && (
        <motion.div
          initial={{ opacity: 0, height: 0 }}
          animate={{ opacity: 1, height: 'auto' }}
          exit={{ opacity: 0, height: 0 }}
          transition={{ duration: 0.2 }}
          className="mt-4 space-y-4"
          onClick={e => e.stopPropagation()}
        >
          {/* Visual Metaphor */}
          <div className="bg-white/[0.02] rounded-xl border border-white/5 px-4">
            <MetaphorVisual metaphor={meta.uiMetaphor} meta={meta} isPrivate={isPrivate} />
          </div>

          {/* Signal vs. Noise Table */}
          <SignalNoiseTable meta={meta} />

          {/* Technical Justifications (existing) */}
          <div className="space-y-2">
            <p className="text-[9px] text-muted uppercase tracking-widest font-bold">Technical Reasoning</p>
            {signal.justifications.map((j, i) => (
              <p key={i} className="text-[10px] text-secondary leading-relaxed border-l border-white/10 pl-3">
                {j}
              </p>
            ))}
          </div>

          {/* Harvest Explanation (only for SELL) */}
          {meta.harvestAmountRupees > 0 && meta.harvestExplanation && (
            <div className="bg-buy/[0.04] border border-buy/15 rounded-lg p-4">
              <p className="text-[10px] text-muted uppercase tracking-widest font-bold mb-1">🌾 Volatility Harvest</p>
              <p className="text-[11px] text-secondary leading-relaxed">{meta.harvestExplanation}</p>
            </div>
          )}
        </motion.div>
      )}
    </motion.div>
  );
};

export default RecommendationDetailCard;
```

---

### 5.3 Updated `formatters.ts`

Add the client-side fallback `buildReasoningMetadata()` function. This generates `ReasoningMetadata` from
the existing signal fields if the backend hasn't yet provided it (backwards compatibility):

```typescript
// Append to portfolio-dashboard/src/utils/formatters.ts

import { ReasoningMetadata, TacticalSignal, UIMetaphor, ZScoreLabel } from '../types/signals';

/**
 * Client-side fallback: derives ReasoningMetadata from existing TacticalSignal fields.
 * Used when backend has not yet been deployed with the new RebalanceEngine.
 */
export const buildReasoningMetadata = (signal: TacticalSignal): ReasoningMetadata => {
  const z = signal.returnZScore ?? 0;
  const H = 0.5; // Unknown until new engine runs — assume random walk

  const zScoreLabel: ZScoreLabel =
    z <= -2.0 ? 'STATISTICALLY_CHEAP' :
    z <= -1.0 ? 'SLIGHTLY_CHEAP'      :
    z >=  2.0 ? 'OVERHEATED'          :
    z >=  1.0 ? 'SLIGHTLY_RICH'       : 'NEUTRAL';

  const uiMetaphor: UIMetaphor =
    signal.action === 'BUY'  && z <= -1.5 ? 'RUBBER_BAND'        :
    signal.action === 'SELL' && z >=  2.0 ? 'THERMOMETER'        :
    signal.action === 'SELL'              ? 'VOLATILITY_HARVEST' :
    signal.action === 'HOLD'              ? 'WAVE_RIDER'          : 'COOLING_OFF';

  const noobHeadline =
    signal.action === 'BUY'  && z <= -2.0
      ? `This fund is on a statistically rare discount (Z=${z.toFixed(1)}σ). A snapback is expected.`
    : signal.action === 'BUY'
      ? `This fund is underweight and shows a mild price dip (Z=${z.toFixed(1)}σ).`
    : signal.action === 'SELL' && z >= 2.0
      ? `This fund has grown hotter than usual (Z=+${z.toFixed(1)}σ). Time to trim before it cools.`
    : signal.action === 'SELL'
      ? `This fund has drifted overweight. Trimming it now locks in 'extra' gains to redeploy elsewhere.`
    : signal.action === 'HOLD' && (signal.actualPercentage > signal.plannedPercentage)
      ? `Overweight but holding — the trend looks strong. We won't cut profits yet.`
    : `All good — within target range. No action needed.`;

  return {
    primaryNarrative:    signal.justifications[0] ?? signal.schemeName,
    technicalLabel:      `Z-Score: ${z.toFixed(2)}σ`,
    noobHeadline,
    uiMetaphor,
    zScore:              z,
    hurstExponent:       H,
    volatilityTax:       0,
    hurstRegime:         'RANDOM_WALK',
    zScoreLabel,
    historicalRarityPct: zScoreLabel === 'STATISTICALLY_CHEAP' ? 2.3
                       : zScoreLabel === 'OVERHEATED'           ? 2.3
                       : 50,
    harvestAmountRupees: 0,
    harvestExplanation:  '',
  };
};

/**
 * Resolves metadata: uses backend-provided metadata if present, otherwise
 * synthesises it client-side from existing signal fields.
 */
export const resolveReasoningMetadata = (signal: TacticalSignal): ReasoningMetadata =>
  signal.reasoningMetadata ?? buildReasoningMetadata(signal);
```

---

### 5.4 Wiring into `RebalanceView.tsx`

Replace the existing raw signal rows with `RecommendationDetailCard`. The key integration points:

```tsx
// In RebalanceView.tsx — import the new component + resolver:

import { RecommendationDetailCard } from '../ui/RecommendationDetailCard';
import { resolveReasoningMetadata } from '../../utils/formatters';
import { TacticalSignal } from '../../types/signals';

// In the signals render section, replace the current signal row with:

{(opportunisticSignals as TacticalSignal[]).map((signal) => {
  // Ensure metadata is present (fallback for legacy signals)
  const enrichedSignal: TacticalSignal = {
    ...signal,
    reasoningMetadata: signal.reasoningMetadata ?? resolveReasoningMetadata(signal),
  };
  return (
    <RecommendationDetailCard
      key={signal.amfiCode}
      signal={enrichedSignal}
      isPrivate={isPrivate}
      defaultExpanded={signal.action === 'BUY' && (signal.returnZScore ?? 0) <= -2.0}
    />
  );
})}

// Apply the same pattern to activeSellSignals and exitQueue sections.
// The card auto-adapts its visual metaphor based on signal.action + reasoningMetadata.uiMetaphor.
```

---

## 6. Signal vs. Noise Display Logic

This is the complete translation table the frontend uses to render the `zScoreLabel` and `hurstRegime` fields
into human-readable UI elements. All values are derived from `ReasoningMetadata`:

| Technical Metric | "Noob" Translation | UI Visual | Color Token |
|---|---|---|---|
| Z-Score ≤ −2.0 | **"Statistically Cheap"** | 🏷️ Green "Discount" tag + `historicalRarityPct`% label | `text-buy` |
| Z-Score ≤ −1.0 | **"Mild Discount"** | Yellow dot | `text-warning` |
| Z-Score in (−1.0, +1.0) | **"Normal Range"** | No tag | `text-muted` |
| Z-Score ≥ +1.0 | **"Slightly Rich"** | Orange dot | `text-warning` |
| Z-Score ≥ +2.0 | **"Overheated"** | 🌡️ Red thermometer filling up | `text-exit` |
| Hurst < 0.45 | **"Bouncing Back"** | ↩️ U-turn arrow icon | `text-buy` |
| Hurst 0.45–0.55 | **"Unpredictable"** | 〰️ wave icon | `text-muted` |
| Hurst > 0.55 + overweight | **"Riding the Wave"** | 🏄 surfboard on wave | `text-accent` |
| Volatility Tax > 0 | **"Free Money"** | 🌾 Harvest icon | `text-buy` |
| SELL + Z ≥ +2σ + VT > 0 | **"Volatility Harvest"** | 🌾 + harvest amount in big green text | `text-buy` |

---

## 7. Visual Metaphor Specification

The `uiMetaphor` field in `ReasoningMetadata` maps to one of five visual metaphors:

### 🎯 RUBBER_BAND (BUY signals — mean-reverting + statistically cheap)
- **When triggered:** `action == BUY` AND (`H < 0.45` OR `Z ≤ -1.5`)
- **Visual:** SVG rubber band, stretched downward proportional to `|Z|`. Bounce increases with stretch.
- **Label logic:**
  - Z ≤ −2.0 → "Highly Overstretched — Snapback Expected"
  - Z ≤ −1.0 → "Moderately Stretched — Dip Opportunity"
- **Data shown:** `historicalRarityPct`% of historical days were this cheap.
- **Emotional tone:** Confident, opportunistic. Green palette.

### 🌾 VOLATILITY_HARVEST (SELL signals — drift-triggered trim)
- **When triggered:** `action == SELL` AND `Z < +2.0` (not yet overheated, just drifted)
- **Visual:** Large 🌾 emoji + harvest amount in large green currency value.
- **Narrative:** "We are capturing ₹X,XXX in 'extra' growth to fuel your laggards."
- **Emotional tone:** Positive framing — this is *not* a loss, it's a gain capture.

### 🌡️ THERMOMETER (SELL signals — overheated)
- **When triggered:** `action == SELL` AND `Z ≥ +2.0`
- **Visual:** Animated thermometer (red fill rises from 0 to Z/4σ).
- **Label:** "Overheated — +Xσ"
- **Emotional tone:** Urgent but clinical. Red palette. Show `100 - rarityPct`% expensive framing.

### 🏄 WAVE_RIDER (HOLD overrides — trending upward)
- **When triggered:** `action == HOLD` AND `H > 0.55` AND fund is overweight
- **Visual:** Animated SVG wave with a gold dot riding across it.
- **Headline:** "Riding the Wave — this fund is trending upward; we won't cut profits yet."
- **Emotional tone:** Calm, patient, trust-instilling. Accent/purple palette.

### ❄️ COOLING_OFF (neutral HOLD / WATCH states)
- **When triggered:** All other cases
- **Visual:** Simple thermometer emoji at room temperature.
- **Headline:** "Z-Score within normal range. No statistically significant signal."
- **Emotional tone:** Reassuring. Muted palette.

---

## 8. Migration & Rollout Plan

### Phase 1 — Database Only (No Code Changes, No User Impact)
1. Run `V4__add_hurst_volatility_columns.sql` migration.
2. New columns default to `0` / `'RANDOM_WALK'` / `0.5`. Existing API responses are unaffected.

### Phase 2 — Backend Engine (Nightly, Invisible to Users)
1. Deploy updated `QuantitativeEngineService` with Steps 4 and 5.
2. Deploy `HurstExponentService`.
3. Deploy updated `ConvictionMetricsRepository` with `updateRollingZScoreAndVolatilityTax()`.
4. Deploy updated `MarketMetrics`, `TacticalSignal`, `ReasoningMetadata` DTOs.
5. Deploy updated `RebalanceEngine`.
6. The next nightly engine run (11:30 PM IST) populates the new columns.
7. API now returns `reasoningMetadata` in every `TacticalSignal`. Old frontend ignores it gracefully.

### Phase 3 — Frontend UI
1. Deploy updated `types/signals.ts`.
2. Deploy `RecommendationDetailCard.tsx`.
3. Deploy updated `formatters.ts` with `resolveReasoningMetadata()`.
4. Wire into `RebalanceView.tsx`.
5. **Day-one fallback:** If backend hasn't run yet, `resolveReasoningMetadata()` synthesises the metadata
   client-side from `returnZScore` (existing field) so no blank cards appear.

### Rollback Plan
- All changes are additive. The new columns have `DEFAULT` values, new DTO fields are appended, and the
  frontend has a graceful fallback for `null` metadata.
- To rollback the engine: comment out Steps 4 and 5 in `QuantitativeEngineService`.
- To rollback the UI: swap `RecommendationDetailCard` back to the old inline signal row.
- No data is deleted or renamed at any step.

---

## Appendix: Mathematical Reference

### Rolling 252-day Z-Score

For a fund with daily log returns `r₁, r₂, ... rₙ` (N=252):

```
μ  = (1/N) Σ rᵢ                       ← Rolling mean
σ  = √[(1/N) Σ (rᵢ - μ)²]             ← Rolling standard deviation
Z  = (r_today - μ) / σ                 ← Today's Z-Score
```

**Interpretation:**
- Z = 0.0 → Today's return is exactly average. No signal.
- Z = −2.0 → Today's return is 2 standard deviations below average. Historically, only ~2.3% of days are this bad. Strong buy signal (if mean-reverting).
- Z = +2.0 → 2 standard deviations above average. Fund is running hot. Harvest signal.

### Hurst Exponent (R/S Analysis)

```
r[i]   = ln(P[i] / P[i-1])             ← Log daily returns
μ      = mean(r)                        ← Series mean
Y[i]   = Σ (r[j] - μ) for j=1..i      ← Cumulative deviation
R      = max(Y) - min(Y)               ← Range
S      = std(r)                        ← Standard deviation
H      = log(R/S) / log(N)            ← Hurst Exponent
```

**Interpretation:**
- H < 0.45 → Mean Reverting. Dips get bought, rallies get sold. Buying the dip is safe.
- H ≈ 0.50 → Random Walk (Brownian motion). No edge.
- H > 0.55 → Trending. Trends persist. Don't cut winners; don't buy losers yet.

### Volatility Tax (2σ²)

```
σ²_daily     = Var(r)                  ← Daily variance
σ²_annual    = σ²_daily × 252         ← Annualised variance
VolatilityTax = 2 × σ²_annual         ← Annual return drag from variance
```

**Why 2σ²?** The Itô correction / Kelly criterion shows that for a continuously rebalanced portfolio,
the geometric mean return = arithmetic mean return − σ²/2. The "tax" is σ²/2 per asset, but the
rebalancing *bonus* between two uncorrelated assets is σ²/2 per pair, giving a total opportunity of 2σ²
when expressed as the spread between holding and rebalancing. Rebalancing is, in this sense, literally
free money extracted from price volatility.

**Practical interpretation for the UI:** A fund with 30% annual volatility has σ² ≈ 0.09, so its
Volatility Tax ≈ 0.18 = 18% of the overweight position as annual drag. Harvesting even a fraction of this
is meaningful alpha.
```
