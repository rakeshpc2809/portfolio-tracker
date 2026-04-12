# Portfolio OS — Bug Diagnosis & Fix Guide
## Root cause analysis of broken metrics + data opportunity map

> **Gemini: Work through Part 1 (all bugs) before Part 2 (enhancements).
> Each task is self-contained. If a step fails, fix the compile error from
> existing codebase patterns, annotate what changed, and continue.**

---

## Part 1 — Root Cause Diagnosis (Why metrics show 0)

### The Three-Layer Failure

The screenshot shows YIELD=0, RISK=0, VALUE=0, PAIN=0, FRICTION=0 and
"Historical data loading or insufficient data points" for most funds.
This is caused by three separate but related bugs:

---

### Bug 1 — `ConvictionScoringService` is never called for new users (CRITICAL)

**File:** `BackfillController.java`

The current `/admin/force-sync` endpoint:
```java
@PostMapping("/force-sync")
public ResponseEntity<String> forceSync(
    @RequestParam(required = false, defaultValue = "CFXPR4533R") String pan) {
    ...
    convictionScoringService.calculateAndSaveFinalScores(pan);
```

**Problem:** The default PAN is hardcoded to `"CFXPR4533R"`. Since you added a login system,
any user who logs in with a different PAN will NEVER get their conviction scores computed.
The `yieldScore`, `riskScore`, `valueScore`, `painScore`, `frictionScore` columns all remain
at 0 (NULL gets cast to 0) for everyone except the original hardcoded PAN.

**Bug 2 — `QuantitativeEngineService` never calls `ConvictionScoringService`**

`runNightlyMathEngine()` runs 7 steps (Sortino, NavSignals, BucketCQS, ZScore, Hurst, OU, HMM)
but NEVER calls `ConvictionScoringService.calculateAndSaveFinalScores()`.

That service is the ONLY place that computes the 5 sub-scores. It's investor-specific
(uses personal tax lots + personal CAGR) so it can't just be one SQL. But it's
completely absent from the automated pipeline.

**Bug 3 — `HistoricalDataController` has a leading-zero mismatch**

`HistoricalDataController.getFundHistory()` calls:
```java
navRepo.findByAmfiCodeOrderByNavDateDesc(amfiCode)
```
This is an EXACT string match. If `fund_history` stores codes like `"119551"`
but the frontend sends `"0119551"` (because `SchemePerformanceDTO.amfiCode` still
has a leading zero), the query returns 0 rows → "Insufficient data points".

---

## Fix 1A — Remove hardcoded PAN, make scoring multi-investor

**File:** `BackfillController.java`

```
Gemini prompt:

In BackfillController.java, make these changes:

1. Inject InvestorRepository (add to constructor fields):
   private final InvestorRepository investorRepository;
   (Already imported in other files — add import if needed)

2. Change the forceSync endpoint to run for ALL investors, not just one:

   @PostMapping("/force-sync")
   public ResponseEntity<String> forceSync(
       @RequestParam(required = false) String pan) {
       
     if (quantitativeEngineService.getIsRunning().get()) {
       return ResponseEntity.badRequest().body("Engine sync is already in progress.");
     }
     
     new Thread(() -> {
       // Step 1: Run the global quant engine (market-wide metrics)
       quantitativeEngineService.runNightlyMathEngine();
       
       // Step 2: Run per-investor scoring
       // If a specific PAN was provided, score only that investor
       // Otherwise score ALL investors
       List<String> pansToScore = (pan != null && !pan.isBlank())
         ? List.of(pan.trim().toUpperCase())
         : investorRepository.findAll().stream()
             .map(inv -> inv.getPan())
             .toList();
       
       for (String investorPan : pansToScore) {
         try {
           log.info("🧮 Running conviction scoring for PAN: {}", investorPan);
           convictionScoringService.calculateAndSaveFinalScores(investorPan);
         } catch (Exception e) {
           log.error("❌ Conviction scoring failed for PAN {}: {}", investorPan, e.getMessage());
         }
       }
       
       // Step 3: Evict cache so next dashboard load is fresh
       cacheManager.getCacheNames().forEach(name -> {
         Cache c = cacheManager.getCache(name);
         if (c != null) c.clear();
       });
       log.info("✅ Full sync complete for {} investors.", pansToScore.size());
     }).start();
     
     return ResponseEntity.ok("Full sync started for " + 
       (pan != null ? pan : "all investors") + ".");
   }

3. Add the Cache import at the top:
   import org.springframework.cache.Cache;
   import java.util.List;
   import com.oreki.cas_injector.core.repository.InvestorRepository;
   
4. Add InvestorRepository to the constructor/field injection.
```

