# Gemini CLI Prompt — Bug Fixes + Design Improvements

Read every section before changing any file. Each bug is diagnosed with root cause and exact fix.

---

## Bug 1 — Portfolio CVaR shows 0.00%

**Root cause:** `SchemePerformanceDTO` has no `cvar5` field. `PortfolioFullService.getFullPortfolio()` merges metrics from `fund_conviction_metrics` but never includes `cvar_5`. `PortfolioView.tsx` reads `s.cvar5` which is always `undefined`.

**Fix — `SchemePerformanceDTO.java`:** Add the field:
```java
private double cvar5;
```

**Fix — `PortfolioFullService.java`:** In the metrics SQL and merge block, add `cvar_5`:
```java
String metricsSql = "SELECT amfi_code, conviction_score, sortino_ratio, max_drawdown, " +
                   "cvar_5, nav_percentile_3yr, drawdown_from_ath, return_z_score " +
                   "FROM fund_conviction_metrics " +
                   "WHERE calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)";

// In the merge loop, add:
scheme.setCvar5(getSafeDouble(fundMetrics.get("cvar_5")));
```

---

## Bug 2 — Rebalance chart is empty (most critical)

**Root cause:** The new `PortfolioFullService` no longer calls the signal merge that sets `plannedPercentage` and `allocationPercentage` on each `SchemePerformanceDTO`. The old code used `orchestrator.generateDailySignals()` and merged `TacticalSignal` by amfiCode. The new code uses `computeSipPlan()`, `computeOpportunisticSignals()`, and `computeExitQueue()` but never maps those back onto the scheme breakdown. So `s.plannedPercentage = 0` and `s.allocationPercentage = 0` for all funds, and `RebalanceView` filter returns nothing.

**Fix — `PortfolioFullService.java`:** After building the `tacticalPayload`, also enrich the scheme breakdown with signal data. Add this block:

```java
// Build a lookup map from SIP plan: isin → SipLineItem
Map<String, SipLineItem> sipByIsin = sipPlan.stream()
    .collect(Collectors.toMap(SipLineItem::isin, s -> s, (a, b) -> a));

// Also build a map from opportunistic + exit signals by amfiCode
Map<String, TacticalSignal> signalByAmfi = new java.util.HashMap<>();
opportunistic.forEach(s -> signalByAmfi.put(s.amfiCode(), s));
exitQueue.forEach(s -> signalByAmfi.put(s.amfiCode(), s));

// Compute total portfolio value for percentage calculation
double totalPortfolioValue = summary.getSchemeBreakdown().stream()
    .mapToDouble(s -> s.getCurrentValue() != null ? s.getCurrentValue().doubleValue() : 0.0)
    .sum();

summary.getSchemeBreakdown().forEach(scheme -> {
    String code = scheme.getAmfiCode();
    String isin = scheme.getIsin();

    // 1. Set actual allocation percentage
    double actualPct = totalPortfolioValue > 0 
        ? (scheme.getCurrentValue() != null ? scheme.getCurrentValue().doubleValue() : 0.0) 
          / totalPortfolioValue * 100.0
        : 0.0;
    scheme.setAllocationPercentage(actualPct);

    // 2. Set planned percentage and action from SIP plan (primary source)
    SipLineItem sipItem = sipByIsin.get(isin);
    if (sipItem != null) {
        // plannedPercentage = targetPortfolioPct from Google Sheet strategy
        // This needs to come from StrategyTarget. For now use sipPct as proxy.
        // Ideally fetch StrategyTarget map separately.
        scheme.setPlannedPercentage(sipItem.sipPct());
        scheme.setAction("HOLD");
        scheme.setSignalType(sipItem.mode());
        scheme.setJustifications(List.of(sipItem.note()));
    }

    // 3. Override with tactical signal if present
    TacticalSignal signal = signalByAmfi.get(code);
    if (signal != null) {
        scheme.setAction(signal.action());
        scheme.setSignalAmount(new java.math.BigDecimal(signal.amount().replace(",", "")));
        scheme.setJustifications(signal.justifications());
        scheme.setLastBuyDate(signal.lastBuyDate());
    }
});
```

