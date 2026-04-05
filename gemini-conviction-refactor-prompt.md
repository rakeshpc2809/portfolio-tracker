# Gemini CLI Prompt — Conviction Scoring Refactor

You are working on a Spring Boot + Python portfolio tracker called `cas-injector`.
Below is a precise, self-contained brief. Read all sections before touching any file.

---

## Context — what this system does

The system scores mutual funds with a 0–100 "conviction score" to guide buy/sell/hold decisions.
The score has five weighted components:

| Component   | Weight | Current source                        |
|-------------|--------|---------------------------------------|
| Yield       | 20%    | Personal CAGR from tax lots           |
| Risk        | 25%    | Sortino ratio (from NAV history SQL)  |
| Value       | 25%    | PE/PB ratios scraped from holdings    |
| Pain        | 15%    | Max drawdown (from NAV history SQL)   |
| Friction    | 15%    | Tax drag simulation                   |

**The problem:** The Value component (25% weight) is broken.
- PE/PB data is missing for 7 of 21 funds (debt, gold, sector, some equity).
- For the 14 funds that have it, `coverage_pct` is below 80% for several.
- The Z-score logic in `calculateZScore()` requires 10+ data points from `fund_metrics`
  joined against `index_fundamentals` on matching dates. The system has only ~1 month of
  history so `spreads.size() < 10` is true for every fund — Z-score silently returns 0.0
  for all funds, every time. The whole Z-score path is a no-op.
- `MarketClimateService.assessFundValuations()` sets `currentPe = benchmarkPe` for every
  fund (comparing a fund to itself), making its output meaningless. It is dead code.
- Applying equity PE/PB signals to debt funds, gilt funds, gold FoFs, and sector funds
  is conceptually wrong.

**The fix:** Replace the PE/PB-based Value component with three signals computed purely
from NAV history (`fund_history` table), which is complete for every fund. Keep the
`scraper.py` and `index_fundamentals` table for Sortino/CVaR context — just stop using
PE/PB as the primary value signal.

---

## Changes required — read this fully before writing any code

### 1. NEW SQL in `QuantitativeEngineService.java`

Add a second SQL block after the existing Sortino/CVaR INSERT. This computes three new
columns and UPSERTs them into `fund_conviction_metrics`:

```sql
-- Add to the end of runNightlyMathEngine(), as a second jdbcTemplate.update() call

WITH nav_stats AS (
    SELECT
        amfi_code,
        MAX(nav) FILTER (WHERE nav_date >= CURRENT_DATE - INTERVAL '3 years') AS max_3yr,
        MIN(nav) FILTER (WHERE nav_date >= CURRENT_DATE - INTERVAL '3 years') AS min_3yr,
        MAX(nav) AS ath_nav,
        (SELECT nav FROM fund_history h2
         WHERE h2.amfi_code = h.amfi_code
         ORDER BY nav_date DESC LIMIT 1) AS current_nav
    FROM fund_history h
    GROUP BY amfi_code
),
percentile_and_ath AS (
    SELECT amfi_code,
        CASE WHEN (max_3yr - min_3yr) > 0
            THEN (current_nav - min_3yr) / (max_3yr - min_3yr)
            ELSE 0.5 END AS nav_percentile_3yr,
        (current_nav - ath_nav) / NULLIF(ath_nav, 0) AS drawdown_from_ath
    FROM nav_stats
),
rolling_1yr_returns AS (
    SELECT amfi_code, nav_date,
        (nav / NULLIF(LAG(nav, 252) OVER (PARTITION BY amfi_code ORDER BY nav_date), 0) - 1)
            AS return_1yr
    FROM fund_history
    WHERE nav_date >= CURRENT_DATE - INTERVAL '4 years'
),
return_z AS (
    SELECT amfi_code,
        AVG(return_1yr) AS mean_1yr,
        STDDEV(return_1yr) AS std_1yr,
        (SELECT return_1yr FROM rolling_1yr_returns r2
         WHERE r2.amfi_code = r.amfi_code AND return_1yr IS NOT NULL
         ORDER BY nav_date DESC LIMIT 1) AS latest_1yr
    FROM rolling_1yr_returns r
    WHERE return_1yr IS NOT NULL
    GROUP BY amfi_code
)
UPDATE fund_conviction_metrics fcm
SET
    nav_percentile_3yr    = p.nav_percentile_3yr,
    drawdown_from_ath     = p.drawdown_from_ath,
    return_z_score        = CASE WHEN rz.std_1yr > 0
                                THEN (rz.latest_1yr - rz.mean_1yr) / rz.std_1yr
                                ELSE 0 END
FROM percentile_and_ath p
LEFT JOIN return_z rz ON p.amfi_code = rz.amfi_code
WHERE fcm.amfi_code = p.amfi_code
  AND fcm.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics);
```

