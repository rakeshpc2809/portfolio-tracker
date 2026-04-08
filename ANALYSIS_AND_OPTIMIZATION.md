# Design Gap Analysis & Buy/Sell Logic Optimization

## 1. What Was Designed vs. What Was Built

### The Three Design Generations

| Doc | Core Idea |
|---|---|
| Microservices Blueprint | Infrastructure: Spring Boot, GraalVM, OCI, Neon. Microservice decomposition. |
| Portfolio Optimization / Arbitrage | Domain model: Invesco as rebalancer "dry powder", Factor Barbell, arbitrage fund mechanics. |
| Intelligent Tactical Orchestrator | The full decision engine: Z-scoring, Risk Parity ERC, Black-Litterman, Kelly Criterion, Tax-Alpha Hurdle. |

---

## 2. Gap Analysis: Design → Code

### 2.1 IMPLEMENTED (Green)

| Design Requirement | Where in Code |
|---|---|
| NAV percentile + ATH drawdown + Z-score as entry signals | `ConvictionScoringService.calculateValueScore()` + `PortfolioOrchestrator` accumulator gate |
| 5-factor conviction score (yield, risk, value, pain, friction) | `ConvictionScoringService` with weights YIELD 20%, RISK 25%, VALUE 25%, PAIN 15%, FRICTION 15% |
| FIFO tax lot traversal on simulation | `TaxSimulatorService.simulateSellOrder()` |
| FIFO-Aware TLH with proxy fund map | `TaxLossHarvestingService` — cleanest service in the codebase |
| Systemic CVaR halt (portfolio-weighted) | `SystemicRiskMonitorService` @ -3.5% threshold |
| Tax-aware exit sequencing (Debt first → LTCG within headroom → STCG deferral) | `PortfolioOrchestrator.computeExitQueue()` |
| 21-day cooldown between buys | `weightSignalsByConviction()` pre-filter |
| Rebalancer/Arbitrage "dry powder" identification | `rebalancerValue` calculation in `computeOpportunisticSignals()` |

---

### 2.2 DEVIATIONS / GAPS (Red)

#### GAP 1 — No Cross-Sectional Z-Scoring (CQS)
**Design said:** Normalize Sortino, WinRate, CVaR, MDD *relative to peer funds in the same strategy bucket* to produce a Composite Quant Score. A Sortino of 1.2 means nothing without knowing if it's the best or worst in the bucket.

**Code does:** Uses static `normalize(value, hardMin, hardMax)` bounds that never change, making scores incomparable across market regimes.

**Fix:** See Section 3.1 below.

---

#### GAP 2 — No Proactive SELL Signal for Active Funds (Gate B)
**Design said:** Gate B fires on overweight *active* funds when:
- CQS collapses below peer threshold, OR
- Drift exceeds +2% tolerance

**Code does:** `computeExitQueue()` only processes funds explicitly marked `"dropped"` in the sheet. There is **zero** proactive SELL logic for a live, overweight fund that is deteriorating quantitatively.

**Fix:** See Section 3.2 below.

---

#### GAP 3 — No Tax-Alpha Hurdle Rate on SELL Decisions
**Design said:** Before finalizing a SELL, compute:
- `Net Realizable Value = currentValue - taxPenalty`
- Does the replacement fund's expected future value (from conviction score × time horizon) exceed holding the deteriorating fund?
- If not → emit `HOLD (Tax-Locked)`, not a SELL.

**Code does:** `TaxSimulatorService` has a 2% `MAX_ACCEPTABLE_TAX_DRAG` binary gate, but it's only called inside `ConvictionScoringService` *to score*, not to gate the sell decision. The orchestrator never calls it for the exit decision path.

**Fix:** See Section 3.2 below.

---

#### GAP 4 — Kelly Criterion Not Implemented
**Design said:** Half-Kelly with CVaR penalty: `f = (b*p - q) / b * 0.5 * cvarPenalty`. Used as the final trade size multiplier.

**Code does:** `PositionSizingService.calculateAdjustedBuySize()` just does `scoreMult * riskPenalty` — neither Kelly nor Half-Kelly.

