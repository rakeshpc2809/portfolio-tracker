Part I — Mathematics of the Scoring System
1.1 The Conviction Score Formula
The final score is a static five-factor weighted sum computed in ConvictionScoringService:
finalScore = yieldScore × 0.20 + riskScore × 0.25 + valueScore × 0.25
           + painScore × 0.15 + frictionScore × 0.15
All five component scores are individually scaled to [0, 100] before weighting. Each factor's transformation:
Yield Score — min(100, (personalCAGR / maxCAGR) * 100)
The maxCAGR is dynamically set to the portfolio's best performer (with a floor of 35%). This is smart relative normalisation — it adapts to the investor's own portfolio quality rather than some arbitrary benchmark. The CAGR itself is computed as a weighted-average-days annualised return across all open lots, which is a reasonable but simplified approximation of XIRR.
Risk Score — (sortino ≤ 0) ? 10 : min(100, 10 + sortino × 40)
The floor of 10 for any non-positive Sortino treats sortino = -0.01 identically to sortino = -3.0. A Sortino of -5 (catastrophic) scores the same as -0.5 (slightly bad). The linear region saturates at sortino = 2.25 → 100. The risk-free rate used is 7% (correct for Indian context, set in the SQL).
Value Score — max(5, min(95, 50 - rollingZScore252 × 22.5))
This is a mean-reversion buy signal dressed as a valuation factor. When z = +2 (expensive relative to history), score = 5. When z = -2 (historically cheap), score = 95. The multiplier 22.5 = 45/2 implies the score saturates at |z| = 2.22. The labelling as "Value" is slightly misleading — it measures historical cheapness via return distribution, not fundamental valuation (P/E, P/B).
Pain Score — max(0, 100 - maxDrawdown × 2.5)
Clean linear decay: at MDD = 40%, score = 0. The mdd value coming from the SQL is already in percentage form (* 100). Importantly the SQL computes MDD over 3 years only, so funds with large pre-3-year drawdowns are not penalised.
Friction Score — max(0, 100 - taxPctOfValue × 6.66)
The magic constant 6.66 = 100/15 implies the score reaches zero at a 15% effective tax rate. The current LTCG rate is 12.5% (post-Budget 2024), so a fund fully in LTCG territory scores 100 - 12.5×6.66 = 16.7 — not zero, which is correct. However STCG is now 20%, which would push the score to 100 - 20×6.66 = -33.2 (clamped to 0). Consider recalibrating the multiplier to 100/20 = 5.0 to align the scale to current STCG as the worst case.
1.2 The Quantitative Engine (Python Sidecar)
Three signals are computed nightly but never connected to the conviction score:
Hurst Exponent — computed as: tau[h] = √(std(NAV_t+h − NAV_t)), then H = 2 × slope(log τ vs log h). This is mathematically valid — the factor of 2 corrects for using √std instead of std directly. Values: H < 0.47 → MEAN_REVERTING, H > 0.53 → TRENDING, otherwise RANDOM_WALK. A mean-reverting fund is safer in a systematic buy-on-dip strategy; trending funds suit momentum tilts.
Ornstein-Uhlenbeck Half-Life — estimated via OLS regression of Δ(log NAV) on log NAV: dy = a + b·y, giving θ = -b, half_life = ln(2)/θ. Valid only when b < 0. Half-lives of 20–60 days are typical for equity mutual funds. This measures how quickly the fund reverts to its long-run equilibrium — directly useful for sizing opportunistic entries.
HMM Regime — 3-state Gaussian HMM (CALM_BULL / STRESSED_NEUTRAL / VOLATILE_BEAR) on daily log-returns, with means sorted descending so state 0 is always the best-return state. The bull_prob, bear_prob, and transition_to_bear are returned. This is a serious tool — the transition probability specifically tells you the probability of entering a bear regime from the current state, which is far more actionable than just knowing the current state.
None of these are wired into scoring.
1.3 Additional Computed Signals (Stored, Unused)
win_rate — fraction of trading days returning above the risk-free daily threshold (7%/252). Stored but never used in any formula.
cvar_5 — 5th percentile average loss (Conditional Value at Risk). This is a better tail-risk measure than max drawdown for fat-tailed assets. Computed and stored, but riskScore only uses Sortino.
nav_percentile_3yr (mislabelled — actually 1-year range, computed as (current − min_1yr) / (max_1yr − min_1yr)) — stored and exposed in MarketMetrics, but not used in the conviction score. Note the column name says "3yr" but the SQL clearly uses INTERVAL '365 days' — naming inconsistency.
drawdown_from_ath — current price relative to all-time high. Stored and exposed but not scored.