Also add the three new columns to the `fund_conviction_metrics` table DDL
(use `spring.jpa.hibernate.ddl-auto=update` — Hibernate will auto-add them if you add
them to the entity, OR add a Flyway/Liquibase migration, OR add a manual ALTER TABLE.
Choose whichever pattern the project already uses — check `application.properties` for
`ddl-auto` setting. If it is `update`, just add the fields to the JPA entity.)

New columns:
```sql
ALTER TABLE fund_conviction_metrics
    ADD COLUMN IF NOT EXISTS nav_percentile_3yr DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS drawdown_from_ath   DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS return_z_score      DOUBLE PRECISION;
```

---

### 2. REWRITE `ConvictionScoringService.java` — the value score block

**Delete entirely:**
- The private method `calculateZScore(String amfiCode, String benchmarkIndex, double currentFundPe, double currentBenchPe)`
- The private method `determineValuationStatus(double zScore, double peRelative)`
- All variables: `fundPe`, `fundPb`, `benchPe`, `benchPb`, `zScore`, `peRelative`, `relativePart`, `zScorePart`, `valStatus`
- The rebound bonus block (the comment says "VALUE HUNTER (REBOUND ALPHA BONUS)") —
  the `reboundBonus` variable and its addition to `baseConviction`. This double-counts
  the risk score.

**The fetchSql query:** Remove the LEFT JOINs to `fund_metrics` and `index_fundamentals`.
Add the three new columns from `fund_conviction_metrics` itself:

```sql
SELECT m.amfi_code, m.sortino_ratio, m.max_drawdown, m.calculation_date,
       m.nav_percentile_3yr, m.drawdown_from_ath, m.return_z_score,
       s.asset_category
FROM fund_conviction_metrics m
JOIN scheme s ON m.amfi_code = s.amfi_code
WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
AND m.amfi_code IN (
    SELECT s2.amfi_code
    FROM scheme s2
    JOIN folio fol ON s2.folio_id = fol.id
    WHERE fol.investor_pan = ?
)
```

**Replace the value score calculation** with this logic (add as a private method
`calculateValueScore`):

```java
private double calculateValueScore(double navPercentile, double athDrawdown,
                                   double returnZScore, String assetCategory) {
    String cat = assetCategory != null ? assetCategory.toUpperCase() : "";

    // Debt, gilt, bond, gold — PE signals are meaningless. Return neutral.
    if (cat.contains("DEBT") || cat.contains("GILT") || cat.contains("BOND")
            || cat.contains("GOLD") || cat.contains("LIQUID")) {
        return 50.0;
    }

    // High navPercentile = near 3yr high = expensive. Invert it.
    double percentileScore = invertNormalize(navPercentile, 0.1, 0.9) * 100;

    // Deep ATH drawdown = opportunity. athDrawdown is negative (e.g. -0.22).
    // Math.abs gives the magnitude; higher magnitude = more of an opportunity.
    double athScore = normalize(Math.abs(athDrawdown), 0.0, 0.40) * 100;

    // Negative returnZScore = fund has had a poor recent year vs its own history = cheap.
    // Invert: lower Z → higher score.
    double returnZNorm = invertNormalize(returnZScore, -2.0, 2.0) * 100;

    // All active equity (index and active alike)
    return (percentileScore * 0.50) + (athScore * 0.30) + (returnZNorm * 0.20);
}
```

**In `calculateAndSaveFinalScores`**, replace the value score assignment line with:

```java
double navPercentile  = fund.get("nav_percentile_3yr")  != null
    ? ((Number) fund.get("nav_percentile_3yr")).doubleValue()  : 0.5;
double athDrawdown    = fund.get("drawdown_from_ath")    != null
    ? ((Number) fund.get("drawdown_from_ath")).doubleValue()   : 0.0;
double returnZScore   = fund.get("return_z_score")       != null
    ? ((Number) fund.get("return_z_score")).doubleValue()      : 0.0;
String assetCategory  = (String) fund.get("asset_category");

double valueScore = calculateValueScore(navPercentile, athDrawdown, returnZScore, assetCategory);
```

**Update the SQL UPDATE** at the end of the loop. Remove `pe_ratio`, `pb_ratio`,
`z_score`, `coverage_pct`, `valuation_status` from the SET clause. Replace with:

```java
String updateSql = """
    UPDATE fund_conviction_metrics
    SET conviction_score = ?
    WHERE amfi_code = ?
    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
""";
jdbcTemplate.update(updateSql, finalScore, amfiCode);
```

(The nav_percentile_3yr, drawdown_from_ath, return_z_score columns are already written
by `QuantitativeEngineService`. Do not duplicate the update here.)

---

### 3. DELETE `MarketClimateService.java` — entirely

**Reason:** `assessFundValuations()` sets `currentPe = benchmarkPe` for every fund,
making it compare a fund to itself. The method produces no useful output and is not
called anywhere in the conviction scoring pipeline. The `syncMarketClimateData()` method
duplicates what `MetricsSchedulerService` already does (runs scraper.py via
ProcessBuilder).

Steps:
1. Delete the file `MarketClimateService.java`.
2. Find any `@Autowired` or constructor injection of `MarketClimateService` in other
   classes and remove those references.
3. Delete `ConvictionMetricsController.java` if its only purpose is exposing
   `MarketClimateService` endpoints — check the file first. If it also exposes
   `ConvictionScoringService` results, keep the file and only remove the
   `MarketClimateService`-specific endpoints.

---

### 4. UPDATE `MarketMetrics.java` (the record)

Remove fields that no longer exist in the scoring pipeline:

**Remove:** `peRatio`, `pbRatio`, `zScore`, `coveragePct`, `valuationStatus`

**Add:** `navPercentile3yr`, `drawdownFromAth`, `returnZScore`

New record:
```java
public record MarketMetrics(
    int convictionScore,
    double sortinoRatio,
    double cvar5,
    double winRate,
    double maxDrawdown,
    double navPercentile3yr,
    double drawdownFromAth,
    double returnZScore,
    LocalDate lastBuyDate
) {}
```

After changing this record, fix all call sites — the compiler will show you every place
`MarketMetrics` is constructed. The main one is `fetchLiveMetricsMap()` in
`PortfolioOrchestrator.java`. Update it to read the three new columns and drop the five
removed ones.

---

### 5. UPDATE `PortfolioOrchestrator.java` — `fetchLiveMetricsMap()`

In the SQL string, remove `m.pe_ratio`, `m.pb_ratio`, `m.z_score`, `m.coverage_pct`,
`m.valuation_status`.

Add: `m.nav_percentile_3yr`, `m.drawdown_from_ath`, `m.return_z_score`

In the `MarketMetrics` constructor call inside the loop, replace the removed fields with
the three new ones read from the row map.

Also remove `m.pe_ratio`, `m.pb_ratio`, `m.z_score`, `m.coverage_pct`,
`m.valuation_status` from the `TacticalSignal` record/DTO if they are only there to
carry MarketClimateService data to the frontend. Check `TacticalSignal.java` — if it has
these fields, remove them and fix the `createSignal()` helper accordingly.

---

### 6. UPDATE `FundConvictionDTO.java`

Remove: `currentPe`, `benchmarkPe`, `valuationFlag`

These fields were fed by `MarketClimateService` which is being deleted.
If anything still references this DTO, update those references.

---

### 7. UPDATE `metrics_engine.py` — stop writing PE/PB to the scoring pipeline

The `metrics_engine.py` still computes PE/PB by fetching holdings from mfdata.in and
calculating weighted-average PE using yfinance stock prices. This is the source of the
unreliable `fund_metrics.pe_ratio` and `fund_metrics.pb_ratio` columns.

**Do NOT delete `metrics_engine.py`** — it also writes `nav`, `expense_ratio`, `aum_cr`
and the `holdings_snapshot` table which may be used elsewhere.

