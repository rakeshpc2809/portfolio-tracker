# PORTFOLIO OS // SYSTEM ANALYSIS & OPTIMIZATION

> Architecture, Mechanics, and Quantitative Directives for the Rebalancing Engine.
> Cleanly formatted for terminal environments.

---

## I. ARCHITECTURE & EFFICIENCY

**1. Redundant State Computation**
The `generateDailySignals` process currently invokes `evaluateAll()` redundantly for opportunistic, active sell, and exit queue signals. 
* **Action:** Compute the state graph once. Partition results in-memory to eliminate 66% of database load and optimize systemic resource usage during the daily cron run.

**2. O(n²) SQL Bottlenecks**
Max Drawdown calculations currently utilize a self-join (`h1 JOIN h2 ON h2.nav_date > h1.nav_date`).
* **Action:** Refactor using a linear O(n) window function: `MAX(nav) OVER (PARTITION BY amfi_code ORDER BY nav_date)`. This is essential for long-term scalability as the time-series data grows.

**3. Strict State Typing**
Raw strings are currently used for portfolio states.
* **Action:** Transition all string-based states ("DROPPED", "EXIT", "ACTIVE") to a unified `FundStatus` enum to enforce compile-time safety and exhaustiveness in switch cases.

**4. Query Hoisting**
* **Action:** Hoist the NAV lookup (`navService.getLatestSchemeDetails`) outside the lot-level iteration in `calculatePersonalCagr` to eliminate N-1 redundant service calls per fund.

## II. CORE DIRECTIVES & MECHANICS

**1. Strict SIP Isolation**
The `PortfolioOrchestrator` must decouple tactical rebalancing from structural cash flows. 
* **Constraint:** The engine is strictly a read-only advisory layer for existing SIP allocations. It must *never* mutate or intercept structural SIPs. Its sole mandate is emitting analytical signals (e.g., tactical lump-sum deployments or tax-loss harvesting) while preserving the core automated cash flow untouched.

**2. Concentrated Factor Execution**
Over-diversification is a mathematical drag on returns and is counterproductive to the core strategy.
* **Constraint:** The `BucketZScorerService` is restricted from seeking new assets outside the current mandate. Capital density must be prioritized into the highest-conviction existing holdings (such as LargeMidcap 250 or primary factor funds). The engine must widen existing positions to compound growth rather than expanding the portfolio's surface area.

**3. Precision Tax Accounting**
* **Action:** Deprecate the 50% taxable gain heuristic (`sellAmt * 0.125 * 0.5`). Utilize the `CapitalGainAudit` table's FIFO inventory matching to execute deterministic queries for exact realized gain calculations.

## III. QUANTITATIVE MODELS

**1. Continuous Risk Scaling (Sortino)**
Hard floors for negative Sortino ratios (`sortino <= 0 ? 10 : ...`) create severe information loss, treating mild and catastrophic downside deviation identically.
* **Action:** Implement a continuous decay function: `Math.max(0, Math.min(100, 50 + (sortino * 25)))`.

**2. Regime-Conditional Modifiers (HMM)**
The Python sidecar effectively models Hidden Markov Model transition probabilities, but this context is missing from the final `ConvictionScore`.
* **Action:** Wire `hmm_bear_prob` as a dampener. Applying a dynamic penalty during a confirmed `VOLATILE_BEAR` regime will prevent the system from aggressively buying into structural market collapses.

**3. Tax Friction Calibration**
* **Action:** Update the friction multiplier to reflect current STCG (20%) and LTCG (12.5%) brackets. Change `taxPctOfValue * 6.66` to `5.0` so the score correctly zeroes at maximum drag.

**4. Ornstein-Uhlenbeck (OU) Mean Reversion**
* **Action:** The currently dormant OU half-life metric should be blended with the `PainScore`. Measuring the velocity of mean reversion alongside the depth of the drawdown provides a strictly superior metric of asset resilience.

---
*END OF SPECIFICATION*