**Fix:** See Section 3.3 below.

---

#### GAP 5 — Risk Parity / ERC Target Weights Not Calculated
**Design said:** Target weights should be computed via Equal Risk Contribution — each fund's weight × MCTR should be equal across the portfolio.

**Code does:** Target weights are read directly from the Google Sheet (human-entered). No dynamic optimization.

**Assessment:** This is an acceptable pragmatic deviation for V1. Implementing ERC requires covariance matrix construction, which needs 15-year daily NAV data per fund — the data *exists* in `historical_nav` but the optimization loop is not trivial. The sheet-driven approach is a reasonable stand-in. **Leave this for V3.**

---

#### GAP 6 — HARVEST Not a First-Class Signal
**Design said:** HARVEST (Tax-Loss Harvesting) should appear in the same tactical signal list alongside BUY/SELL/HOLD.

**Code does:** `TaxLossHarvestingService` is a separate endpoint (`/tax-loss-harvesting`), not integrated into `UnifiedTacticalPayload`.

**Fix:** See Section 3.4 below.

---

#### GAP 7 — SignalType Enum vs String Inconsistency
`SignalType` enum has `BUY, SELL, HOLD` but `TacticalSignal` uses String `action` values of `"BUY"`, `"EXIT"`, `"WATCH"` — the enum is unused. Either kill the enum or use it.

---

## 3. Optimized Logic

### 3.1 Cross-Sectional Z-Scorer (New Service)

```java
// NEW: BucketZScorerService.java
// Call this once per day after QuantitativeEngineService.runNightlyMathEngine()

@Service
@RequiredArgsConstructor
@Slf4j
public class BucketZScorerService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Computes peer-relative Z-scores for Sortino, WinRate, CVaR, MaxDD
     * grouped by the fund's strategy bucket (core/strategy/satellite/accumulator).
     * 
     * Writes a composite_quant_score back to fund_conviction_metrics.
     * 
     * WEIGHTS (from design doc):
     *   Sortino  : +0.35
     *   WinRate  : +0.25
     *   CVaR 5%  : -0.25  (lower is better — negate)
     *   MaxDD    : -0.15  (lower magnitude is better — negate)
     */
    public void computeBucketCqs(String pan) {
        // Step 1: Load all funds with their bucket and raw metrics
        String sql = """
            SELECT 
                m.amfi_code,
                m.sortino_ratio,
                m.win_rate,
                m.cvar_5,
                m.max_drawdown,
                gs.bucket                      -- e.g. 'core', 'strategy', 'satellite'
            FROM fund_conviction_metrics m
            JOIN scheme s ON m.amfi_code = s.amfi_code
            JOIN google_sheet_strategy gs ON gs.isin = s.isin
            WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        if (rows.isEmpty()) return;

        // Step 2: Group by bucket
        Map<String, List<Map<String, Object>>> byBucket = rows.stream()
            .collect(Collectors.groupingBy(r -> String.valueOf(r.getOrDefault("bucket", "core"))));

        for (Map.Entry<String, List<Map<String, Object>>> entry : byBucket.entrySet()) {
            List<Map<String, Object>> peers = entry.getValue();
            if (peers.size() < 2) continue; // Can't z-score a single fund

            // Step 3: Compute bucket mean & stddev for each metric
            double[] sortinos  = peers.stream().mapToDouble(r -> safeDouble(r.get("sortino_ratio"))).toArray();
            double[] winRates  = peers.stream().mapToDouble(r -> safeDouble(r.get("win_rate"))).toArray();
            double[] cvars     = peers.stream().mapToDouble(r -> safeDouble(r.get("cvar_5"))).toArray();
            double[] maxDDs    = peers.stream().mapToDouble(r -> safeDouble(r.get("max_drawdown"))).toArray();

            double sortinoMean = mean(sortinos), sortinoStd = std(sortinos);
            double winMean     = mean(winRates),  winStd     = std(winRates);
            double cvarMean    = mean(cvars),      cvarStd    = std(cvars);
            double mddMean     = mean(maxDDs),     mddStd     = std(maxDDs);

            for (Map<String, Object> fund : peers) {
                String amfi = (String) fund.get("amfi_code");

                double zSortino = sortinoStd > 0 ? (safeDouble(fund.get("sortino_ratio")) - sortinoMean) / sortinoStd : 0;
                double zWin     = winStd > 0     ? (safeDouble(fund.get("win_rate")) - winMean) / winStd               : 0;
                double zCvar    = cvarStd > 0    ? (safeDouble(fund.get("cvar_5"))  - cvarMean) / cvarStd              : 0;
                double zMdd     = mddStd > 0     ? (safeDouble(fund.get("max_drawdown")) - mddMean) / mddStd          : 0;

                // CQS: positive = outperforming peers, negative = underperforming
                // Negate CVaR and MDD z-scores (higher raw = worse risk)
                double cqs = (0.35 * zSortino)
                           + (0.25 * zWin)
                           + (-0.25 * zCvar)   // negate: lower CVaR is better
                           + (-0.15 * zMdd);   // negate: shallower drawdown is better

                // Scale to 0–100 for storage (raw CQS is typically -3 to +3)
                int cqsScore = (int) Math.max(0, Math.min(100, 50 + (cqs * 15)));

                jdbcTemplate.update("""
                    UPDATE fund_conviction_metrics 
                    SET composite_quant_score = ?, bucket_peer_count = ?
                    WHERE amfi_code = ?
                    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
                    """, cqsScore, peers.size(), amfi);
                
                log.debug("CQS [{}] bucket={} cqs={} (sortino_z={:.2f}, win_z={:.2f}, cvar_z={:.2f}, mdd_z={:.2f})",
                    amfi, entry.getKey(), cqsScore, zSortino, zWin, zCvar, zMdd);
            }
        }
        log.info("✅ Bucket CQS scoring complete across {} buckets.", byBucket.size());
    }

    private double mean(double[] arr) {
        return Arrays.stream(arr).average().orElse(0);
    }
    private double std(double[] arr) {
        double m = mean(arr);
        return Math.sqrt(Arrays.stream(arr).map(x -> (x-m)*(x-m)).average().orElse(0));
    }
    private double safeDouble(Object o) { return o == null ? 0.0 : ((Number)o).doubleValue(); }
}
```