**Instead:** Remove the `pe_ratio` and `pb_ratio` computation and stop writing those
columns to `fund_metrics`. Specifically:
- Remove the `get_ticker_by_isin()` function and the `_ratio_cache` dict.
- Remove the `HoldingsSnapshot` SQLAlchemy model class (the `holdings_snapshot` table)
  unless it is read by the Java backend — check `SchemeRepository` or any
  `@Query` annotations for references to `holdings_snapshot`. If nothing reads it, remove it.
- Remove the loop that fetches stock-level PE/PB and accumulates `weighted_pe` /
  `weighted_pb` per fund.
- In the `FundMetric` SQLAlchemy model, keep `nav`, `expense_ratio`, `aum_cr`,
  `scheme_name`, `fetch_date`, `scheme_code`. Remove `pe_ratio`, `pb_ratio`,
  `coverage_pct`, `holdings_as_of`.
- Keep the AMFI NAV fetch and the upsert into `fund_metrics` for `nav`, `expense_ratio`,
  `aum_cr` — these are still useful.

---

### 8. KEEP unchanged (do not touch)

- `scraper.py` — still needed for `index_fundamentals` (PE/PB of the *index itself*,
  not the funds). This feeds `QuantitativeEngineService` context queries.
- `QuantitativeEngineService.java` — keep the existing Sortino/CVaR/drawdown SQL
  intact. Only add the new NAV signal SQL block described in step 1.
- `TaxLossHarvestingService.java` — keep entirely.
- `TaxSimulatorService.java` — keep entirely.
- `CasProcessingService.java` — keep entirely.
- `DashboardService.java` — keep entirely.
- `GoogleSheetService.java` — keep entirely.
- `HistoricalBackfillerService.java` — keep entirely.
- `MetricsSchedulerService.java` — keep entirely (it schedules both scripts).
- All transaction, folio, investor, scheme models and repositories — untouched.

---

## Summary of files changed

| File | Action |
|---|---|
| `QuantitativeEngineService.java` | Add second SQL block for NAV signals |
| `ConvictionScoringService.java` | Rewrite value score; delete calculateZScore, determineValuationStatus, rebound bonus |
| `MarketClimateService.java` | **DELETE** |
| `ConvictionMetricsController.java` | Remove MarketClimateService endpoints; keep if other endpoints exist |
| `MarketMetrics.java` | Replace 5 old fields with 3 new ones |
| `FundConvictionDTO.java` | Remove currentPe, benchmarkPe, valuationFlag |
| `PortfolioOrchestrator.java` | Update fetchLiveMetricsMap() SQL and MarketMetrics construction |
| `TacticalSignal.java` | Remove peRatio, pbRatio, zScore, coveragePct, valuationStatus if present |
| `metrics_engine.py` | Remove PE/PB scraping; keep NAV/AUM/expense fetch |
| `fund_conviction_metrics` table | Add 3 new columns (via ddl-auto or ALTER TABLE) |
| `fund_metrics` table | pe_ratio, pb_ratio, coverage_pct columns become unused (leave in DB, just stop writing) |

---

## Validation checklist — confirm these before finishing

1. `ConvictionScoringService` compiles with no references to `calculateZScore`,
   `determineValuationStatus`, `fundPe`, `benchPe`, `zScore`, `peRelative`, or `reboundBonus`.
2. `MarketMetrics` record has exactly 9 fields: `convictionScore`, `sortinoRatio`,
   `cvar5`, `winRate`, `maxDrawdown`, `navPercentile3yr`, `drawdownFromAth`,
   `returnZScore`, `lastBuyDate`.
3. Every place `new MarketMetrics(...)` is called compiles without errors.
4. No class imports `MarketClimateService`.
5. `metrics_engine.py` no longer imports `yfinance` or references `pe_ratio`, `pb_ratio`,
   `coverage_pct`, or `holdings_snapshot`.
6. The application starts without errors (`mvn spring-boot:run` or Docker Compose up).
7. After the nightly engine runs once, `fund_conviction_metrics` rows have non-null values
   in `nav_percentile_3yr`, `drawdown_from_ath`, `return_z_score` for funds that have
   at least 1 year of NAV history.