---

## Fix 1B — Auto-run scoring after CAS upload for new users

**File:** `CasProcessingService.java` (wherever CAS injection completes)

```
Gemini prompt:

Find the method in CasProcessingService.java that is called after a CAS file
has been successfully parsed and injected. This is the method that creates
the Investor, Folio, Scheme, and Transaction records.

At the END of that method (after all data is committed), add:

  // Trigger initial scoring for this investor
  try {
    log.info("🆕 New investor data loaded for PAN {}. Triggering initial scoring...", pan);
    quantitativeEngineService.runNightlyMathEngine();  // runs global metrics first
    convictionScoringService.calculateAndSaveFinalScores(pan);
    log.info("✅ Initial conviction scoring complete for PAN {}", pan);
  } catch (Exception e) {
    log.warn("⚠️ Initial scoring failed for PAN {} (non-fatal): {}", pan, e.getMessage());
    // Non-fatal — user can manually trigger from Data tab
  }

Inject both services into CasProcessingService:
  private final QuantitativeEngineService quantitativeEngineService;
  private final ConvictionScoringService convictionScoringService;

Add @Async on the scoring call so CAS upload doesn't time out:
  @Async("mathEngineExecutor")
  private void runInitialScoringAsync(String pan) {
    quantitativeEngineService.runNightlyMathEngine();
    convictionScoringService.calculateAndSaveFinalScores(pan);
  }
  
Call it as: runInitialScoringAsync(pan);
```

---

## Fix 1C — Strip leading zeros in HistoricalDataController

**File:** `HistoricalDataController.java`

```
Gemini prompt:

In HistoricalDataController.java, in the getFundHistory() method, strip leading
zeros from amfiCode before the database query:

  String cleanAmfiCode = amfiCode.trim().replaceFirst("^0+(?!$)", "");
  List<HistoricalNav> fundHistory = navRepo.findByAmfiCodeOrderByNavDateDesc(cleanAmfiCode);
  
  String cleanBenchmark = benchmark.trim().toUpperCase();
  List<IndexFundamentals> benchmarkHistory = indexRepo.findByIndexNameOrderByDateDesc(cleanBenchmark);

Also: the current endpoint returns ALL 252 rows of fund history in DESC order.
For the normalized chart in FundDetailView.tsx, the frontend reverses them.
But 252 days = 1 year. Users who joined 3 years ago will only see 1 year.
Increase the limit to 756 rows (3 years):

  if (fundHistory.size() > 756) fundHistory = fundHistory.subList(0, 756);
  if (benchmarkHistory.size() > 756) benchmarkHistory = benchmarkHistory.subList(0, 756);
```

---

## Fix 1D — Add a "Run My Scoring" button for the user in the Data tab

**File:** `CasUploadView.tsx`

