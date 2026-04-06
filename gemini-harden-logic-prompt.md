# Gemini CLI Prompt — Harden Exit Queue, Debt Tax Logic, Opportunistic Signals

Read every section carefully before touching any file.
Three independent problems. Fix them in order.

---

## Problem 1 — Exit queue shows only 1 fund instead of all dropped funds

### Root cause A: `aggregateLots()` silently zeroes out LTCG/STCG for some funds

`aggregateLots()` calls `CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), category)`.
If this utility returns an unexpected string (e.g. `"DEBT_SLAB"` instead of something containing `"LTCG"` or `"STCG"`), the lot falls through both `if (isLtcg)` and `else` branches — meaning `ltcgVal`, `stcgVal`, `ltcgGains`, `stcgGains` all stay 0. When these are 0, `computeExitQueue()` has no basis for generating an exit signal.

### Root cause B: `computeExitQueue()` gate is too strict

The current logic:
```java
if (exitAmount > 0 || !justs.isEmpty()) {
    exitPlan.add(...)
}
```
Only adds to the exit plan if either an amount was calculated OR justifications were added. But for equity dropped funds where `ltcgValue == 0` AND `stcgValue == 0` (due to Root Cause A), neither condition fires — the fund is silently dropped from the exit plan entirely.

### Root cause C: Debt category matching is fragile

```java
if (h.getAssetCategory().contains("DEBT") || h.getAssetCategory().contains("GILT"))
```
AMFI category strings are like `"DEBT SCHEME - GILT FUND"` or `"DEBT SCHEME - CORPORATE BOND"`. The check for `"GILT"` would match `"GILT FUND"` but not standalone debt funds with names like `"BANKING AND PSU"`. And `assetCategory` from `scheme.getAssetCategory()` may be null — calling `.contains()` on null throws NullPointerException.

### Fix — rewrite `computeExitQueue()` in `PortfolioOrchestrator.java`

Replace the entire method with this hardened version:

