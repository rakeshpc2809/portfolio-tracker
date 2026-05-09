# Portfolio OS — Codebase Analysis & Refactor Plan

## What This System Actually Is

A personal mutual fund portfolio intelligence system. You ingest your CAS PDF, and the system:
- Tracks each fund's XIRR against its benchmark
- Scores every fund with a multi-factor conviction score (momentum, risk, value, regime, mean-reversion, Hurst)
- Classifies market regimes per fund (HMM: CALM_BULL / VOLATILE_BEAR / STRESSED_NEUTRAL)
- Detects statistical mispricings (252-day rolling Z-score, OU process half-life)
- Manages tax lots (FIFO), projects LTCG/STCG, flags harvest opportunities
- Generates rebalancing signals (SIP routing, exit queue, opportunistic buys)

The math is solid. The infrastructure is not matched to the problem size.

---

## The Wins — Keep These Exactly As They Are

### Backend (cas-injector)
| Component | Why It's a Win |
|---|---|
| `CommonUtils.SOLVE_XIRR` | Correct Newton-Raphson XIRR, well-tested |
| `CommonUtils.DETERMINE_TAX_CATEGORY` | Handles post-Apr-2023 debt fund rule correctly |
| `DashboardService.computeSummary` | Clean CQRS pattern — reads from pre-built read model, falls back to live compute |
| `QuantitativeEngineService` | The conviction scoring pipeline (sortino, cvar, drawdown, z-score, hurst, HMM, OU) is the core intellectual value of the whole project |
| `AmfiDailyDeltaService` + `HistoricalBackfillerService` | Clean AMFI NAV ingestion — this is what makes everything else work |
| `BenchmarkService` | Assigns per-fund benchmark returns by category bucket — simple but critical |
| `TaxLossHarvestingService` | Identifies unrealized losses that can be harvested before year-end |
| `FifoInventoryService` | Correct FIFO tax lot matching |

### Frontend (portfolio-dashboard)
| Component | Why It's a Win |
|---|---|
| `FundsListView` — Z-Score bar | The visual statistical pricing indicator is genuinely useful |
| `FundsListView` — Conviction dots | Clean 5-dot conviction display with sparkline history |
| `PortfolioView` — Treemap | Fund allocation colored by action signal (BUY/HOLD/EXIT) is the single best visual in the app |
| `PortfolioView` — Risk-Conviction scatter | High conviction + low drawdown quadrant ("Ideal Core") is insightful |
| `PortfolioView` — Alpha bar chart | XIRR vs benchmark per fund, colored green/red for winning/losing |
| `TodayBriefView` — LTCG headroom bar | ₹1.25L cap progress bar with remaining amount is genuinely useful on every visit |
| `FundDetailView` | The per-fund slide-over with OU half-life, HMM state, conviction history is excellent |
| `isPrivate` masking | Elegant privacy toggle — worth keeping |

### Python (cas-parser)
| Component | Why It's a Win |
|---|---|
| `scoring_engine.py` | Multi-factor conviction scoring with weighted sub-scores |
| `ticker_plant.py` | NAV time-series fetching and feature engineering |

---

## The Redundancy & Overreach — Remove or Simplify

### Backend Overreach

**Kafka (`KafkaConfig.java`)** — Dead weight for a personal app. It's hardcoded to `localhost:9092` and adds Docker container complexity for zero benefit. You have one user. Use Spring application events (already partially done) or simple synchronous calls.  
→ **Remove Kafka entirely. Keep `ApplicationEventPublisher`.**

**FIU / Account Aggregator (`FiuConsentService`, `FiuCallbackController`, `FiuCryptoUtils`)** — SEBI Account Aggregator flow for automated data fetching. Not surfaced in the UI. Adds OAuth/crypto complexity with no active usage.  
→ **Comment out / archive. CAS upload works fine.**

**`GoogleSheetService`** — Syncing strategy targets to a Google Sheet. Adds API credentials, auth flows, and a brittle integration for something the app itself does better.  
→ **Remove. The StrategyManagerView handles this in-app.**