Part II — Bugs and Mathematical Issues
Bug 1: Negative Sortino Information Loss. The branch (sortino <= 0) ? 10 : ... is a cliff-edge. A fund with Sortino = -4.0 scores identical to one at -0.1. Replace with a sigmoid or extend the linear formula down:
java// Current (broken for negative values):
double riskScore = (sortino <= 0) ? 10 : Math.min(100, 10 + (sortino * 40));

// Proposed: continuous, captures negative Sortino meaningfully
double riskScore = Math.max(0, Math.min(100, 50 + (sortino * 25)));
// At sortino=2.0 → 100, at sortino=0 → 50, at sortino=-2.0 → 0
Bug 2: computeOpportunisticSignals sorts by returnZScore ascending (most negative z first). Since z ≤ 0 means cheap/beaten-down, this is intentional. But the comment says "opportunistic signals" — a z of -3 should be prioritised over z = -1. The sort is correct directionally, but there is no secondary sort by conviction score, so two funds with identical z-scores will have arbitrary ordering.
Bug 3: generateDailySignals calls evaluateAll() three times. Each invocation loads open lots, aggregates holdings, fetches all metrics maps, queries strategy targets, and runs LTCG queries. This is 3× the necessary database load for a single API response:
java// Current (3 DB round-trips):
public List<TacticalSignal> generateDailySignals(String pan, double monthlySip, double lumpsum) {
    all.addAll(computeOpportunisticSignals(pan, lumpsum));  // calls evaluateAll()
    all.addAll(computeActiveSellSignals(pan));               // calls evaluateAll()
    all.addAll(computeExitQueue(pan));                       // calls evaluateAll()
}

// Fix: evaluate once and partition
public List<TacticalSignal> generateDailySignals(String pan, double monthlySip, double lumpsum) {
    List<TacticalSignal> all = evaluateAll(pan);  // one call
    // partition by action/status and return combined
}
Bug 4: NAV lookup inside the lot-level loop. In calculatePersonalCagr, for a fund with 24 monthly SIP lots, navService.getLatestSchemeDetails(amfiCode) is called 24 times — for the same fund. The NAV won't change between iterations:
java// Fix: hoist outside the loop
String amfiCode = lots.get(0).getScheme().getAmfiCode();
double currentNav = navService.getLatestSchemeDetails(amfiCode).getNav().doubleValue();
if (currentNav <= 0) currentNav = ...;
for (TaxLot lot : lots) { ... }
Bug 5: RebalancingTrade tax calculation uses a guess. taxCost = sellAmt × 0.125 × 0.5. The 0.5 assumes 50% of the sell amount is taxable gain — but this ratio varies wildly (a fund bought yesterday vs. one held 5 years). The actual realized gain is already in the capital gain audit table. The system has the data to compute this precisely.
Bug 6: Hardcoded benchmark return values in PerformanceView.tsx. The periodic performance matrix table hardcodes bench: 1.2, 4.5, 8.2, 14.8, 18.5. These are stale placeholder values visible to the user as real Nifty 50 returns. The API already has a benchmarkService.getBenchmarkReturnsForAllPeriods() method — these values need to come from there.
Bug 7: nav_percentile_3yr column naming. The SQL computes a 1-year range percentile (nav_date >= CURRENT_DATE - INTERVAL '365 days'), but the column is named nav_percentile_3yr and the UI exposes it as such. A 1-year percentile and a 3-year percentile carry very different information. Rename or recompute.
Bug 8: Max Drawdown SQL is O(n²). The drawdown subquery does a full cross-join of fund_history h1 JOIN fund_history h2 ON h2.nav_date > h1.nav_date per fund. For a fund with 750 trading days of history, that's ~281,000 row comparisons. Replace with the standard running-max window function approach:
sql-- O(n) alternative using window functions:
WITH peaks AS (
    SELECT amfi_code, nav_date, nav,
           MAX(nav) OVER (PARTITION BY amfi_code ORDER BY nav_date) AS peak_nav
    FROM fund_history WHERE nav_date >= CURRENT_DATE - INTERVAL '3 years'
)
SELECT amfi_code, MIN((nav - peak_nav) / peak_nav) AS max_drawdown
FROM peaks GROUP BY amfi_code