```
Gemini prompt:

In CasUploadView.tsx, add a "Recalculate Scores" button below the Force Sync section.

When clicked, it calls:
  POST /api/admin/force-sync?pan={currentPan}

Show a spinner while running. Show "✓ Scoring updated" on success.
Add this below the existing "Force Sync" button if one exists, or as a standalone section:

<section className="bg-surface/40 border border-white/5 p-6 rounded-2xl space-y-4">
  <h3 className="text-[10px] font-black uppercase tracking-widest text-muted">
    Conviction Engine
  </h3>
  <p className="text-xs text-secondary">
    Recalculates your personal conviction scores using your tax lots and performance data.
    Run this after uploading a new CAS file or if scores appear as 0.
  </p>
  <button
    onClick={handleRescore}
    disabled={rescoring}
    className="px-4 py-2 bg-accent/10 border border-accent/20 rounded-xl text-accent text-xs font-black uppercase tracking-widest hover:bg-accent/20 transition-all disabled:opacity-40"
  >
    {rescoring ? 'Calculating...' : 'Recalculate My Scores'}
  </button>
  {rescoreStatus && (
    <p className={`text-xs ${rescoreStatus.includes('✓') ? 'text-buy' : 'text-exit'}`}>
      {rescoreStatus}
    </p>
  )}
</section>

const [rescoring, setRescoring] = useState(false);
const [rescoreStatus, setRescoreStatus] = useState('');

const handleRescore = async () => {
  setRescoring(true);
  setRescoreStatus('');
  try {
    await fetch(`/api/admin/force-sync?pan=${pan}`, {
      method: 'POST',
      headers: { 'X-API-KEY': 'dev-secret-key' }
    });
    setRescoreStatus('✓ Scores updated. Refresh the dashboard to see results.');
  } catch {
    setRescoreStatus('❌ Scoring failed. Check server logs.');
  } finally {
    setRescoring(false);
  }
};
```

---

## Fix 1E — Historical data missing for most funds

**Root cause:** `fund_history` is only populated when:
1. User uploads a CAS file (which adds current NAV)
2. Admin triggers "Full History Refresh" via `/admin/trigger-historical-backfill`

For the "Contextual Positioning" chart to work, `fund_history` needs 252+ rows per fund.
Most users have never run the backfill. The chart shows "Insufficient data points" because
`navRepo.findByAmfiCodeOrderByNavDateDesc(amfiCode)` returns < 2 results.

**Fix — Show a clear actionable message instead of the chart placeholder:**

**File:** `FundDetailView.tsx`

```
Gemini prompt:

In FundDetailView.tsx, in the normalizedHistory useMemo, the check is:
  if (!history || !history.fund || history.fund.length < 2) return [];

Change this to also provide context:
  const hasData = history && history.fund && history.fund.length >= 2;
  const needsBackfill = history && history.fund && history.fund.length < 2;

In the chart rendering section, replace the current loading/empty state with:

  {loading ? (
    <div className="h-full flex items-center justify-center">
      <div className="w-6 h-6 border-2 border-accent border-t-transparent rounded-full animate-spin" />
    </div>
  ) : needsBackfill ? (
    <div className="h-full flex flex-col items-center justify-center gap-3 p-8 text-center">
      <div className="w-10 h-10 bg-amber-500/10 border border-amber-500/20 rounded-2xl flex items-center justify-center">
        <Activity size={18} className="text-amber-400" />
      </div>
      <p className="text-amber-400 text-xs font-black uppercase tracking-widest">
        Historical data not yet loaded
      </p>
      <p className="text-muted text-[11px] max-w-xs leading-relaxed">
        This chart needs historical NAV data. Go to the{' '}
        <span className="text-accent">Data tab</span> and click{' '}
        <span className="text-accent">"Full History Refresh"</span> to load
        3 years of NAV data for all your funds. This takes 2-5 minutes.
      </p>
    </div>
  ) : normalizedHistory.length > 0 ? (
    <ResponsiveLine ... />  {/* existing chart */}
  ) : null}
```

---

## Fix 1F — `updateConvictionBreakdown` SQL doesn't use sanitized AMFI code

**File:** `ConvictionMetricsRepository.java`

```
Gemini prompt:

In ConvictionMetricsRepository.java, find the updateConvictionBreakdown method.
The current WHERE clause is:
  WHERE amfi_code = ?
  
Change it to strip leading zeros on both sides:
  WHERE LTRIM(amfi_code, '0') = LTRIM(?, '0')
  AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)

This ensures the update works regardless of whether the caller passed '119551' or '0119551'.
```

---

## Part 2 — What You Can Build With The Data You Have

You have more data than you're using. Here's the honest map of what's possible
vs what's worth building.

---

### 2.1 — Goal Projector (HIGH VALUE, LOW EFFORT)

**Data available:** XIRR (computed), current value, total invested

You can project "at your current XIRR, how long to reach ₹X?" purely with
compound interest math. No new backend needed.

**File:** Add to `PerformanceView.tsx`