```java
public List<TacticalSignal> computeExitQueue(String pan) {
    List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
    
    // Build a case-insensitive lookup of dropped ISINs and names from sheet
    Set<String> droppedIsins = targets.stream()
        .filter(t -> "dropped".equalsIgnoreCase(t.status()))
        .map(t -> t.isin().toUpperCase())
        .collect(Collectors.toSet());
    Set<String> droppedNames = targets.stream()
        .filter(t -> "dropped".equalsIgnoreCase(t.status()))
        .map(t -> t.schemeName().toUpperCase().trim())
        .collect(Collectors.toSet());

    List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
    List<AggregatedHolding> holdings = aggregateLots(allLots);

    // Find realized LTCG so far this FY
    LocalDate fyStart = CommonUtils.getCurrentFyStart();
    String ltcgSql = """
        SELECT COALESCE(SUM(a.realized_gain), 0)
        FROM capital_gain_audit a
        JOIN transaction t ON a.sell_transaction_id = t.id
        JOIN scheme s ON t.scheme_id = s.id
        JOIN folio f ON s.folio_id = f.id
        WHERE a.tax_category LIKE '%LTCG%'
        AND t.transaction_date >= ?
        AND f.investor_pan = ?
        """;
    double realizedLtcg = jdbcTemplate.queryForObject(ltcgSql, Double.class, fyStart, pan);
    double ltcgHeadroom = Math.max(0, 125000 - realizedLtcg);

    List<TacticalSignal> exitPlan = new ArrayList<>();

    for (AggregatedHolding h : holdings) {
        // Check if this holding is marked dropped — match by ISIN first, then name
        boolean isDropped = droppedIsins.contains(h.getIsin() != null ? h.getIsin().toUpperCase() : "")
            || droppedNames.contains(h.getSchemeName().toUpperCase().trim())
            // Also include holdings with no entry in strategy sheet at all (orphaned)
            || targets.stream().noneMatch(t -> 
                t.isin().equalsIgnoreCase(h.getIsin()) || 
                t.schemeName().equalsIgnoreCase(h.getSchemeName()));

        if (!isDropped) continue;
        if (h.getCurrentValue() < 100) continue; // Skip dust positions

        List<String> justs = new ArrayList<>();
        double exitAmount = 0;
        String category = h.getAssetCategory() != null ? h.getAssetCategory().toUpperCase() : "";

        // --- DEBT / GILT / BOND: Post-April 2023, ALL gains are slab-taxed regardless of holding period.
        // Exit immediately — waiting for "LTCG" threshold provides zero benefit.
        boolean isDebt = category.contains("DEBT") || category.contains("GILT")
            || category.contains("BOND") || category.contains("LIQUID")
            || category.contains("BANKING AND PSU") || category.contains("CORPORATE")
            || category.contains("MONEY MARKET");

        if (isDebt) {
            exitAmount = h.getCurrentValue();
            double gain = h.getCurrentValue() - h.getInvestedAmount();
            if (gain > 0) {
                justs.add(String.format(
                    "Priority exit: Debt fund taxed at slab rate (post-Apr 2023 rules). " +
                    "No benefit in waiting. Gain of ₹%,.0f will be taxed at your income bracket.", gain));
            } else {
                justs.add(String.format(
                    "Priority exit: Debt fund at a loss of ₹%,.0f. " +
                    "Exit now to book the loss — it offsets other income at slab rate.", Math.abs(gain)));
            }
        }
        // --- EQUITY: Sequence by tax efficiency
        else {
            // Case 1: Has LTCG-eligible units — harvest within headroom
            if (h.getLtcgValue() > 0) {
                // Use the actual LTCG gains to determine how much headroom to consume
                double ltcgGainInHolding = h.getLtcgAmount();
                if (ltcgGainInHolding > 0 && ltcgHeadroom > 0) {
                    // How many rupees of value can we sell while keeping gains under headroom?
                    double gainRatio = ltcgGainInHolding / h.getLtcgValue(); // gain per rupee of LTCG value
                    double maxSellableUnderHeadroom = gainRatio > 0 ? ltcgHeadroom / gainRatio : h.getLtcgValue();
                    exitAmount = Math.min(h.getLtcgValue(), maxSellableUnderHeadroom);
                    ltcgHeadroom -= Math.min(ltcgGainInHolding, ltcgHeadroom);
                    justs.add(String.format(
                        "Tax-efficient exit: Selling ₹%,.0f of LTCG-eligible units " +
                        "(profit ₹%,.0f) within ₹1.25L annual tax-free limit.", exitAmount, ltcgGainInHolding));
                } else if (ltcgGainInHolding <= 0) {
                    // LTCG lots are at a loss — always exit, no tax
                    exitAmount = h.getLtcgValue();
                    justs.add(String.format(
                        "Exit at no tax: LTCG lots are at a loss of ₹%,.0f. No capital gains tax applies.",
                        Math.abs(ltcgGainInHolding)));
                } else {
                    // ltcgHeadroom = 0 — limit exhausted
                    exitAmount = h.getLtcgValue();
                    justs.add(String.format(
                        "LTCG limit reached for this FY. Exiting ₹%,.0f anyway — " +
                        "excess gains (₹%,.0f) taxed at 12.5%%.",
                        exitAmount, Math.max(0, ltcgGainInHolding - (125000 - realizedLtcg))));
                }
            }

            // Case 2: Only STCG lots — defer if close to threshold, else recommend immediate exit
            if (h.getStcgValue() > 0) {
                int daysToLtcg = h.getDaysToNextLtcg();
                if (daysToLtcg > 0 && daysToLtcg <= 45) {
                    // Very close — wait it out
                    justs.add(String.format(
                        "Deferred: %d days until next lot becomes LTCG-eligible. " +
                        "Waiting saves 20%% STCG tax on ₹%,.0f.", daysToLtcg, h.getStcgValue()));
                } else if (daysToLtcg > 45) {
                    // Too far — if the position is small, just exit and pay the tax
                    if (h.getStcgValue() < 15000) {
                        exitAmount += h.getStcgValue();
                        double stcgTax = Math.max(0, h.getStcgAmount()) * 0.20;
                        justs.add(String.format(
                            "Small position (₹%,.0f): Exiting despite %d days remaining. " +
                            "STCG tax of ₹%,.0f is acceptable on this position size.", 
                            h.getStcgValue(), daysToLtcg, stcgTax));
                    } else {
                        justs.add(String.format(
                            "Deferred: %d days until LTCG. Holding ₹%,.0f to avoid " +
                            "₹%,.0f in STCG tax (20%%).", 
                            daysToLtcg, h.getStcgValue(), Math.max(0, h.getStcgAmount()) * 0.20));
                    }
                } else {
                    // daysToNextLtcg = 0 means no STCG lots, nothing to do here
                }
            }

            // If fund has value but BOTH ltcgValue and stcgValue are 0
            // (aggregateLots silently failed), still show it with a generic signal
            if (exitAmount == 0 && justs.isEmpty() && h.getCurrentValue() > 0) {
                exitAmount = h.getCurrentValue();
                justs.add(String.format(
                    "Exit queued: Fund dropped from strategy. " +
                    "Current value ₹%,.0f. Verify lot details for tax treatment.", h.getCurrentValue()));
            }
        }

        // Always add if there's anything to say about this dropped holding
        if (!justs.isEmpty() || exitAmount > 0) {
            exitPlan.add(new TacticalSignal(
                h.getSchemeName(), amfiCodeFor(h), "EXIT",
                String.format("%.2f", exitAmount),
                0, (h.getCurrentValue() / (holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum() / 100.0)),
                0, "DROPPED", 0, 0, 0, 0, 0, 0,
                LocalDate.now(), justs));
        }
    }

    // Sort: debt funds first (immediate exits), then by exit amount descending
    exitPlan.sort(Comparator
        .comparing((TacticalSignal s) -> {
            AggregatedHolding h = holdings.stream()
                .filter(x -> x.getSchemeName().equalsIgnoreCase(s.schemeName()))
                .findFirst().orElse(null);
            String cat = h != null && h.getAssetCategory() != null ? h.getAssetCategory().toUpperCase() : "";
            return cat.contains("DEBT") || cat.contains("GILT") || cat.contains("BOND") ? 0 : 1;
        })
        .thenComparing(Comparator.comparingDouble(
            (TacticalSignal s) -> Double.parseDouble(s.amount())).reversed())
    );

    log.info("🚀 Exit queue computed: {} funds to exit", exitPlan.size());
    return exitPlan;
}
```