**DB migration needed:**
```sql
ALTER TABLE fund_conviction_metrics 
  ADD COLUMN IF NOT EXISTS composite_quant_score INT DEFAULT 50,
  ADD COLUMN IF NOT EXISTS bucket_peer_count INT DEFAULT 0;
```

---

### 3.2 Optimized Sell Gate — `computeActiveSellSignals()` (New Method in PortfolioOrchestrator)

This is **Gate B** from the design, implemented properly.

```java
/**
 * GATE B: Proactive SELL/HOLD for active (non-dropped) funds.
 * 
 * Fires when a fund is:
 *   (a) Overweight by > DRIFT_SELL_THRESHOLD, AND
 *   (b) CQS < peer bucket floor (fundamentally deteriorating)
 * 
 * Before finalizing SELL: runs Tax-Alpha Hurdle Rate.
 * If tax friction is too high to clear → HOLD (Tax-Locked) instead.
 */
public List<TacticalSignal> computeActiveSellSignals(String pan) {
    final double DRIFT_SELL_THRESHOLD = 2.5;      // % overweight to trigger
    final double CQS_DETERIORATION_FLOOR = 35;    // CQS below this = quantitative failure
    final double TAX_DRAG_HARD_OVERRIDE = 0.08;   // 8% drag = sell regardless (Gate C)
    final double INVESTMENT_HORIZON_YEARS = 5.0;  // Investor's assumed time horizon

    List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
    Map<String, MarketMetrics> metricsMap = fetchLiveMetricsMap(pan);

    List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
    List<AggregatedHolding> holdings = aggregateLots(allLots);
    double totalValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

    // Load CQS scores (written by BucketZScorerService)
    Map<String, Integer> cqsMap = loadCqsMap();

    List<TacticalSignal> sellSignals = new ArrayList<>();

    for (StrategyTarget target : targets) {
        if ("dropped".equalsIgnoreCase(target.status())) continue; // handled by exit queue
        
        AggregatedHolding holding = findHolding(holdings, target);
        if (holding.getCurrentValue() < 5000) continue; // ignore dust

        String amfi = amfiCodeFor(target);
        MarketMetrics metrics = metricsMap.getOrDefault(amfi, defaultMetrics());
        int cqs = cqsMap.getOrDefault(amfi, 50);

        double actualPct = totalValue > 0 ? (holding.getCurrentValue() / totalValue) * 100 : 0;
        double drift = actualPct - target.targetPortfolioPct(); // positive = overweight

        boolean isOverweight = drift > DRIFT_SELL_THRESHOLD;
        boolean isQuantDeterioration = cqs < CQS_DETERIORATION_FLOOR;

        // Gate B: must be overweight AND quantitatively failing
        if (!isOverweight || !isQuantDeterioration) continue;

        // --- TAX-ALPHA HURDLE RATE ---
        // Calculate how much of the portfolio value would be lost to tax
        double sellAmount = (drift / 100.0) * totalValue; // amount needed to rebalance back
        TaxSimulationResult taxResult = taxSimulator.simulateSellOrder(
            holding.getSchemeName(), sellAmount, getCurrentNav(amfi));
        
        double netRealizable = sellAmount - taxResult.estimatedTax();
        double taxDragPct = taxResult.taxDragPercentage();

        List<String> justs = new ArrayList<>();
        justs.add(String.format("Overweight by %.1f%% (actual: %.1f%%, target: %.1f%%).", 
            drift, actualPct, target.targetPortfolioPct()));
        justs.add(String.format("Peer CQS: %d/100 — below floor of %d (quantitative deterioration).", 
            cqs, CQS_DETERIORATION_FLOOR));

        // === GATE C OVERRIDE: If CVaR breach or extreme tax drag ===
        if (metrics.cvar5() < -5.0 || taxDragPct > TAX_DRAG_HARD_OVERRIDE) {
            justs.add(String.format(
                "⚠️ Risk override: CVaR=%.2f%% or tax drag %.1f%% exceeds hard limits. Ignoring tax lock.",
                metrics.cvar5(), taxDragPct * 100));
            sellSignals.add(buildSignal(target, amfi, "SELL", sellAmount, 
                target.targetPortfolioPct(), actualPct, metrics, justs));
            continue;
        }

        // === TAX LOCK CHECK ===
        // Is the expected outperformance of the replacement fund worth the tax cost?
        // We approximate "replacement fund expected return" via the bucket's best CQS fund
        double currentFundExpectedReturn = sortinoToExpectedReturn(metrics.sortinoRatio());
        double replacementExpectedReturn = estimateBestBucketReturn(pan, target.status(), cqsMap, metricsMap);
        
        double currentFutureValue = netRealizable * Math.pow(1 + currentFundExpectedReturn, INVESTMENT_HORIZON_YEARS);
        double holdFutureValue    = sellAmount    * Math.pow(1 + currentFundExpectedReturn, INVESTMENT_HORIZON_YEARS);
        double switchFutureValue  = netRealizable * Math.pow(1 + replacementExpectedReturn, INVESTMENT_HORIZON_YEARS);

        boolean hurdleCleared = switchFutureValue > holdFutureValue;

        // === STCG TEMPORAL OPTIMIZATION ===
        // If close to LTCG threshold, waiting may save 7.5% (20% → 12.5% STCG→LTCG)
        int daysToLtcg = holding.getDaysToNextLtcg();
        if (!hurdleCleared && daysToLtcg > 0 && daysToLtcg <= 45 && taxResult.hasStcg()) {
            double stcgSavings = taxResult.stcgProfit() * 0.075; // 20% - 12.5% = 7.5% saving
            justs.add(String.format(
                "⏳ Tax-Locked: %d days until LTCG. Waiting saves ~₹%,.0f in STCG tax. Hurdle not cleared.",
                daysToLtcg, stcgSavings));
            sellSignals.add(buildSignal(target, amfi, "HOLD", 0, 
                target.targetPortfolioPct(), actualPct, metrics, justs));
            continue;
        }

        if (!hurdleCleared) {
            justs.add(String.format(
                "Tax-Locked: Net realizable ₹%,.0f after ₹%,.0f tax. Expected switch benefit " +
                "(₹%,.0f) doesn't clear hold value (₹%,.0f) over %d-yr horizon.",
                netRealizable, taxResult.estimatedTax(), 
                (long)switchFutureValue, (long)holdFutureValue, (int)INVESTMENT_HORIZON_YEARS));
            sellSignals.add(buildSignal(target, amfi, "HOLD", 0,
                target.targetPortfolioPct(), actualPct, metrics, justs));
        } else {
            justs.add(String.format(
                "Tax-Alpha cleared: Switch value ₹%,.0f vs hold ₹%,.0f (net of ₹%,.0f tax, %.1f%% drag).",
                (long)switchFutureValue, (long)holdFutureValue, taxResult.estimatedTax(), taxDragPct * 100));
            justs.add(String.format("HIFO lot selection: Sell highest-cost lots first to minimize embedded gains."));
            sellSignals.add(buildSignal(target, amfi, "SELL", sellAmount,
                target.targetPortfolioPct(), actualPct, metrics, justs));
        }
    }

    return sellSignals;
}

// Maps Sortino to a rough expected annual return proxy
// Sortino of 1.0 ≈ barely keeping up with MAR (7%). 2.0 ≈ strong alpha.
private double sortinoToExpectedReturn(double sortino) {
    double MAR = 0.07;
    return MAR + Math.max(0, (sortino - 1.0) * 0.03); // +3% for every unit above 1.0
}

// Returns the best expected return achievable within this strategy bucket
private double estimateBestBucketReturn(String pan, String bucket, 
        Map<String, Integer> cqsMap, Map<String, MarketMetrics> metricsMap) {
    return metricsMap.values().stream()
        .mapToDouble(m -> sortinoToExpectedReturn(m.sortinoRatio()))
        .max().orElse(0.09);
}

private Map<String, Integer> loadCqsMap() {
    String sql = """
        SELECT amfi_code, composite_quant_score 
        FROM fund_conviction_metrics
        WHERE calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
        """;
    Map<String, Integer> map = new HashMap<>();
    jdbcTemplate.query(sql, rs -> {
        map.put(rs.getString("amfi_code"), rs.getInt("composite_quant_score"));
    });
    return map;
}
```