**However**, the `sipPct` from `SipLineItem` is the SIP allocation percentage (e.g. 33%), not the target portfolio percentage (e.g. 29%). These are different. The cleanest fix is to expose `targetPortfolioPct` from `StrategyTarget` through `SipLineItem`. 

**Fix — `SipLineItem.java`:** Add `targetPortfolioPct` field:
```java
public record SipLineItem(
    String schemeName,
    String isin,
    String amfiCode,
    double amount,
    double sipPct,
    double targetPortfolioPct,  // ADD THIS
    String mode,
    String deployFlag,
    String note
) {}
```

**Fix — `PortfolioOrchestrator.computeSipPlan()`:** Set `targetPortfolioPct` from the strategy target:
```java
return new SipLineItem(t.schemeName(), t.isin(), amfiCode, amount, t.sipPct(),
    t.targetPortfolioPct(), t.status(), flag, note);
```

Then in `PortfolioFullService`, use `sipItem.targetPortfolioPct()` instead of `sipItem.sipPct()` when setting `plannedPercentage`.

---

## Bug 3 — Unrealised gain % bar renders full when value is negative

**Root cause:** `width: Math.min(100, (unrealizedGain / totalInvestedAmount) * 100)%` — when unrealised gain is negative, this produces a negative CSS width which browsers render as 100% or clamp in unexpected ways.

**Fix — `PortfolioView.tsx`:** Change the unrealised gain gauge:

```tsx
// Replace the unrealised gain card entirely:
const unrealizedPct = portfolioData.totalInvestedAmount 
  ? (portfolioData.totalUnrealizedGain / portfolioData.totalInvestedAmount) * 100 
  : 0;
const unrealizedAbsPct = Math.min(100, Math.abs(unrealizedPct));
const isUnrealizedNegative = unrealizedPct < 0;

// In JSX:
<div className="bg-surface border border-white/5 p-6 rounded-xl space-y-4">
  <MetricWithTooltip 
    label="Unrealised Gain %" 
    value={`${unrealizedPct.toFixed(1)}%`}
    valueClass={isUnrealizedNegative ? "text-exit" : "text-buy"}
    tooltip="Absolute return on currently active capital."
  />
  <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
    <div 
      className={`h-full transition-all duration-500 ${isUnrealizedNegative ? 'bg-exit' : 'bg-buy'}`}
      style={{ width: `${unrealizedAbsPct}%` }} 
    />
  </div>
</div>
```

---

## Bug 4 — Tax Efficiency shows 0% when no sales have been made

**Root cause:** Tax efficiency = `realizedLTCG / (realizedLTCG + realizedSTCG)`. If nothing has been sold yet (both are 0), this is 0/1 = 0. The metric is meaningless until first sale.

**Fix — `PortfolioView.tsx`:** Change the tax efficiency gauge to show unrealised LTCG ratio instead, which is meaningful on any portfolio:

```tsx
// Replace the taxEfficiency calculation:
// Compute what % of unrealised gain sits in LTCG lots (from AggregatedHolding data)
// The schemeBreakdown has ltcgValue/stcgValue if SchemePerformanceDTO exposes it.
// If not available, fall back to a time-based proxy: % of portfolio older than 1yr.

// Quick fix using available data: ratio of conviction score weighted by lot age
// Better fix: add ltcgUnrealizedGain to SchemePerformanceDTO (see backend fix below)

// For now, show "Not applicable" when 0 realized gains exist:
const hasRealizedGains = totalLTCG > 0 || totalSTCG > 0;
const taxEfficiencyValue = hasRealizedGains 
  ? `${(taxEfficiency * 100).toFixed(1)}%` 
  : 'No sales yet';
const taxEfficiencyWidth = hasRealizedGains ? taxEfficiency * 100 : 0;
```