---

## Problem 2 — Debt fund taxation uses wrong tax rates throughout the system

### `TaxSimulatorService.java` — fix the debt tax rate

Current code uses `0.30` as a flat rate for all non-equity. This is wrong:
- Debt funds purchased **after April 1, 2023**: ALL gains taxed at income slab rate (typically 30% for high earners, but the system should not assume this — flag it as slab-rate).
- Debt funds purchased **before April 1, 2023**: older rules applied (3yr LTCG at 20% with indexation), but those lots are now mostly all past the threshold anyway.

Replace the non-equity tax section:

```java
// Replace the else branch in simulateSellOrder():
} else {
    // Post-April 2023: Debt gains are always slab-taxed regardless of holding period.
    // We use 30% as the conservative upper bound — actual rate depends on investor's slab.
    // Flag this in the result so the UI can show the correct message.
    double slabRate = 0.30; // Conservative 30% — mark as slab
    estimatedTax += Math.max(0, stcgProfit) * slabRate;
    // Note: for debt there is no separate LTCG — all profit is stcgProfit in the non-equity path
}
```

Also update `TaxSimulationResult` to carry a `boolean isDebt` flag so the frontend can display "Taxed at your income slab (up to 30%)" instead of a fixed percentage. Add to `TaxSimulationResult.java`:
```java
public record TaxSimulationResult(
    double sellAmount,
    double stcgProfit,
    double ltcgProfit,
    double estimatedTax,
    double taxDragPercentage,
    boolean isTaxLocked,
    boolean isDebt  // NEW: true for non-equity funds
) {}
```

Update `TaxSimulatorService` to set `isDebt = !isEquity` in the return.

### `aggregateLots()` in `PortfolioOrchestrator.java` — fix debt lot classification

The current logic:
```java
boolean isLtcg = taxCat.contains("LTCG");
```

For debt funds, `DETERMINE_TAX_CATEGORY` likely returns `"DEBT_STCG"` or similar — meaning it never contains "LTCG", so all debt lots go to `stcgVal`. This is actually correct post-2023. But the issue is `daysToNextLtcg` for debt funds is meaningless (there is no LTCG threshold for debt anymore). Add:

```java
// After the isLtcg calculation, for debt funds override:
boolean isDebtFund = category.contains("DEBT") || category.contains("GILT") 
    || category.contains("BOND") || category.contains("LIQUID");
if (isDebtFund) {
    // All debt is slab-taxed — put everything in stcgVal, never compute daysToNextLtcg
    stcgVal += lVal;
    stcgGains += Math.max(0, gain);
    // Do NOT update minDaysToLtcg for debt — it doesn't apply
    continue; // Skip the rest of the loop body for this lot
}
```