```
Gemini prompt:

In PerformanceView.tsx, add a Goal Projector section below the period returns table.

State:
  const [goalAmount, setGoalAmount] = useState(10000000); // ₹1Cr default
  const [monthlyAddition, setMonthlyAddition] = useState(0);

Computation (pure frontend, no API):
  const xirr = perf?.xirr ?? 0; // annualised
  const currentValue = portfolioData.currentValueAmount ?? 0;
  
  // Future Value with monthly contributions
  // FV = PV * (1+r)^n + PMT * ((1+r)^n - 1) / r
  // Solve for n: binary search
  const yearsToGoal = useMemo(() => {
    if (xirr <= 0 || currentValue <= 0) return null;
    const monthlyRate = Math.pow(1 + xirr / 100, 1/12) - 1;
    let n = 0;
    let fv = currentValue;
    while (fv < goalAmount && n < 600) { // max 50 years
      fv = fv * (1 + monthlyRate) + monthlyAddition;
      n++;
    }
    return n <= 600 ? n / 12 : null; // convert months to years
  }, [xirr, currentValue, goalAmount, monthlyAddition]);

Show:
  - Slider for goal amount (₹25L to ₹10Cr)
  - Input for monthly SIP addition
  - "At your {xirr}% XIRR, you'll reach {formatCurrency(goalAmount)} in {X} years"
  - A simple bar showing progress (current/goal)

This is the single most emotionally engaging feature you can add with zero backend work.
```

---

### 2.2 — Monthly P&L Calendar (MEDIUM VALUE, MEDIUM EFFORT)

**Data available:** `portfolio_snapshot` (daily value + invested)

From snapshot deltas, compute month-over-month value change and show as a
calendar heatmap (green = portfolio grew, red = declined).

**Gemini prompt for backend:**
```
In PortfolioFullService.java, add a new method:

public Map<String, Double> getMonthlyReturns(String pan) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
        SELECT 
            TO_CHAR(snapshot_date, 'YYYY-MM') as month,
            LAST_VALUE(total_value) OVER (
                PARTITION BY TO_CHAR(snapshot_date, 'YYYY-MM') 
                ORDER BY snapshot_date 
                ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
            ) as month_end_value,
            FIRST_VALUE(total_value) OVER (
                PARTITION BY TO_CHAR(snapshot_date, 'YYYY-MM') 
                ORDER BY snapshot_date 
                ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
            ) as month_start_value
        FROM portfolio_snapshot
        WHERE pan = ?
        ORDER BY snapshot_date
        """, pan);
    
    return rows.stream()
        .collect(Collectors.toMap(
            r -> (String) r.get("month"),
            r -> {
                double end = ((Number) r.get("month_end_value")).doubleValue();
                double start = ((Number) r.get("month_start_value")).doubleValue();
                return start > 0 ? (end / start - 1) * 100 : 0.0;
            },
            (a, b) -> b // keep last
        ));
}

Add endpoint: GET /dashboard/monthly-returns/{pan}
```

---

### 2.3 — Rebalancing Benefit Tracker (HIGH VALUE, MEDIUM EFFORT)

**Data available:** Tax lots (buy date, cost, units), all transactions

Track the "excess return" from rebalancing vs a pure buy-and-hold strategy.
Show: "Your rebalancing added ₹X in extra wealth."

**Backend computation:**
```
Gemini prompt:

In PortfolioFullService.java, add getRebalancingBenefit(pan):

The algorithm:
1. Fetch all transactions ordered by date
2. Simulate a "never rebalance" portfolio — only buys, no sells
3. Compare current value of that vs actual current value
4. Difference = rebalancing benefit (can be negative too, which is honest)

Simplified version:
  - Total invested (actual) vs total invested (never-rebalance)
  - These are the same (you put in the same rupees either way)
  - Realized gains from sales = rebalancing harvest captured
  - This is totalRealizedGain in DashboardSummaryDTO

Actually, realized gains IS the rebalancing benefit quantification.
Expose it as: "You've crystallized ₹{totalRealizedGain} through strategic rebalancing."

For a cleaner analysis:
  double rebalancingGain = portfolioData.totalRealizedGain;
  double currentUnrealized = portfolioData.totalUnrealizedGain;
  double totalWealth = rebalancingGain + currentUnrealized;
  double rebalancingShare = totalWealth > 0 ? rebalancingGain / totalWealth : 0;
  
Show this on the Performance tab as:
"Of your total ₹{totalWealth} in gains, ₹{rebalancingGain} ({X}%) came from harvesting
overheated positions and redeploying into undervalued ones."
```