Part III — Redundancies
Redundancy 1: Two identical RestTemplate beans. RestConfig and HmmRestConfig both create a SimpleClientHttpRequestFactory with identical 5s/10s timeouts. The only difference is the bean name. One can be removed; the remaining bean used via @Qualifier where needed.
Redundancy 2: FundStatus enum (ACTIVE/REDEEMED) vs string-based status system. The enum is used in DashboardService, while everywhere else string literals like "DROPPED", "EXIT", "ACCUMULATOR", "WATCH" are scattered across PortfolioOrchestrator, RebalanceEngine, and TacticalSignal. A unified enum with all states would allow compile-time safety, exhaustive switch coverage, and IDE-assisted refactoring.
Redundancy 3: composite_quant_score column. This column is computed by BucketZScorerService (bucket peer relative score) but the relationship to conviction_score is never documented or enforced. Both are integers in [0, 100]. The dashboard only renders conviction_score. The composite_quant_score is effectively a dead column in the UI.
Redundancy 4: OU columns ou_theta, ou_mu, ou_sigma, ou_buy_threshold, ou_sell_threshold. These are defined in the DB schema in ensureColumnsExist() but the Python sidecar only persists ou_half_life and ou_valid. The others remain at their default value of 0.0 permanently. Either populate them (theta and mu in particular are the most actionable — mu is the long-run equilibrium NAV level) or drop the columns.
Redundancy 5: hurst_20d, hurst_60d, multi_scale_regime columns. Defined in schema, never computed. These suggest an earlier design intent (multi-scale Hurst analysis) that was never completed. Either implement or drop them.
Redundancy 6: win_rate and cvar_5. Both are correctly computed in the nightly SQL, stored persistently, and exposed in MarketMetrics — but neither appears in any scoring formula or UI display element. They are pure database overhead.

Part IV — New Factors to Introduce
4.1 Regime-Conditional Multiplier (highest priority — uses existing data)
The HMM bear probability is already computed every night. It should modulate the final conviction score:
java// In ConvictionScoringService, after computing finalScore:
double bearProb = safeDouble(fund.get("hmm_bear_prob"));
double regimePenalty = bearProb * 20.0; // up to -20 points in full bear regime
finalScore = Math.max(0, finalScore - regimePenalty);
This is zero-cost to implement — no new data, no new computation. A fund in confirmed VOLATILE_BEAR (bear_prob ≈ 0.9) gets its score suppressed by up to 18 points, naturally making the engine less aggressive in entering positions during regime deterioration.
4.2 OU Mean-Reversion Score (uses existing data)
The half-life is computed and stored. A short half-life (fast mean reversion) is a quality signal — the fund recovers from drawdowns efficiently. Where OU is valid:
javadouble ouScore = 0;
if (safeDouble(fund.get("ou_valid")) > 0) {
    double halfLife = safeDouble(fund.get("ou_half_life"));
    // Half-life of 10 days → score 100, 100 days → score ~31
    ouScore = Math.max(0, Math.min(100, 100 * Math.exp(-halfLife / 30.0)));
}
This can either replace or blend with painScore given both measure drawdown recovery quality (MDD is the depth; OU half-life is the speed of recovery).
4.3 Expense Ratio Drag Factor
expenseRatio is already stored in FundMetric but never used. For index funds, expense ratio is the primary differentiator. The alpha net of expenses is the only return that matters:
java// Expense ratio drag score: 0% ER → 100, 2% ER → 0 (linear)
double expRatio = safeDouble(fund.get("expense_ratio")); // already in DB
double expenseScore = Math.max(0, 100 - expRatio * 50);
This is especially important for distinguishing between competing index funds on the same benchmark.
4.4 Momentum Factor
Short-term price momentum (1–3 month return) vs the fund's own peer bucket is well-established academically. The bucket peer z-scorer infrastructure already exists — adding a short-horizon return z-score alongside the 252-day version would be straightforward:
sql-- Add to updateNavSignals():
(nav_30d / LAG(nav_30d, 1) OVER ... - 1) AS return_1m
-- then z-score within bucket
4.5 AUM Quality Band
Very small AUMs (< ₹100 Cr) carry closure risk and wider bid-ask spreads. Very large AUMs (> ₹50,000 Cr) may face capacity constraints in mid/small cap mandates. A log-normalized AUM band score:
java// aumCr already in FundMetric
double aumCr = safeDouble(fund.get("aum_cr"));
double aumScore;
if (aumCr < 100) {
    aumScore = (aumCr / 100.0) * 50;  // small fund penalty
} else if (aumCr > 50000) {
    aumScore = Math.max(50, 100 - (aumCr - 50000) / 5000.0);  // giant fund penalty
} else {
    aumScore = 100;  // sweet spot
}

Part V — Suggested Revised Scoring Architecture
With the above additions, a refined 7-factor model:
FactorWeightSourceYield0.18Personal CAGR (unchanged)Risk (Sortino)0.20Fixed negative-Sortino curveValue (Z-Score)0.20UnchangedPain + Recovery0.15MDD + OU half-life blendedRegime0.12HMM bear probability (new)Friction0.10Updated multiplier (20% STCG base)Expense0.05Expense ratio (new)
Total = 1.00. The regime and expense factors use zero additional compute — the data is already there.