Place this block immediately after computing `gain` and before the `isLtcg` check.

### `TodayBriefView.tsx` — display debt exit reasoning correctly

In the exit queue table, add a debt-specific badge when the justification contains "slab rate":

```tsx
// In the exit queue table row, replace the existing justification display:
<td className="px-6 py-4">
  <div className="flex items-start gap-2 max-w-xs">
    {s.justifications[0]?.includes('slab') || s.justifications[0]?.includes('Debt') ? (
      <span className="shrink-0 text-[9px] font-bold uppercase tracking-widest px-1.5 py-0.5 rounded bg-warning/10 text-warning border border-warning/20">
        SLAB TAX
      </span>
    ) : (
      <Receipt size={12} className="text-muted mt-0.5 shrink-0" />
    )}
    <p className="text-[11px] text-secondary leading-relaxed">{s.justifications[0]}</p>
  </div>
</td>
```

---

## Problem 3 — Opportunistic signals always empty

### Root cause: Default `navPercentile3yr = 0.5` silently fails all gates

`fetchLiveMetricsMap(pan)` only fetches funds that are currently in `fund_conviction_metrics` joined with `folio` (i.e., funds YOU OWN). Accumulator and rebalancer funds from the Google Sheet that you don't yet own (or haven't owned long enough for the nightly engine to compute metrics) return `defaultMetrics()` which has `navPercentile3yr = 0.5`.

The gates:
- Accumulator: `metrics.navPercentile3yr() < 0.40` → `0.5 < 0.40` → **FALSE, silently skipped**
- Rebalancer deploy: `metrics.navPercentile3yr() < 0.60` → `0.5 < 0.60` → TRUE, but then `arbitrageValue > 10000` may be false if the arbitrage fund isn't loaded in holdings.

Additionally, `weightSignalsByConviction()` distributes `cash` (the lumpsum parameter) among signals. When lumpsum = 0 (the default when `api.ts` calls with no lumpsum), `totalDemand` calculates correctly but the final `weightedAmount` = `(baseAmount * scoreMult / totalDemand) * 0` = **0 for everything**, turning all signals into meaningless ₹0 BUYs.

### Fix — `PortfolioOrchestrator.java`

**Fix 1: Fetch metrics for ALL strategy funds, not just owned ones**

Replace `fetchLiveMetricsMap(pan)` with a broader query that also pulls metrics by ISIN from the strategy sheet:

```java
// In computeOpportunisticSignals(), replace the metrics fetch:
List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
Map<String, MarketMetrics> metricsMap = fetchLiveMetricsMap(pan);

// ENHANCEMENT: Also fetch metrics for accumulator/strategy funds not yet held
// by looking up their amfiCode via ISIN and querying conviction metrics directly
for (StrategyTarget t : targets) {
    String amfi = amfiCodeFor(t);
    if (!amfi.isEmpty() && !metricsMap.containsKey(amfi)) {
        String sql = """
            SELECT conviction_score, sortino_ratio, cvar_5, win_rate, max_drawdown,
                   nav_percentile_3yr, drawdown_from_ath, return_z_score
            FROM fund_conviction_metrics
            WHERE amfi_code = ?
            AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, amfi);
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                metricsMap.put(amfi, new MarketMetrics(
                    getSafeInt(r.get("conviction_score")),
                    getSafeDouble(r.get("sortino_ratio")),
                    getSafeDouble(r.get("cvar_5")),
                    getSafeDouble(r.get("win_rate")),
                    getSafeDouble(r.get("max_drawdown")),
                    getSafeDouble(r.get("nav_percentile_3yr")),
                    getSafeDouble(r.get("drawdown_from_ath")),
                    getSafeDouble(r.get("return_z_score")),
                    LocalDate.of(1970, 1, 1)
                ));
            }
        } catch (Exception e) {
            log.debug("No conviction metrics found for amfi {}", amfi);
        }
    }
}
```

Add helper methods `getSafeInt` and `getSafeDouble` if not already present:
```java
private double getSafeDouble(Object obj) { return obj == null ? 0.0 : ((Number) obj).doubleValue(); }
private int getSafeInt(Object obj) { return obj == null ? 0 : ((Number) obj).intValue(); }
```

**Fix 2: Widen the accumulator gate when metrics are absent**