**Better backend fix — add to `SchemePerformanceDTO.java`:**
```java
private double ltcgUnrealizedGain;   // sum of gains on lots > 365 days
private double stcgUnrealizedGain;   // sum of gains on lots <= 365 days
```

In `DashboardService`, compute these from `allLots` (already available in the loop):
```java
double ltcgUnrealized = allLots.stream()
    .filter(lot -> "OPEN".equalsIgnoreCase(lot.getStatus()))
    .filter(lot -> ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now()) > 365)
    .mapToDouble(lot -> {
        double currentVal = lot.getRemainingUnits().doubleValue() * currentNav.doubleValue();
        double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
        return Math.max(0, currentVal - cost);
    }).sum();

double stcgUnrealized = allLots.stream()
    .filter(lot -> "OPEN".equalsIgnoreCase(lot.getStatus()))
    .filter(lot -> ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now()) <= 365)
    .mapToDouble(lot -> {
        double currentVal = lot.getRemainingUnits().doubleValue() * currentNav.doubleValue();
        double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
        return Math.max(0, currentVal - cost);
    }).sum();
```

Then in `PortfolioView.tsx`, use:
```tsx
const totalUnrealizedLTCG = breakdown.reduce((a: number, s: any) => a + (s.ltcgUnrealizedGain || 0), 0);
const totalUnrealizedGainPos = breakdown.reduce((a: number, s: any) => 
  a + Math.max(0, (s.unrealizedGain || 0)), 0);
const taxEfficiency = totalUnrealizedGainPos > 0 
  ? totalUnrealizedLTCG / totalUnrealizedGainPos 
  : 0;
```

This shows: "Of all your paper profits, X% are in long-term holdings (tax-efficient)." Meaningful immediately.

---

## Bug 5 — Tax page layout dead space

**Root cause:** `lg:grid-cols-2` forces equal halves. When STCG list is empty (shows a "no items" placeholder with `min-h-[200px]`) and TLH has 3+ items stacking vertically, the right column grows much taller than the left, creating visible dead space.

**Fix — `TaxView.tsx`:** Change the layout to stack sections vertically when STCG is empty, and side-by-side only when both have content:

```tsx
// Replace the grid wrapper:
{stcgFunds.length > 0 && tlhOps.length > 0 ? (
  // Both have content: side by side
  <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
    {/* STCG section */}
    {stcgSection}
    {/* TLH section */}
    {tlhSection}
  </div>
) : (
  // Only one has content: full width
  <div className="space-y-8">
    {stcgFunds.length > 0 && stcgSection}
    {tlhOps.length > 0 && tlhSection}
    {stcgFunds.length === 0 && tlhOps.length === 0 && (
      <div className="bg-surface border border-white/5 rounded-xl p-8 text-center space-y-3">
        <p className="text-muted text-sm">No tax actions needed today.</p>
        <p className="text-muted text-[11px]">System monitors FIFO lots for -5% drops and STCG exposure daily.</p>
      </div>
    )}
  </div>
)}
```

Extract `stcgSection` and `tlhSection` as JSX variables before the return.

Also: TLH cards need a `proxySchemeRecommendation` fallback. When it's empty/null (no proxy in `tlh_proxy.json`), currently shows nothing next to "Buy". Fix:
```tsx
<p className="text-sm text-buy truncate max-w-[200px]">
  {op.proxySchemeRecommendation || 'Search for similar category fund'}
</p>
```

---

## Bug 6 — SIP table in Today tab may be empty

**Root cause:** Depends on whether `DashboardSummaryDTO` has the `tacticalPayload` field. Verify the field exists:

**`DashboardSummaryDTO.java`** must have:
```java
import com.oreki.cas_injector.dashboard.dto.UnifiedTacticalPayload;

private UnifiedTacticalPayload tacticalPayload;
```

If this field is missing, `portfolioData.tacticalPayload` is `undefined` in the frontend, the `|| { sipPlan: [], ... }` fallback kicks in, and all three sections render empty with no error.