---

### 2.4 — Volatility-Adjusted Return Display (LOW EFFORT, HIGH INSIGHT)

**Data available:** CVaR and Sortino ratio in `fund_conviction_metrics`

Show a simple "Risk-Adjusted Ranking" table: which funds give you the best
return per unit of risk taken?

```
Gemini prompt:

In FundsListView.tsx, add a new sort option "Risk-Adj" to the sort buttons.

When sorted by Risk-Adj, order by:
  (parseFloat(fund.xirr) / Math.abs(fund.maxDrawdown || 1))

Display as a small "Risk-Adj Return" label on the card:
  const riskAdjReturn = maxDrawdown !== 0 
    ? (parseFloat(xirr) / Math.abs(maxDrawdown)).toFixed(2) 
    : 'N/A';
  
  <MiniStat label="Risk-Adj" value={riskAdjReturn} />
  
With tooltip: "XIRR divided by maximum drawdown. Higher = more return per unit of risk taken.
A fund with 20% XIRR and 40% drawdown scores 0.5. A fund with 15% XIRR and 15% drawdown
scores 1.0 — the second fund is actually better quality."
```

---

### 2.5 — SIP Efficiency Report (NEW, MEDIUM EFFORT)

**Data available:** Transactions (buy dates, amounts), current NAV per fund

For each SIP-invested fund, compute: "If you had deployed the same total amount as
a one-time lumpsum on Day 1, what would you have today?" vs actual DCA result.

This directly answers "is SIP better than lumpsum for me?"

```
Gemini prompt:

Add this to PerformanceView.tsx as a collapsible section "SIP vs Lumpsum Analysis".

For each fund, compute client-side:
  const sipBuyTransactions = fund.transactions?.filter(t => t.type === 'BUY') || [];
  const totalDeployed = sipBuyTransactions.reduce((sum, t) => sum + t.amount, 0);
  const firstBuyDate = sipBuyTransactions[0]?.date;
  const firstBuyNav = sipBuyTransactions[0]?.nav;
  
  // If deployed all on day 1:
  const lumpsumUnits = totalDeployed / firstBuyNav;
  const lumpsumCurrentValue = lumpsumUnits * currentNav;
  
  // Actual SIP result:
  const actualCurrentValue = fund.currentValue;
  
  const sipEdge = actualCurrentValue - lumpsumCurrentValue;
  const sipEdgePct = (sipEdge / lumpsumCurrentValue) * 100;

Show per-fund: "SIP gave you ₹X more (or less) than a single lumpsum on {date}"

Note: This requires transaction-level data on the fund object. 
You may need to add transactions to SchemePerformanceDTO or fetch separately.
```

---

### 2.6 — Correlation Matrix from HRP (ALREADY COMPUTED, JUST DISPLAY IT)

**Data available:** HRP service already computes `corrMatrix` and `sortedAmfiCodes`

The `HrpResult` record has the full correlation matrix but it's discarded after
weight computation. Return it to the frontend and show as a heatmap.

```
Gemini prompt:

1. In PortfolioFullService or DashboardController, add:
   GET /dashboard/correlation/{pan}
   
   Call hrpService.computeHrpWeights(heldAmfiCodes) and return:
   {
     labels: List<String>,     // fund short names
     matrix: double[][]        // correlation matrix
   }

2. In PortfolioView.tsx, add a "Correlation Matrix" section:
   Use @nivo/heatmap (ResponsiveHeatMap) to render it.
   
   Color scale: -1 = green (negative correlation = good diversification)
                 0 = gray (no correlation)
                +1 = red (perfect correlation = no diversification benefit)
   
   Title: "How correlated are your funds?"
   Sub-text: "Red = they move together (overlapping exposure).
              Green = they move opposite (genuine diversification)."
   
   This makes the HRP output visible and understandable to the user.
```

---

## Part 3 — What NOT to add (with clear reasoning)