**`EventStoreService` + `EventAdminService`** — Event sourcing infrastructure (storing and replaying domain events). For a single-user personal app, this is a patterns exercise, not a necessity.  
→ **Simplify. Keep `PortfolioSummaryUpdater` (the read-model builder). Drop the full event store.**

**`SystemicRiskMonitorService`** — Mentioned in the structure but not surfaced in any meaningful UI path.  
→ **Remove or fold its logic into the conviction scoring service.**

**`PythonQuantClient` (HTTP bridge)** — Java calling a Python scoring engine over HTTP is fragile. If the Python process is down, conviction scores silently fall back to defaults. The scoring math could live entirely in Java or run as a scheduled Python script that writes to the database.  
→ **Make the Python script a scheduled batch job that writes metrics to `conviction_metrics` table. Remove the live HTTP bridge.**

**`WebSocketConfig` + `PriceStreamGateway`** — Real-time NAV streaming via WebSocket. NAV updates once per day (AMFI publishes EOD NAV). This infrastructure is solving a non-existent problem.  
→ **Remove. Poll on page load.**

### Frontend Overreach

**`AIOptimizerView.tsx`** — "AI Optimizer" tab that overlaps entirely with WealthAI. Both are AI chat interfaces for the portfolio. You don't need two.  
→ **Merge into `WealthAIView`. Remove the tab.**

**`PortfolioVisualizer.tsx` (3D)** — Three.js 3D globe/particle visualization. Not connected to any navigation tab. Beautiful but zero analytical value.  
→ **Delete.**

**`AlphaFeedView.tsx`** — News/sentiment feed as a standalone tab ("Pulse"). Useful data but it's a tab that most visits won't need.  
→ **Demote from a tab. Add a collapsible "Market Pulse" section inside TodayBrief.**

**`StrategyManagerView.tsx`** — Full UI for editing allocation targets (planned %) per fund. For a personal app, this could be a settings drawer or a simple table inside FundDetail.  
→ **Merge into a settings drawer. Remove as a primary tab.**

**`LtcgPlannerView.tsx`** — In the `views/` directory but not linked to any tab in `Dashboard.tsx`.  
→ **Either wire it up or delete it. The LTCG headroom in TodayBrief already covers the core need.**

**Tab count: 12 tabs is too many.** The navigation bar overflows on mobile and forces users to scroll to find core views.

---

## The Dashboard Gap — What's Missing

The biggest problem with the current UI: **there is no single screen where you can see the current state of the entire portfolio and every fund at once.** You have to jump between "Today", "Portfolio", and "Each Fund" to piece together the picture.

The `TodayBriefView` is closest to a dashboard but it's focused on *actions* (what to do today), not *state* (where you stand right now).

### Proposed: New `OverviewView` (replaces Today as the landing tab)

A single-screen dashboard with these sections, in order:

#### 1. Portfolio Health Strip
Four number cards at the top:
- **Current Value** (large, glowing)
- **Unrealized Gain / Loss** (absolute + %)
- **Overall XIRR** (vs the date you started)
- **LTCG Headroom** (₹X of ₹1.25L remaining, FY progress bar)

#### 2. Fund Status Grid
Every active fund as a compact card. Each card shows:
- Fund simple name
- Current value
- XIRR % (colored green/red) + benchmark delta (e.g. `+2.3% vs Nifty`)
- Conviction dots (1–5)
- HMM regime badge (🟢 Bull / 🔴 Bear / 🟡 Neutral)
- Action badge (BUY / HOLD / HARVEST / EXIT)
- Z-score indicator bar (cheap ←→ expensive)

This gives you the entire portfolio state on one screen. Click any card → FundDetailView slide-over.

#### 3. Portfolio Composition (compact)
- Pie chart: Category breakdown (Equity / Debt / Gold / ELSS)
- Bar: AMC concentration

#### 4. Action Queue (from TodayBrief)
- SIP this month with amounts
- Exit candidates
- Harvest opportunities
- Market regime summary bar (N bull / N bear / N neutral)

#### 5. Signals (demoted from Pulse tab)
Collapsible. Top 3 sentiment/alpha signals from the feed.