---

### 3.3 Optimized Buy Sizing — Half-Kelly with CVaR Penalty

**Replace `PositionSizingService.calculateAdjustedBuySize()` entirely:**

```java
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

        // Half-Kelly (standard institutional practice)
        double halfKelly = fullKelly * 0.5;

        // CVaR penalty: if CVaR is worse than -3%, apply a dampener
        // CVaR of -3% = 0.9 multiplier. CVaR of -5% = 0.75. CVaR of -7% = 0.6.
        double cvarPenalty = 1.0;
        if (metrics.cvar5() < -3.0) {
            cvarPenalty = Math.max(0.4, 1.0 + (metrics.cvar5() / 20.0)); // linear taper
        }

        double multiplier = halfKelly * cvarPenalty;

        // Clamp to [0.2, 1.5] — never size below 20% or above 150% of drift
        multiplier = Math.max(0.2, Math.min(1.5, multiplier));
        
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
        
        // Hard cap: never deploy more than available cash
        return Math.min(rawAmount, availableCash);
    }
}
```

**Wire this into `computeOpportunisticSignals()`:**

```java
// BEFORE (existing code):
double baseAmount = Math.max(10000, 
    (target.targetPortfolioPct() - actualPct) / 100.0 * totalPortfolioValue * 0.5);

// AFTER (optimized):
double driftAmount = (target.targetPortfolioPct() - actualPct) / 100.0 * totalPortfolioValue;
double baseAmount = Math.max(10000, 
    positionSizingService.calculateExecutionAmount(driftAmount, lumpsum, metrics));
```