| Idea | Verdict | Why |
|---|---|---|
| **Real-time price alerts** | Skip | Mutual funds have 1 NAV per day. There's nothing to alert on in real-time. |
| **WhatsApp/Email notifications** | Skip | For 1 user, a manual "Recalculate" button in the UI is sufficient. Add if multi-user. |
| **AI/LLM chat assistant** | Consider only after all scores are working | The `ReasoningMetadata` already explains every signal in plain English. An LLM chat adds value when the existing explanations are trustworthy — they aren't yet because scores show 0. |
| **Portfolio optimization (ML)** | Skip | You already have HRP + Kelly + OU + HMM. That's research-grade. Adding more models without fixing existing data quality is counter-productive. |
| **Stock / crypto integration** | Skip | Completely different data pipeline. Different tax rules. Different custody. That's a second product. |

---

## Part 4 — Verification Checklist

```
After all fixes, run these checks:

1. CONVICTION SCORES:
   Call POST /api/admin/force-sync?pan={your PAN} from the Data tab.
   Then reload the dashboard and open any fund detail.
   EXPECTED: YIELD, RISK, VALUE, PAIN, FRICTION all show non-zero values.
   FAILURE CAUSE if still 0: Check server logs for
   "⚠️ No metrics found for scoring!" — means nightly engine hasn't run yet.
   Fix: Run force-sync WITHOUT a pan param first to run the global engine.

2. HISTORICAL CHART:
   After running a Full History Refresh, open a fund detail.
   EXPECTED: "Contextual Positioning" shows a line chart.
   FAILURE CAUSE if still empty: Check /api/history/fund/{amfiCode} in browser.
   If it returns {"fund":[]}, the AMFI code has a leading zero mismatch.
   Fix: Check DB: SELECT COUNT(*) FROM fund_history WHERE amfi_code = '{code}';
   Then try: SELECT COUNT(*) FROM fund_history WHERE LTRIM(amfi_code,'0') = '{code_without_zeros}';

3. LOGIN FLOW:
   Log out and log in with a different PAN.
   EXPECTED: Dashboard loads with data for that investor.
   Check: Does that investor have conviction scores? If 0, run force-sync for their PAN.

4. PERFORMANCE TAB:
   EXPECTED: Shows history chart after first nightly snapshot runs.
   If portfolio_snapshot is empty: MetricsSchedulerService.snapshotAllPortfolios()
   runs at 7:30pm on weekdays. Or call /admin/force-sync which includes snapshot logic.
   Alternatively, manually insert a test row:
   INSERT INTO portfolio_snapshot (pan, snapshot_date, total_value, total_invested)
   VALUES ('YOURPAN', CURRENT_DATE, 83039, 85000);

5. amfi code consistency check (run in psql):
   SELECT s.amfi_code, f.amfi_code
   FROM scheme s
   LEFT JOIN fund_history f ON LTRIM(s.amfi_code,'0') = LTRIM(f.amfi_code,'0')
   WHERE f.amfi_code IS NULL AND s.amfi_code IS NOT NULL
   LIMIT 10;
   This shows schemes with NO matching history rows — they need backfill.
```

---

## Summary Priority

| # | Fix | Effort | Impact |
|---|-----|--------|--------|
| 1A | Remove hardcoded PAN from force-sync | 10 min | **CRITICAL** — scores are 0 for all new users |
| 1B | Auto-score after CAS upload | 20 min | **HIGH** — fixes first-time experience |
| 1C | Strip leading zeros in HistoricalDataController | 5 min | **HIGH** — fixes chart for many funds |
| 1D | "Recalculate Scores" button in Data tab | 15 min | **HIGH** — user self-service fix |
| 1E | Better empty state message for chart | 10 min | **MEDIUM** — tells user exactly what to do |
| 1F | AMFI code sanitization in updateConvictionBreakdown | 5 min | **MEDIUM** — prevents silent update failures |
| 2.1 | Goal Projector | 30 min | **HIGH VALUE** — emotionally engaging, zero backend |
| 2.4 | Risk-Adjusted Return sort | 20 min | **MEDIUM** — uses existing data |
| 2.6 | Correlation heatmap | 45 min | **MEDIUM** — already computed, just display |