If `tacticalPayload` exists but `sipPlan` is empty, the issue is in `PortfolioOrchestrator.computeSipPlan()`. Add a log line there and check the backend logs for the pan.

**Frontend defensive fix in `TodayBriefView.tsx`:** Add a loading/empty state for the SIP table:
```tsx
{payload.sipPlan.length === 0 ? (
  <tr>
    <td colSpan={4} className="px-6 py-8 text-center text-muted text-xs italic">
      SIP plan not loaded. Check that Google Sheet is accessible and contains funds with sip% &gt; 0.
    </td>
  </tr>
) : (
  payload.sipPlan.map(/* existing map */)
)}
```

---

## Design improvements

### 1 — Portfolio: add mode badges to bucket bars

Currently bucket bars just show AGGRESSIVE_GROWTH etc. Add a count badge showing how many of your funds fall in each bucket, and a small indicator of whether the bucket is over/under its target:

```tsx
// Add to bucketData computation:
const bucketData = Object.entries(bucketMap).map(([name, value]) => {
  const fundsInBucket = breakdown.filter((s: any) => s.bucket === name);
  // Target from strategy: sum of targetPortfolioPct for funds in this bucket
  // (requires targetPortfolioPct on SchemePerformanceDTO)
  return {
    name: name.replace(/_/g, ' '),
    value: (value / totalValue) * 100,
    color: BUCKET_COLORS[name] || "#94a3b8",
    count: fundsInBucket.length,
  };
});

// In JSX, add the count:
<span className="text-[9px] text-muted tabular-nums ml-2">{b.count} funds</span>
```

### 2 — Rebalance: show mode column in the table

The drift table currently has Fund Name / Target % / Actual % / Drift / Action. Add a **Mode** column showing core/strategy/satellite/rebalancer/dropped as a colored pill. This makes the table much more scannable:

```tsx
// Add column header:
<th className="px-6 py-4 font-medium">Mode</th>

// Add cell (use s.signalType or a new mode field from SchemePerformanceDTO):
<td className="px-6 py-4">
  <span className={`text-[9px] font-bold uppercase tracking-widest px-2 py-0.5 rounded ${
    s.signalType === 'core' ? 'bg-teal-500/10 text-teal-400' :
    s.signalType === 'strategy' ? 'bg-purple-500/10 text-purple-400' :
    s.signalType === 'satellite' ? 'bg-amber-500/10 text-amber-400' :
    s.signalType === 'rebalancer' ? 'bg-blue-500/10 text-blue-400' :
    s.signalType === 'dropped' ? 'bg-zinc-500/10 text-zinc-400' :
    'bg-white/5 text-muted'
  }`}>
    {s.signalType || 'unknown'}
  </span>
</td>
```

### 3 — Portfolio vital signs: use a 2×2 grid with better metrics

Replace the current four gauges (CVaR, Tax Efficiency, Avg Conviction, Unrealised Gain %) with a cleaner layout. The progress bars under each metric currently all look the same — add directional context:

- CVaR: the bar should fill from right (worst) to left (best). Use `flex-row-reverse` and label the ends "Safe" / "Risky".
- Average Conviction: show distribution instead of a single average — `X funds high / Y funds medium / Z funds low`. A stacked mini-bar is more useful than an average number.

```tsx
// Replace avg conviction gauge with distribution:
const highConv = breakdown.filter((s: any) => s.convictionScore >= 65).length;
const midConv = breakdown.filter((s: any) => s.convictionScore >= 45 && s.convictionScore < 65).length;
const lowConv = breakdown.filter((s: any) => s.convictionScore < 45).length;
const total = breakdown.length || 1;

// Stacked bar:
<div className="h-2 w-full bg-white/5 rounded-full overflow-hidden flex">
  <div style={{ width: `${(highConv/total)*100}%` }} className="h-full bg-buy" />
  <div style={{ width: `${(midConv/total)*100}%` }} className="h-full bg-warning" />
  <div style={{ width: `${(lowConv/total)*100}%` }} className="h-full bg-exit" />
</div>
<div className="flex gap-4 mt-2">
  <span className="text-[10px] text-buy">{highConv} high</span>
  <span className="text-[10px] text-warning">{midConv} mid</span>
  <span className="text-[10px] text-exit">{lowConv} low</span>
</div>
```