---

## Proposed Tab Structure (12 → 7)

| Before | After | Reason |
|---|---|---|
| Today | **Overview** | Rebuilt as the comprehensive dashboard described above |
| Portfolio | **Portfolio** | Keep — treemap, pies, scatter are excellent |
| Performance | **Performance** | Keep — equity curve & period returns |
| Each Fund | **Funds** | Keep — the list + FundDetail slide-over |
| Tax | **Tax** | Keep — LTCG/STCG breakdown is high-value |
| Ledger | **Ledger** | Keep — transaction history |
| Rebalance | ~~merged into Overview Action Queue~~ | Core signals already surface in Overview |
| Strategy | ~~settings drawer~~ | Move to a ⚙️ icon in the header |
| Pulse (Alpha) | ~~collapsed into Overview~~ | Demote to collapsible section |
| Optimizer (AI) | ~~merged into Wealth AI~~ | One AI tab is enough |
| Wealth AI | **Wealth AI** | Keep — one AI tab |
| Data (Upload) | **Data** | Keep — but move to a secondary icon in the header |

---

## Specific Frontend Component Changes

### `Dashboard.tsx`
- Drop tabs: `rebalance`, `strategy`, `alpha`, `ai`
- Add tab: `overview` (new)
- Move Upload (`data`) + Strategy to header icons, not primary tabs
- 12 tabs → 7 tabs

### `TodayBriefView.tsx`
- Retire as a tab
- Extract: the SIP budget panel, action queue, and regime bar → reuse in `OverviewView`
- Extract: LTCG headroom widget → reuse in `OverviewView`

### New `OverviewView.tsx`
- Fund status grid (the main new addition)
- Assemble from existing data — no new API calls needed
- All data comes from `portfolioData.schemeBreakdown` which is already fetched

### `PortfolioView.tsx`
- Remove the "Execution Efficiency" stats block (Regime Climate, Mean Reversion Pulse, Alpha Consistency) — these will live in Overview
- Keep: Treemap, Pie charts (AMC + Category), Alpha bar chart, Risk-Conviction scatter, Correlation heatmap

### `FundsListView.tsx`
- Keep exactly as is — it's well-built

---

## Backend Cleanup Priority Order

1. **Remove Kafka** — biggest complexity-to-value ratio reduction
2. **Remove FIU / Account Aggregator** — dead code, security surface area
3. **Remove Google Sheets sync** — credentials, fragility
4. **Convert Python bridge to batch job** — resilience improvement
5. **Remove WebSocket price streaming** — no real-time need
6. **Archive event store** — keep the read model, drop the store

---

## What NOT to Touch

- The entire `cas-parser/scoring_engine.py` + `ticker_plant.py` pipeline
- `CommonUtils` (XIRR, tax categorization, name normalization)
- `DashboardService.computeSummary` — the CQRS read-model pattern is correct
- `AmfiDailyDeltaService` — clean daily NAV sync
- `TaxLossHarvestingService` — the core tax logic
- `FifoInventoryService` — correct lot matching
- `FundDetailView.tsx` — excellent per-fund deep-dive
- `FundsListView.tsx` — the Z-score bar and conviction dots are the best parts of the UI
- The privacy masking system

---

## Summary

The backend has excellent quant math — XIRR, conviction scoring, HMM regime detection, OU mean-reversion, Hurst exponent, FIFO tax lots — all of it is sound and worth keeping. The problem is the infrastructure scaffolding (Kafka, WebSockets, FIU, event store, Google Sheets) that was added for patterns/completeness but serves no real purpose at personal-use scale.

The frontend has a beautiful design system and several genuinely insightful charts (treemap, scatter, Z-score bar). The problem is 12 tabs making it hard to land on the right view, and the absence of a single "state of the portfolio" screen that shows every fund's health at a glance.

The single highest-value change is building the **Overview dashboard with a fund-by-fund status grid**, which requires zero new backend work — all the data is already in `schemeBreakdown`. It's purely a frontend composition task assembling existing components into a new layout.