Part VI — UI Analysis and Improvements
The UI aesthetic is coherent and well-executed (dark-glass morphism, uppercase tracking, Catppuccin-adjacent colour palette). The core issues are data correctness and analytical depth, not visual style.
Critical: Hardcoded benchmark numbers. The PerformanceView periodic performance matrix shows static values (1.2%, 4.5%, 8.2%, 14.8%, 18.5%) as Nifty 50 benchmarks. These will be wrong for any user viewing the dashboard more than a week after these were typed. The benchmarkService.getBenchmarkReturnsForAllPeriods("NIFTY 50") endpoint already exists. Replace the hardcoded object with a useEffect call to that API.
Missing: 3-Year column. PeriodReturns record has a threeYear field but the table only shows 1M, 3M, 6M, 1Y, ITD. The 3Y row is absent. Add it.
Conviction score breakdown is invisible. The dashboard shows a single integer score (e.g. "73") but the five sub-scores (yield/risk/value/pain/friction) are stored in the database and returned by the API. A radar chart or horizontal bar breakdown per fund in FundDetailView would show why a fund scores 73 — whether it's cheap (high value score) but risky (low pain score), or expensive but quality. This is the most actionable analytical enhancement possible with zero backend work.
HMM / Hurst regime is invisible. The MarketMetrics DTO carries hmmState, hmmBullProb, hmmBearProb, hurstExponent, hurstRegime, and ouHalfLife. None of these appear in the UI. A compact "Quant signals" strip on FundDetailView showing regime state + bear probability + mean-reversion speed would surface serious information that currently exists only in the database.
Scatter chart quadrant labels are unreadable. In FundsListView, the conviction vs. z-score scatter chart has quadrant watermarks ("Ideal Core", "Exit Candidates") at opacity="0.15" with a fontSize="10". At that combination they are effectively invisible. Increase to opacity="0.35" or render them as proper pill badges outside the chart area.
Goal planner is a deterministic simulation. The yearsToGoal calculation in PerformanceView iterates fv = fv * (1 + monthlyRate) + monthlyAddition with the historical XIRR as a fixed rate. This produces false precision — a single number like "14.3 years" suggests more certainty than any investment model can offer. A simple Monte Carlo wrapper (±30% of XIRR as volatility, 500 trials) would produce a confidence range ("12–18 years at 80% confidence") which is far more honest and useful.
Performance chart tooltip always shows "since start." The tooltip in the wealth trajectory chart renders +{((point.data.y as number) - 100).toFixed(1)}% since start for every point. Since the y-axis is an indexed value starting at 100, this is technically correct but semantically confusing for mid-chart points — users expect the tooltip to show the return for the period ending at that date, not since inception. Consider showing point-in-time YoY return instead.
Benchmark comparison is Nifty 50 only. The chart compares against Nifty 50 for a portfolio that may include mid-cap, gold, and debt funds. A mid-cap heavy portfolio beating Nifty 50 may still be underperforming Nifty Midcap 150. Consider adding a dynamically weighted composite benchmark based on bucket allocation percentages — the benchmark data for NIFTY MIDCAP 150, NIFTY SMALLCAP 250, and NIFTY 500 is already being scraped and stored in the Python sidecar.
SIP range input is capped at ₹2 lakh. The max="200000" slider in TodayBriefView has a step="5000". For larger investors, ₹2L may be the lower bound, not the upper. Consider making it configurable or extending the range.

Summary of Priority Actions
Rank-ordered by impact-to-effort ratio:

Wire HMM bear probability into the conviction score — 5 lines of code, uses existing data, materially improves signal quality.
Fix evaluateAll() called 3× — single-line cache, removes 2/3 of the heaviest query load on the rebalancing API.
Fix NAV fetch inside lot loop — hoist outside, eliminates N-1 redundant service calls per fund scoring.
Fix max drawdown SQL — replace the O(n²) cross-join with a window function; this is the single most expensive query in the nightly engine.
Remove hardcoded benchmark values in PerformanceView — data correctness issue visible to every user.
Add conviction factor breakdown to FundDetailView — surfaces analysis that already exists in the API.
Fix frictionScore multiplier from 6.66 to 5.0 (aligning to 20% STCG ceiling) — improves scoring accuracy for recently-acquired holdings.
Fix negative Sortino cliff — replace the 10-floor branch with a continuous function.
Unify FundStatus into a single enum — prevents silent bugs from string typos.
Rename nav_percentile_3yr or recompute it — avoid misleading labels in a financial application.