When a fund has no metrics row at all (new fund, or engine hasn't run), don't silently skip it. Instead use ATH drawdown as a fallback signal:

```java
// In the ACCUMULATOR LOGIC block, replace the gate:
if ("accumulator".equalsIgnoreCase(target.status())) {
    boolean isUnderTarget = actualPct < (target.targetPortfolioPct() > 0 ? target.targetPortfolioPct() : 100);
    
    // Primary gate: NAV percentile signal
    boolean isNearLow = metrics.navPercentile3yr() < 0.45; // Slightly more permissive than 0.40

    // Fallback gate: if no NAV percentile data, use ATH drawdown as proxy
    // A fund down > 15% from ATH is likely at a dip
    boolean isAtAthDiscount = metrics.drawdownFromAth() < -0.15;
    
    // Also check if fund has been recently bought (respect cooldown)
    long daysSinceLastBuy = ChronoUnit.DAYS.between(metrics.lastBuyDate(), LocalDate.now());
    boolean cooldownPassed = daysSinceLastBuy >= 21; // 21-day minimum between accumulator buys

    boolean entrySignalPresent = isNearLow || isAtAthDiscount;

    if (isUnderTarget && entrySignalPresent && cooldownPassed) {
        String reason = isNearLow 
            ? String.format("NAV at %d%% of 3yr range — near historical low.", Math.round(metrics.navPercentile3yr() * 100))
            : String.format("NAV down %.1f%% from all-time high — discounted entry.", metrics.drawdownFromAth() * 100);
        
        List<String> justs = List.of("Accumulator entry: " + reason);
        
        Scheme scheme = schemeRepository.findByIsin(target.isin()).orElse(null);
        String amfi = scheme != null ? scheme.getAmfiCode() : "";
        double baseAmount = Math.max(10000, 
            (target.targetPortfolioPct() - actualPct) / 100.0 * totalPortfolioValue * 0.5);

        opportunisticDrafts.add(new TacticalSignal(
            target.schemeName(), amfi, "BUY", String.valueOf(baseAmount),
            target.targetPortfolioPct(), actualPct, 0, target.status(),
            metrics.convictionScore() > 0 ? metrics.convictionScore() : 40, // Default 40 if no score
            metrics.sortinoRatio(), metrics.maxDrawdown(), metrics.navPercentile3yr(),
            metrics.drawdownFromAth(), metrics.returnZScore(),
            metrics.lastBuyDate(), justs));
    }
}
```

**Fix 3: Make `weightSignalsByConviction()` work when lumpsum = 0**

When `cash = 0`, signals should still be returned (with their base amounts) rather than becoming ₹0. The intent is: show the investor WHAT to buy and the suggested sizing, even if they haven't specified a lumpsum.

```java
private List<TacticalSignal> weightSignalsByConviction(List<TacticalSignal> signals, double cash) {
    if (signals.isEmpty()) return signals;

    // Move cooldown check BEFORE the cash weighting
    List<TacticalSignal> activeDrafts = signals.stream().map(sig -> {
        long daysSinceLast = ChronoUnit.DAYS.between(
            sig.lastBuyDate() != null ? sig.lastBuyDate() : LocalDate.of(1970, 1, 1),
            LocalDate.now());
        if (daysSinceLast < 21) {
            List<String> justs = new ArrayList<>(sig.justifications());
            justs.add(String.format("Cooldown: Last bought %d days ago. Wait %d more days.", 
                daysSinceLast, 21 - (int)daysSinceLast));
            return createSignal(sig, "WATCH", "0", justs); // WATCH not HOLD — shows as opportunity but not actionable
        }
        return sig;
    }).collect(Collectors.toList());

    // If no cash, return the signals with their base amounts unchanged
    // (frontend can show them as "suggested" buys even without lumpsum)
    if (cash <= 0) {
        return activeDrafts.stream()
            .filter(s -> !"WATCH".equals(s.action())) // Filter out cooled down signals
            .map(s -> createSignal(s, "BUY", s.amount(), s.justifications()))
            .collect(Collectors.toList());
    }

    // With cash: weight by conviction score
    double totalDemand = activeDrafts.stream()
        .filter(s -> "BUY".equals(s.action()))
        .mapToDouble(s -> Double.parseDouble(s.amount()) * Math.max(0.2, s.convictionScore() / 100.0))
        .sum();

    return activeDrafts.stream().map(sig -> {
        if (!"BUY".equals(sig.action())) return sig;
        
        double baseAmount = Double.parseDouble(sig.amount());
        double scoreMult = Math.max(0.2, sig.convictionScore() / 100.0);
        double allocatedAmount = totalDemand > 0 
            ? (baseAmount * scoreMult / totalDemand) * cash 
            : baseAmount;
        
        List<String> justs = new ArrayList<>(sig.justifications());
        if (cash > 0) {
            justs.add(String.format("Allocated ₹%,.0f from available capital of ₹%,.0f.", allocatedAmount, cash));
        }
        return createSignal(sig, "BUY", String.format("%.2f", allocatedAmount), justs);
    }).collect(Collectors.toList());
}
```

**Fix 4: Add rebalancer deploy to also work without explicit lumpsum**

The rebalancer deploy currently only triggers when `arbitrageValue > 10000`. But `arbitrageValue` is calculated from holdings, and only holds funds you own. If the strategy sheet has a rebalancer fund (Invesco Arbitrage) but it hasn't been categorised with `assetCategory = "ARBITRAGE"` in the DB, it gets missed. Replace the category-based lookup with a name/status-based one:

```java
// Replace the arbitrageValue calculation:
double rebalancerValue = holdings.stream()
    .filter(h -> {
        // Match by asset category OR by strategy status from sheet
        String cat = h.getAssetCategory() != null ? h.getAssetCategory().toUpperCase() : "";
        boolean isArbitrageByCategory = cat.contains("ARBITRAGE");
        // Also match if the holding's scheme name matches a "rebalancer" status fund
        boolean isRebalancerBySheet = targets.stream()
            .anyMatch(t -> "rebalancer".equalsIgnoreCase(t.status()) 
                && t.schemeName().equalsIgnoreCase(h.getSchemeName()));
        return isArbitrageByCategory || isRebalancerBySheet;
    })
    .mapToDouble(AggregatedHolding::getCurrentValue)
    .sum();
```

---

## `TodayBriefView.tsx` — show opportunistic signals even at ₹0 amount

Currently the opportunistic section shows `s.amount` as a big rupee number. When signals are generated with `cash = 0`, the amount might be a base estimate not tied to actual cash. Adjust the display:

```tsx
// In the opportunistic signal card, replace the amount display:
<div className="text-xl font-medium tabular-nums mb-3 text-buy">
  {parseFloat(signal.amount) > 0 
    ? <CurrencyValue isPrivate={isPrivate} value={parseFloat(signal.amount)} />
    : <span className="text-warning text-sm">Suggested entry — set lumpsum to size</span>
  }
</div>
```

Also update the empty state message to be more informative — the current message "No opportunistic entry points detected" is accurate but gives no guidance. Change to:

```tsx
{payload.opportunisticSignals.length === 0 && (
  <div className="col-span-full py-6 px-6 bg-white/[0.02] border border-white/5 rounded-xl space-y-2">
    <p className="text-muted text-[11px] font-medium uppercase tracking-widest">
      No dip entries or rebalancer deploys triggered today
    </p>
    <p className="text-[11px] text-muted leading-relaxed">
      Accumulator funds (NASDAQ, Gold) fire when NAV is below 45% of 3yr range or 15%+ below ATH.
      Rebalancer deploys fire when core/strategy funds are 5%+ underweight and below 60% of 3yr NAV range.
    </p>
  </div>
)}
```

---

## Validation checklist

1. Exit queue shows ALL funds with `dropped` status in Google Sheet (target: ~12 funds).
2. The two debt funds (Gilt, Corporate Bond) appear at the TOP of the exit queue with `SLAB TAX` badge and "Priority exit" justification.
3. Equity dropped funds show either "Tax-efficient exit" (for LTCG lots) or "Deferred X days" (for near-threshold STCG) or "Exit queued" (fallback when lot data is missing).
4. A fund with `daysToNextLtcg > 45` AND `currentValue < 15000` gets an immediate exit recommendation.
5. `computeOpportunisticSignals()` fetches metrics for ALL strategy sheet funds, not just owned ones.
6. When `lumpsum = 0`, opportunistic signals still appear with base amount estimates.
7. The accumulator gate fires on either `navPercentile < 0.45` OR `drawdownFromAth < -0.15`.
8. `TodayBriefView` shows `SLAB TAX` badge on debt fund exit cards.
9. Backend logs show `"Exit queue computed: N funds to exit"` where N > 1.