### 4 — Today tab: add a portfolio health banner at the top

Before the three sections, add a single-line status banner that summarises the portfolio state:

```tsx
// Compute health summary:
const exitCount = payload.exitQueue?.length || 0;
const sipTotal = payload.sipPlan?.reduce((a: number, s: any) => a + s.amount, 0) || 0;
const opCount = payload.opportunisticSignals?.length || 0;

// Banner JSX:
<div className="flex flex-wrap items-center gap-6 px-6 py-4 bg-white/[0.02] border border-white/5 rounded-xl">
  <div className="flex items-center gap-3">
    <div className={`w-2 h-2 rounded-full ${exitCount > 0 ? 'bg-exit' : 'bg-buy'}`} />
    <span className="text-[11px] text-secondary">
      {exitCount > 0 
        ? `${exitCount} dropped funds need clearing` 
        : 'No exit actions pending'}
    </span>
  </div>
  <div className="flex items-center gap-3">
    <div className="w-2 h-2 rounded-full bg-accent" />
    <span className="text-[11px] text-secondary">
      SIP this month: <CurrencyValue isPrivate={isPrivate} value={sipTotal} className="text-primary font-medium" />
    </span>
  </div>
  {opCount > 0 && (
    <div className="flex items-center gap-3">
      <div className="w-2 h-2 rounded-full bg-warning" />
      <span className="text-[11px] text-secondary">{opCount} opportunistic signals active</span>
    </div>
  )}
</div>
```

### 5 — Fund detail: conviction scores are approximated, not real

In `FundDetailView.tsx`, the five component scores are approximated:
```tsx
{ label: 'Yield', score: (fund.convictionScore || 0) * 0.8 }  // Wrong — just scales total
{ label: 'Risk', score: (fund.sortinoRatio || 0) * 40 }       // Wrong — arbitrary multiplier
```

These are not the actual component scores from `ConvictionScoringService`. The real sub-scores are never stored or returned — only the final `conviction_score` is persisted.

**Backend fix — `fund_conviction_metrics` table:** Add columns for sub-scores:
```sql
ALTER TABLE fund_conviction_metrics
  ADD COLUMN IF NOT EXISTS yield_score DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS risk_score DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS value_score DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS pain_score DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS friction_score DOUBLE PRECISION;
```

In `ConvictionScoringService.calculateAndSaveFinalScores()`, save them:
```java
convictionMetricsRepository.updateConvictionBreakdown(
    finalScore, yieldScore, riskScore, valueScore, painScore, frictionScore, amfiCode);
```

Add `yieldScore`, `riskScore`, `valueScore`, `painScore`, `frictionScore` to `SchemePerformanceDTO` and populate in `PortfolioFullService`.

Until this backend fix is done, in `FundDetailView.tsx`, label these as "estimated" and show a note:
```tsx
<p className="text-[10px] text-muted italic mt-1">
  Component scores are estimated from available metrics. Enable full breakdown in settings.
</p>
```

---

## Validation checklist

1. Portfolio CVaR shows a non-zero value for funds that have `cvar_5` in `fund_conviction_metrics`.
2. Rebalance chart renders bars for all funds that have `plannedPercentage > 0`.
3. Unrealised gain % bar never overflows at negative values.
4. Tax efficiency shows a meaningful value (LTCG unrealised ratio) even before first sale.
5. Tax page has no dead space — TLH section goes full width when STCG list is empty.
6. SIP table in Today tab shows rows when `tacticalPayload.sipPlan` is populated.
7. TLH cards show "Search for similar category fund" when `proxySchemeRecommendation` is null.
8. Rebalance table includes a Mode column showing fund strategy type.
9. Conviction component bars are clearly labelled as estimated until backend sub-scores are added.