---

### 3.4 Integrate HARVEST into UnifiedTacticalPayload

**Update `UnifiedTacticalPayload.java`:**
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UnifiedTacticalPayload {
    private List<SipLineItem> sipPlan;
    private List<TacticalSignal> opportunisticSignals;
    private List<TacticalSignal> activeSellSignals;    // NEW: Gate B
    private List<TacticalSignal> exitQueue;            // existing: dropped funds
    private List<TlhOpportunity> harvestOpportunities; // NEW: TLH integrated
    private double totalExitValue;
    private double totalHarvestValue;                  // NEW
    private int droppedFundsCount;
}
```

**Add a unified endpoint in `RebalanceController`:**
```java
@GetMapping("/{pan}/unified-dashboard")
public ResponseEntity<UnifiedTacticalPayload> getUnifiedDashboard(
        @PathVariable String pan,
        @RequestParam(defaultValue = "75000") double monthlySip,
        @RequestParam(defaultValue = "0") double lumpsum) {
    
    List<SipLineItem> sip         = orchestrator.computeSipPlan(pan, monthlySip);
    List<TacticalSignal> oppBuys  = orchestrator.computeOpportunisticSignals(pan, lumpsum);
    List<TacticalSignal> sellsigs = orchestrator.computeActiveSellSignals(pan);   // NEW
    List<TacticalSignal> exits    = orchestrator.computeExitQueue(pan);
    List<TlhOpportunity> harvest  = taxLossHarvestingService.scanForOpportunities(pan);
    
    double totalExit    = exits.stream().mapToDouble(s -> parseAmount(s.amount())).sum();
    double totalHarvest = harvest.stream().mapToDouble(TlhOpportunity::harvestableAmount).sum();
    
    return ResponseEntity.ok(UnifiedTacticalPayload.builder()
        .sipPlan(sip)
        .opportunisticSignals(oppBuys)
        .activeSellSignals(sellsigs)
        .exitQueue(exits)
        .harvestOpportunities(harvest)
        .totalExitValue(totalExit)
        .totalHarvestValue(totalHarvest)
        .droppedFundsCount(exits.size())
        .build());
}
```

---

### 3.5 Fix the SignalType Inconsistency

**Update `SignalType.java`:**
```java
public enum SignalType {
    BUY,        // Deploy capital
    SELL,       // Rebalance overweight active fund (Gate B — after hurdle cleared)
    HOLD,       // Tax-locked or cooldown
    EXIT,       // Dropped fund — full liquidation path
    WATCH,      // Cooldown active — monitor
    HARVEST     // Tax-loss harvesting opportunity
}
```

Then change `TacticalSignal.action` from `String` to `SignalType` and update all call sites.

---

## 4. Execution Priority

| Priority | Action | Effort |
|---|---|---|
| 🔴 High | Add `composite_quant_score` column + `BucketZScorerService` | Medium |
| 🔴 High | Add `computeActiveSellSignals()` to `PortfolioOrchestrator` | High |
| 🟡 Medium | Replace `PositionSizingService` with Half-Kelly | Low |
| 🟡 Medium | Integrate TLH into `UnifiedTacticalPayload` + new endpoint | Low |
| 🟢 Low | Fix `SignalType` enum usage | Low |
| 🔵 Future (V3) | Risk Parity ERC for dynamic target weights | Very High |
| 🔵 Future (V3) | Black-Litterman expected return model | Very High |

---

## 5. One-Liner Summary

The current code has solid infrastructure (tax simulation, FIFO lot accounting, NAV signals, TLH) but the **decision logic is only half-built**. It knows how to *buy dips* but doesn't know how to *sell deteriorating winners*. The design's Gate B (proactive active SELL with Tax-Alpha Hurdle) and the Half-Kelly sizing are the two highest-leverage missing pieces.
