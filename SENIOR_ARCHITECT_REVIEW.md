# Portfolio OS — Senior Architect Review
## Complete Analysis, Logic Fixes, New Features & Gemini CLI Instructions

> **How to use:** Work through sections in order. Each task has a self-contained Gemini prompt.
> Gemini self-healing rule: if a task fails due to a compile error, read the error, resolve
> the import from existing patterns in the codebase, annotate what changed, and continue.

---

## Part 1 — Critical Logic Gaps

### 1.1 — The "1yr Range Pulse" bar is fundamentally broken

**Root cause — `PriceZoneBar` in `FundsListView.tsx`**

The bar shows `navPercentile3yr` (renamed to "1yr" in the UI). This metric answers:
"Where does today's NAV sit within the fund's own 1-year price history?"

**Why this makes no sense for mutual funds:**
Any equity fund in a bull market will have a NAV that is at or near its all-time-high because mutual fund NAVs are cumulative and drift upward structurally over time. A fund that returned +30% YTD will sit at the 97th percentile of its 1-year range — flagged "Expensive" — even if it is statistically cheap relative to its volatility right now. The metric is telling you "the fund has grown this year", not "the fund is expensive".

**What it should show instead:** The `rollingZScore252` already exists in `SchemePerformanceDTO`. This is the right signal:
- Z < -2 → genuinely statistically cheap (BUY territory)
- Z between -1 and +1 → fairly priced
- Z > +2 → statistically stretched (consider trimming)

**Gemini prompt — fix `FundsListView.tsx`:**
```
In FundsListView.tsx, replace the PriceZoneBar component entirely.

The current `percentile` prop maps to `fund.navPercentile3yr` which is meaningless
for a growing asset. Replace with a component that uses `rollingZScore252`.

New component: ZScoreBar

Props: { zScore: number; rarityPct: number }

Logic:
  const z = Math.max(-4, Math.min(4, zScore ?? 0));
  const pct = ((z + 4) / 8) * 100;  // Map -4..+4 → 0..100%
  const zone = z <= -2 ? 'Statistically cheap' 
             : z <= -1 ? 'Mild discount'
             : z >= 2  ? 'Statistically stretched'
             : z >= 1  ? 'Mild premium'
             : 'Fair value';
  const color = z <= -1.5 ? 'text-buy' : z >= 1.5 ? 'text-exit' : 'text-muted';

  Render as before (gradient bar + moving dot) but with 5 zones:
  Left 25% → dark green tint (cheap)
  25-40%  → light green tint (mild discount)
  40-60%  → neutral (fair)
  60-75%  → light red tint (mild premium)
  Right 25% → dark red tint (stretched)

  Label: "{z.toFixed(1)}σ — {zone}"
  Sub-label (right side): "Only {rarityPct.toFixed(1)}% of days this {z < 0 ? 'cheap' : 'expensive'}"
  Show the sub-label only when |z| >= 1.5.

  LearnTooltip term: "Z_SCORE" with text updated to:
    "Measures how far today's NAV is from the fund's own 1-year average,
     relative to its typical daily swings. -2σ means the fund is cheaper than
     97.5% of recent history — a statistical buy signal. +2σ means it's more
     expensive than 97.5% of recent history."

In the parent card JSX, change the prop from:
  <PriceZoneBar percentile={fund.navPercentile3yr} />
to:
  <ZScoreBar zScore={fund.rollingZScore252} rarityPct={fund.historicalRarityPct ?? 50} />

Update `signals.ts` to add `rollingZScore252: number` and 
`historicalRarityPct: number` to the fund interface if not already present.
```

---

### 1.2 — `BenchmarkService` SQL is logically incorrect

**Current SQL (broken):**
```sql
SELECT (closing_price / NULLIF(LAG(closing_price, 252) OVER (ORDER BY date), 0) - 1) * 100
FROM index_fundamentals
WHERE index_name = ?
ORDER BY date DESC LIMIT 1
```
`LAG(...) OVER (...)` is a window function. When you add `WHERE index_name = ?`, the window is computed over the filtered rows first, then you pick the last row. The issue is the `ORDER BY date DESC LIMIT 1` picks the most recent row — but the LAG(252) on that row looks 252 rows back in the ascending-sorted window. The LIMIT is applied after the window, so it does actually work, but it is fragile and dependent on having >= 252 rows per index. More critically, it returns the 1-year return as a scalar percentage, which is fine — but there's no validation that the gap is actually ~252 trading days (not calendar days). If `index_fundamentals` has gaps, the result will be wrong.

**Gemini prompt — fix `BenchmarkService.java`:**
```
In BenchmarkService.java, replace the getBenchmarkReturn SQL with a robust 
two-query approach that finds the price exactly 365 calendar days ago 
(not 252 rows ago, which breaks if there are data gaps):

String sql = """
    WITH latest AS (
        SELECT closing_price, date
        FROM index_fundamentals
        WHERE index_name = ?
        ORDER BY date DESC
        LIMIT 1
    ),
    year_ago AS (
        SELECT closing_price
        FROM index_fundamentals
        WHERE index_name = ?
        AND date <= (SELECT date FROM latest) - INTERVAL '365 days'
        ORDER BY date DESC
        LIMIT 1
    )
    SELECT (latest.closing_price / NULLIF(year_ago.closing_price, 0) - 1) * 100
    FROM latest, year_ago
    """;
Double result = jdbcTemplate.queryForObject(sql, Double.class, targetIndex, targetIndex);

Also add a new method: Map<String, Double> getBenchmarkReturnsForAllPeriods(String benchmarkIndex)
that returns 1M, 3M, 6M, 1Y, 3Y returns for the given index.
This will be used by the new Performance view (Task 3.3).
```

---

### 1.3 — Portfolio snapshot saves stale cached data

**Root cause in `MetricsSchedulerService.snapshotAllPortfolios()`:**

```java
var summary = dashboardService.getInvestorSummary(pan);  // ← Hits @Cacheable
double totalValue = summary.getCurrentValueAmount().doubleValue();
```

`getInvestorSummary` is `@Cacheable(value = "dashboardSummaryV3", key = "#pan")`. When the scheduler runs at 7:30pm, the cache was populated earlier in the day. The snapshot stores the cached (stale) value, not the fresh end-of-day value.

**Gemini prompt:**
```
In MetricsSchedulerService.java, change snapshotAllPortfolios() to compute
portfolio value directly from the database rather than via the cached service:

private void snapshotAllPortfolios() {
    logger.info("📸 Starting nightly portfolio value snapshot...");
    investorRepository.findAll().forEach(investor -> {
        try {
            String pan = investor.getPan();
            
            // Direct DB query — not cached, always fresh
            Double totalValue = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(tl.remaining_units * s2.nav), 0)
                FROM tax_lot tl
                JOIN scheme s ON tl.scheme_id = s.id
                JOIN folio f ON s.folio_id = f.id
                JOIN (
                    SELECT amfi_code, nav
                    FROM fund_history
                    WHERE (amfi_code, nav_date) IN (
                        SELECT amfi_code, MAX(nav_date)
                        FROM fund_history
                        GROUP BY amfi_code
                    )
                ) s2 ON s2.amfi_code = s.amfi_code
                WHERE f.investor_pan = ?
                AND tl.status = 'OPEN'
                """, Double.class, pan);
            
            // Also compute total invested from open lots
            Double totalInvested = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(tl.remaining_units * tl.cost_basis_per_unit), 0)
                FROM tax_lot tl
                JOIN scheme s ON tl.scheme_id = s.id
                JOIN folio f ON s.folio_id = f.id
                WHERE f.investor_pan = ? AND tl.status = 'OPEN'
                """, Double.class, pan);

            jdbcTemplate.update("""
                INSERT INTO portfolio_snapshot (pan, snapshot_date, total_value, total_invested)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (pan, snapshot_date) DO UPDATE 
                SET total_value = EXCLUDED.total_value,
                    total_invested = EXCLUDED.total_invested
                """, pan, LocalDate.now(), 
                    totalValue != null ? totalValue : 0.0,
                    totalInvested != null ? totalInvested : 0.0);
                    
            logger.info("📸 Snapshot for PAN {}: Value=₹{} Invested=₹{}", 
                pan, totalValue, totalInvested);
        } catch (Exception e) {
            logger.error("Failed to snapshot for investor {}: {}", investor.getPan(), e.getMessage());
        }
    });
}

Also add total_invested column to portfolio_snapshot table:
Run this migration (add to ConvictionMetricsRepository.ensureColumnsExist or a Flyway file):
ALTER TABLE portfolio_snapshot ADD COLUMN IF NOT EXISTS total_invested DOUBLE PRECISION DEFAULT 0;
```

---

### 1.4 — `spring.jpa.hibernate.ddl-auto=update` is dangerous in production

This lets Hibernate silently drop and recreate columns during startup. Combined with `@PostConstruct` ALTER TABLE in `ConvictionMetricsRepository`, you have two systems both trying to manage the schema simultaneously.

**Gemini prompt — `application.properties`:**
```
Change spring.jpa.hibernate.ddl-auto=update to spring.jpa.hibernate.ddl-auto=validate

This forces Hibernate to only VERIFY the schema matches entities, never mutate it.
Your existing @PostConstruct DDL in ConvictionMetricsRepository handles all migrations.

Add this safety comment above the line:
# SAFETY: 'validate' not 'update' — schema mutations are handled by @PostConstruct DDL only.
# Never use 'update' in production: it can silently drop columns that Hibernate thinks are orphaned.
```

---

## Part 2 — The Missing Performance Review

This is the biggest UX gap. There is no view showing how the portfolio has performed over time. `portfolio_snapshot` exists but nothing consumes it on the frontend.

### 2.1 — New backend endpoint: Portfolio performance history

**Gemini prompt — new method in `DashboardController.java`:**
```
Add a new endpoint GET /dashboard/performance/{pan} to DashboardController.

Create a new DTO: PortfolioPerformanceDTO.java in dashboard/dto:

public record PortfolioPerformanceDTO(
    List<SnapshotPoint> history,          // Daily portfolio value + invested
    List<BenchmarkPoint> niftyHistory,    // Nifty 50 normalized to same start
    double totalReturn,                   // (currentValue - totalInvested) / totalInvested * 100
    double xirr,                          // From DashboardSummaryDTO
    PeriodReturns periodReturns,          // 1M, 3M, 6M, 1Y, 3Y, ITD
    double alphaPct,                      // XIRR - benchmark XIRR
    double totalGainRs,                   // currentValue - totalInvested
    double sipContributionRs,             // Total SIP deployed
    double marketGainRs                   // totalGain - sipContributions
) {}

public record SnapshotPoint(String date, double value, double invested) {}
public record BenchmarkPoint(String date, double normalizedValue) {}
public record PeriodReturns(
    double oneMonth, double threeMonth, double sixMonth,
    double oneYear, double threeYear, double itd
) {}

In PortfolioFullService, add:
public PortfolioPerformanceDTO getPerformanceHistory(String pan) {
    // 1. Fetch portfolio_snapshot rows ordered by date ASC
    List<Map<String, Object>> snapshots = jdbcTemplate.queryForList("""
        SELECT snapshot_date, total_value, total_invested
        FROM portfolio_snapshot
        WHERE pan = ?
        ORDER BY snapshot_date ASC
        """, pan);

    List<SnapshotPoint> history = snapshots.stream().map(r -> new SnapshotPoint(
        r.get("snapshot_date").toString(),
        ((Number) r.get("total_value")).doubleValue(),
        ((Number) r.get("total_invested")).doubleValue()
    )).toList();

    // 2. Fetch Nifty 50 history for the same date range, normalize to 100 at first snapshot date
    // Use the first snapshot date as anchor
    if (!history.isEmpty()) {
        String fromDate = history.get(0).date();
        List<Map<String, Object>> niftyRows = jdbcTemplate.queryForList("""
            SELECT date, closing_price
            FROM index_fundamentals
            WHERE index_name = 'NIFTY 50'
            AND date >= ?::date
            ORDER BY date ASC
            """, fromDate);

        // Normalize to 100 at start
        double firstClose = niftyRows.isEmpty() ? 1.0 :
            ((Number) niftyRows.get(0).get("closing_price")).doubleValue();
        
        List<BenchmarkPoint> niftyHistory = niftyRows.stream().map(r ->
            new BenchmarkPoint(
                r.get("date").toString(),
                ((Number) r.get("closing_price")).doubleValue() / firstClose * 100
            )
        ).toList();

        // 3. Compute period returns from snapshots
        PeriodReturns periods = computePeriodReturns(history);
        
        // 4. Compute alpha (XIRR from dashboard - benchmark 1yr return)
        double xirr = ... // from dashboardService.getInvestorSummary(pan).getOverallXirr()
        double benchmarkXirr = benchmarkService.getBenchmarkReturn("", "", "NIFTY 50");
        double alpha = xirr - benchmarkXirr;

        // 5. Total gain breakdown
        double currentValue = history.isEmpty() ? 0 : history.get(history.size()-1).value();
        double totalInvested = history.isEmpty() ? 0 : history.get(history.size()-1).invested();
        double totalGain = currentValue - totalInvested;

        return new PortfolioPerformanceDTO(
            history, niftyHistory, 
            totalInvested > 0 ? (totalGain / totalInvested) * 100 : 0,
            xirr, periods, alpha, totalGain,
            totalInvested, // SIP contribution proxy
            totalGain // market gain
        );
    }
    
    return new PortfolioPerformanceDTO(List.of(), List.of(), 0, 0, null, 0, 0, 0, 0);
}

// Period returns: compare latest snapshot value to the snapshot N days ago
private PeriodReturns computePeriodReturns(List<SnapshotPoint> history) {
    double latest = history.isEmpty() ? 0 : history.get(history.size()-1).value();
    return new PeriodReturns(
        findReturn(history, latest, 30),
        findReturn(history, latest, 90),
        findReturn(history, latest, 180),
        findReturn(history, latest, 365),
        findReturn(history, latest, 1095),
        history.size() > 1 ? (latest / history.get(0).value() - 1) * 100 : 0
    );
}

private double findReturn(List<SnapshotPoint> h, double latest, int daysBack) {
    LocalDate target = LocalDate.now().minusDays(daysBack);
    return h.stream()
        .filter(p -> !LocalDate.parse(p.date()).isAfter(target))
        .reduce((a, b) -> b)
        .map(p -> p.value() > 0 ? (latest / p.value() - 1) * 100 : 0.0)
        .orElse(0.0);
}

Map endpoint in DashboardController:
@GetMapping("/dashboard/performance/{pan}")
public PortfolioPerformanceDTO getPerformance(@PathVariable String pan) {
    return portfolioFullService.getPerformanceHistory(pan);
}
```

---

### 2.2 — New frontend: Performance view

**Gemini prompt — new file `portfolio-dashboard/src/components/views/PerformanceView.tsx`:**
```
Create PerformanceView.tsx. This is a new tab "Performance" (add it between Portfolio 
and Each Fund in Dashboard.tsx). Use @nivo/line for all charts.

SECTION 1 — Hero numbers row (4 cards):
  1. Total Return % (colorized green/red)
  2. XIRR (annualised)  
  3. Alpha vs Nifty (XIRR - Nifty 1yr return)
  4. Total Gain ₹ (market gain, not SIP contribution)

SECTION 2 — Portfolio vs Nifty 50 Growth Chart:
  Title: "Growth of ₹100 invested since inception"
  X-axis: date
  Y-axis: normalized value (starts at 100 for both on first data point)
  Line 1: "Portfolio" — purple (#cba6f7)
  Line 2: "Nifty 50" — dimmed green (#a6e3a1, opacity 0.5)
  Shaded area between lines: green if portfolio > benchmark, red if below
  
  Show at top right: "Alpha: +X.X% p.a." badge (green if positive)

  Data: fetched from GET /api/dashboard/performance/{pan}
  
  Use ResponsiveLine from @nivo/line. Normalise both series to 100 at their
  first data point so they're visually comparable regardless of absolute values.

SECTION 3 — Period Returns Table:
  A clean table showing: 1M | 3M | 6M | 1Y | 3Y | Since Inception
  Each cell: colored text (green if positive) + small "vs Nifty" delta in gray
  
  Row 1: Your portfolio
  Row 2: Nifty 50
  Row 3: Alpha (portfolio - nifty, same period)

SECTION 4 — Contribution Breakdown:
  A simple stacked bar or two-number display:
  "Of your ₹X total gain: ₹Y came from SIP deployment, ₹Z came from market returns"
  Formula: market gain = current_value - total_invested
  Pie chart (Nivo) showing the split.

Data fetching pattern (same as other views):
  const [perf, setPerf] = useState<any>(null);
  useEffect(() => {
    fetch(`/api/dashboard/performance/${pan}`, {headers: {'X-API-KEY': 'dev-secret-key'}})
      .then(r => r.json()).then(setPerf).catch(console.error);
  }, [pan]);

Add PerformanceView to Dashboard.tsx tabs:
  { id: 'performance', label: 'Performance', icon: <TrendingUp size={14}/> }
  Add it after 'portfolio' tab.
  Render: <PerformanceView portfolioData={portfolioData} pan={investorPan} isPrivate={isPrivate} />

Add fetchPerformanceData to api.ts:
  export const fetchPerformanceData = async (pan: string) => {
    const res = await authenticatedFetch(`${BASE_URL}/dashboard/performance/${pan}`);
    if (!res.ok) return null;
    return res.json();
  };
```

---

## Part 3 — Technology Additions (Honest Assessment)

### Should you add Kafka or RabbitMQ?

**No.** Here's why: both are message brokers designed for async communication between multiple services or to decouple producers and consumers under high throughput. Your system has:
- One Java service
- One Python parser
- One PostgreSQL database
- One user loading the dashboard

The nightly pipeline is already sequential and runs at 7:30pm. The bottleneck is not message delivery — it's computation time (Hurst, OU, HMM fitting). Adding a broker would add operational complexity (another container, dead-letter queues, consumer groups) with zero benefit.

**What you should add instead: Spring WebSocket for nightly engine progress**

The current `QuantitativeEngineService` has `AtomicInteger currentStep` and `lastStatusMessage` — these are poll-based. The admin UI has to poll `/admin/status` repeatedly. Replace with a WebSocket push:

### 3.1 — Spring WebSocket for live engine progress

**Gemini prompt — add WebSocket:**
```
Add Spring WebSocket STOMP support to the Java backend.

1. In pom.xml, add:
   <dependency>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-starter-websocket</artifactId>
   </dependency>

2. Create WebSocketConfig.java in core/config:
   @Configuration
   @EnableWebSocketMessageBroker
   public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
     @Override
     public void configureMessageBroker(MessageBrokerRegistry registry) {
       registry.enableSimpleBroker("/topic");
       registry.setApplicationDestinationPrefixes("/app");
     }
     @Override
     public void registerStompEndpoints(StompEndpointRegistry registry) {
       registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
     }
   }

3. In QuantitativeEngineService.java, inject SimpMessagingTemplate:
   private final SimpMessagingTemplate messagingTemplate;
   
   After every step update, broadcast:
   messagingTemplate.convertAndSend("/topic/engine-progress", 
     Map.of("step", currentStep.get(), "message", lastStatusMessage, "total", 7));

4. In the React frontend (CasUploadView.tsx or a new AdminView.tsx), connect:
   import SockJS from 'sockjs-client';
   import { Client } from '@stomp/stompjs';
   
   const client = new Client({
     webSocketFactory: () => new SockJS('/api/ws'),
   });
   client.onConnect = () => {
     client.subscribe('/topic/engine-progress', (msg) => {
       const data = JSON.parse(msg.body);
       setEngineProgress(data);
     });
   };
   client.activate();

   In package.json dependencies add:
   "@stomp/stompjs": "^7.0.0"
   "sockjs-client": "^1.6.1"

   Show a progress bar: steps 1-7 with labels. When step=7, show "Complete ✓".
   This replaces the current polling approach completely.
```

---

### 3.2 — Spring Scheduling: `@Async` to parallelize independent nightly steps

Steps 5 (Hurst), 6 (OU), 7 (HMM) in `QuantitativeEngineService` are all independent after loading the returns cache. They currently run sequentially. For 150+ funds, this adds up.

**Gemini prompt:**
```
In QuantitativeEngineService.java, parallelize steps 5, 6, 7 using CompletableFuture.

1. Add @EnableAsync to CasInjectorApplication.java or a new AsyncConfig.java:
   @Configuration
   @EnableAsync
   public class AsyncConfig {
     @Bean(name = "mathEngineExecutor")
     public Executor taskExecutor() {
       ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
       exec.setCorePoolSize(3);
       exec.setMaxPoolSize(3);
       exec.setThreadNamePrefix("math-engine-");
       exec.initialize();
       return exec;
     }
   }

2. In QuantitativeEngineService, replace the sequential steps 5/6/7 with:
   lastStatusMessage = "Running Hurst, OU and HMM in parallel...";
   currentStep.set(5);
   
   CompletableFuture<Void> hurstFuture = CompletableFuture.runAsync(
     () -> hurstExponentService.computeAndPersistHurstMetrics(returnsCache, navsCache),
     taskExecutor
   );
   CompletableFuture<Void> ouFuture = CompletableFuture.runAsync(
     () -> ouService.computeAndPersistOUMetrics(returnsCache),
     taskExecutor
   );
   CompletableFuture<Void> hmmFuture = CompletableFuture.runAsync(
     () -> hmmRegimeService.computeAndPersistHmmStates(returnsCache),
     taskExecutor
   );
   
   CompletableFuture.allOf(hurstFuture, ouFuture, hmmFuture).join();
   currentStep.set(7);
   lastStatusMessage = "All quantitative steps complete.";

   Inject the executor: @Qualifier("mathEngineExecutor") private final Executor taskExecutor;

This reduces the nightly engine wall-clock time by roughly 40-60% for the compute steps.
```

---

### 3.3 — `@Cacheable` on `DashboardService` needs eviction after snapshot

Currently there's no link between the nightly snapshot completion and cache eviction. The dashboard shows stale data until the 10-minute TTL expires.

**Gemini prompt:**
```
In MetricsSchedulerService.java, after snapshotAllPortfolios() completes, 
evict the dashboard cache so the next request gets fresh data:

Inject CacheManager cacheManager;

After the forEach loop in snapshotAllPortfolios():
  Cache cache = cacheManager.getCache("dashboardSummaryV3");
  if (cache != null) {
    cache.clear();
    logger.info("🗑️ Dashboard cache cleared after snapshot — next load will be fresh.");
  }
```

---

## Part 4 — Logic Completeness Gaps in RebalanceEngine

### 4.1 — `resolveStatus()` only has 3 states but funds can be in 4 meaningful states

```java
// CURRENT — misses the case where fund is on strategy but has ZERO actual holding
if (targetPct == 0.0 && sipPct == 0.0 && actualPct > 0.0) return "DROPPED";
if (sipPct > 0.0 && actualPct < targetPct) return "ACCUMULATOR";
return "ACTIVE";
```

A fund with `targetPct > 0` but `actualPct == 0` is in a "NEW_ENTRY" state — it exists on the strategy sheet but hasn't been bought yet. The engine currently evaluates it as ACTIVE with 100% underweight, which triggers a maximum BUY signal. This is correct in direction but the justification language is misleading ("Underweight by 8.00%").

**Gemini prompt:**
```
In RebalanceEngine.java, update resolveStatus() to handle 4 states:

private String resolveStatus(double targetPct, double sipPct, double actualPct, double originalSheetPct) {
    if (originalSheetPct == 0.0 && sipPct == 0.0 && actualPct > 0.0) return "DROPPED";
    if (targetPct > 0.0 && actualPct == 0.0) return "NEW_ENTRY";
    if (sipPct > 0.0 && actualPct < targetPct) return "ACCUMULATOR";
    return "ACTIVE";
}

In the BUY justification builder, handle NEW_ENTRY:
if ("NEW_ENTRY".equals(status)) {
    justifications.add("New Position: This fund is on your strategy but not yet purchased. " +
        "Initial entry signal — deploy via SIP or lumpsum.");
}

Update the call in evaluate() to pass originalSheetPct.
```

---

### 4.2 — `weightSignalsByConviction()` in `PortfolioOrchestrator` double-counts Kelly sizing

Looking at the flow:
1. `RebalanceEngine` already sets `amount` = drift gap in rupees (from `diffAmount`)
2. `PositionSizingService.calculateExecutionAmount()` is then called in `computeOpportunisticSignals()` which multiplies the amount by the Kelly multiplier
3. Then `weightSignalsByConviction()` further scales the amount by `(baseDriftAmount * scoreMult / totalDemand) * cash`

The final amount has been through three sizing adjustments: drift gap → Kelly → conviction weighting. The conviction weighting in step 3 can reduce amounts below the Kelly floor from step 2, making the Kelly sizing meaningless.

**Gemini prompt:**
```
In PortfolioOrchestrator.java, in the weightSignalsByConviction() method,
change the allocation logic to use conviction weighting INSTEAD of re-scaling
the Kelly amount, not on top of it:

// CURRENT (wrong — triple-scales)
double allocatedAmount = totalDemand > 0.0 
    ? (baseAmount * scoreMult / totalDemand) * cash 
    : baseAmount;

// FIXED — only apply conviction weighting if cash is constrained
// If cash >= totalDemand, every fund gets its full Kelly amount
// If cash < totalDemand, scale proportionally by conviction
double allocatedAmount;
if (cash >= totalDemand || totalDemand == 0) {
    allocatedAmount = baseAmount; // Enough cash — everyone gets their Kelly amount
} else {
    double convictionShare = scoreMult / activeDrafts.stream()
        .filter(s -> SignalType.BUY == s.action())
        .mapToDouble(s -> Math.max(0.2, s.convictionScore() / 100.0))
        .sum();
    allocatedAmount = convictionShare * cash; // Pro-rata by conviction
}
```

---

## Part 5 — UI Overhaul: Performance Tab + Chart Fixes

### 5.1 — Add the Performance tab to Dashboard.tsx

**Gemini prompt:**
```
In Dashboard.tsx:

1. Import PerformanceView from '../views/PerformanceView'

2. Add to the tabs array (between portfolio and funds):
   { id: 'performance', label: 'Performance', icon: <TrendingUp size={14}/> },

3. In the tab content switch/render area, add:
   case 'performance':
     return <PerformanceView portfolioData={portfolioData} pan={investorPan} isPrivate={isPrivate} />;

4. In the header stats row, change 'P&L' stat to show the portfolio's ITD return
   in percentage instead of rupees — rupee amount is already prominent on the dashboard:
   { label: 'Return', value: portfolioData.overallReturn || '0%', 
     color: parseFloat(portfolioData.overallReturn || '0') >= 0 ? 'text-buy' : 'text-exit' }
```

---

### 5.2 — FundDetailView chart: the normalized comparison is correct, but labeling is wrong

In `FundDetailView.tsx`, the chart normalizes both fund and benchmark to 100 at the start date. This is the correct way to compare. However:
- The Y-axis label is missing ("Indexed to 100")
- The tooltip shows raw index values without explanation
- The start date is the first data point in the history, not the user's investment start date

**Gemini prompt:**
```
In FundDetailView.tsx, update the normalizedHistory useMemo to:

1. Find the user's first buy date from `fund.lastBuyDate` — use that as the
   anchor point for normalization, not the first data point in the series.
   This shows "how has the fund performed SINCE YOU BOUGHT IT vs the benchmark?"
   
   const userBuyDate = fund.lastBuyDate; // ISO string "2023-04-01"
   const fData = [...history.fund]
     .filter(d => d.navDate >= userBuyDate)  // Only since user bought
     .reverse();

2. Update the chart title from "NAV vs Benchmark" to:
   "Performance since first purchase · Indexed to 100 at entry"

3. Add a custom tooltip to ResponsiveLine that shows:
   - Fund: +X% since entry (fund value - 100)
   - Benchmark: +X% since entry
   - Your Alpha: +X% (fund return - benchmark return)

4. Add axis labels:
   axisLeft: { legend: 'Indexed return (100 = entry price)', legendPosition: 'middle' }
```

---

### 5.3 — PortfolioView.tsx: Scatter chart is missing axis labels and quadrant context

**Gemini prompt:**
```
In PortfolioView.tsx, the ScatterChart currently shows X=convictionScore, Y=maxDrawdown.
This is hard to interpret without context. Update it:

1. Add quadrant reference lines:
   <ReferenceLine x={50} stroke="rgba(255,255,255,0.1)" label="Avg conviction" />
   <ReferenceLine y={15} stroke="rgba(255,255,255,0.1)" label="Typical DD" />

2. Add axis titles:
   XAxis: label={{ value: 'Conviction score →', position: 'insideBottom', offset: -5 }}
   YAxis: label={{ value: '↑ Max drawdown %', angle: -90, position: 'insideLeft' }}

3. Add 4 quadrant labels (as SVG text via customized dot rendering):
   Top-left:    "High pain, low conviction (EXIT candidates)"
   Top-right:   "High pain, high conviction (hold with care)"
   Bottom-left: "Safe but low conviction (review needed)"
   Bottom-right: "Low pain, high conviction (IDEAL)"

4. Make each scatter dot clickable — call onFundClick(dot.name) to open FundDetailView.
   Pass onFundClick as a prop from PortfolioView down to the scatter section.
```

---

## Part 6 — New Spring Dependencies Worth Adding

### 6.1 — Spring Validation (`@Valid`) for all controller inputs

Currently there is zero input validation. If `pan` is null or `sip` is negative, the system silently processes it wrong.

**Gemini prompt:**
```
In pom.xml, add:
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

In DashboardController.java, add @Validated to the class and @NotBlank to all
@PathVariable String pan parameters.

Create a PortfolioRequest DTO for POST endpoints with:
  @NotBlank @Pattern(regexp = "[A-Z]{5}[0-9]{4}[A-Z]{1}", message = "Invalid PAN format")
  String pan;
  
  @Min(0) @Max(10000000)
  double monthlySip;

In GlobalExceptionHandler.java, add a handler for MethodArgumentNotValidException:
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiError handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage())
        .collect(Collectors.joining(", "));
    return new ApiError(400, message);
  }
```

---

### 6.2 — Spring Retry for Google Sheet fetches

`GoogleSheetService.fetchLatestStrategy()` calls a Google Sheets CSV URL. If it fails once (network hiccup, Google rate limit), every request for the duration of the cache TTL fails.

**Gemini prompt:**
```
In pom.xml, add:
<dependency>
  <groupId>org.springframework.retry</groupId>
  <artifactId>spring-retry</artifactId>
</dependency>

Add @EnableRetry to CasInjectorApplication.java.

In GoogleSheetService.java, annotate fetchLatestStrategy():
  @Retryable(
    retryFor = { Exception.class },
    maxAttempts = 3,
    backoff = @Backoff(delay = 2000, multiplier = 2)
  )
  public List<StrategyTarget> fetchLatestStrategy() { ... }
  
  @Recover
  public List<StrategyTarget> fetchLatestStrategyFallback(Exception e) {
    log.error("Google Sheet unavailable after 3 retries: {}. Returning empty strategy.", e.getMessage());
    return Collections.emptyList();
  }

This prevents cascade failures when Google Sheets is briefly unavailable.
```

---

### 6.3 — Spring Actuator metrics exposure (already in pom, not configured)

`spring-boot-starter-actuator` is in `pom.xml` but not configured. The healthcheck in `docker-compose.yml` hits `/api/admin/status` — a custom endpoint. Use Actuator's built-in `/actuator/health` instead.

**Gemini prompt:**
```
In application.properties, add:
management.endpoints.web.exposure.include=health,info,metrics,caches
management.endpoint.health.show-details=when_authorized
management.endpoint.caches.enabled=true

# Liveness and readiness for Kubernetes/Docker readiness
management.endpoint.health.probes.enabled=true
management.health.livenessState.enabled=true
management.health.readinessState.enabled=true

In docker-compose.yml, change the java-backend healthcheck to:
test: ["CMD", "curl", "-f", "http://localhost:8080/api/actuator/health"]

Now you can also expose cache stats — useful for debugging:
GET /api/actuator/caches  → shows all Caffeine caches and their hit rates
GET /api/actuator/metrics → shows JVM, HTTP, and custom metrics
```

---

## Part 7 — What NOT to Add (with reasoning)

| Technology | Verdict | Why |
|---|---|---|
| **Kafka / RabbitMQ** | Skip | You have 1 producer (scheduler), 1 consumer (DB write). Message broker adds 2 containers and operational overhead with zero throughput benefit for this use case. |
| **Redis** | Skip | Caffeine already does in-process caching well. Redis adds a container, serialization overhead, and network latency for what is currently a single-JVM application. Only add if you ever run 2+ JVM replicas. |
| **Elasticsearch** | Skip | Your search is against ~20 fund names. Postgres full-text is more than sufficient. |
| **GraphQL** | Skip | Your data model is well-defined. GraphQL solves the over-fetching problem for complex object graphs. `DashboardSummaryDTO` is your single query result — no benefit. |
| **DRL / RL Agent** | Skip | Needs thousands of simulated episodes. Your 20-fund monthly portfolio does not have the episode density to train anything meaningful. The quant engine you have (OU + HMM + HRP) is already research-grade. |
| **Mobile app** | Skip | Use case is "review once a week". A PWA (Progressive Web App) from your existing React app gives installable mobile access with zero additional code: add a `manifest.json` and a service worker. |

---

## Part 8 — Final Verification Checklist for Gemini

```
After all changes, run these checks:

1. SQL correctness: The new BenchmarkService SQL uses ? placeholders for named params.
   Verify: jdbcTemplate.queryForObject(sql, Double.class, targetIndex, targetIndex)
   has exactly 2 bind params matching the 2 ? in the WITH clause. 

2. WebSocket CORS: WebSocketConfig allows all origins (*). Before any deployment
   that's not localhost, change to:
   registry.addEndpoint("/ws").setAllowedOrigins("http://localhost", "https://yourdomain.com")

3. portfolio_snapshot table must exist before MetricsSchedulerService runs.
   Add to ConvictionMetricsRepository.ensureColumnsExist():
   jdbcTemplate.execute("""
     CREATE TABLE IF NOT EXISTS portfolio_snapshot (
       pan VARCHAR(20) NOT NULL,
       snapshot_date DATE NOT NULL,
       total_value DOUBLE PRECISION DEFAULT 0,
       total_invested DOUBLE PRECISION DEFAULT 0,
       PRIMARY KEY (pan, snapshot_date)
     )
   """);

4. CompletableFuture thread pool: the mathEngineExecutor has 3 threads (one per
   parallel step). Verify no step's DB operations conflict — all three write to
   different columns (hurst_*, ou_*, hmm_*) of the same row. This is safe because
   Postgres row-level locking allows concurrent column updates to the same row from
   different transactions as long as they update different columns.

5. TypeScript: add to signals.ts:
   rollingZScore252: number;
   historicalRarityPct: number;
   hmmState: string;
   hmmBullProb: number;
   to the SchemePerformanceDTO interface (already in Java DTO but verify frontend type).

6. SOCKJS: add to package.json:
   "@stomp/stompjs": "^7.0.0",
   "sockjs-client": "^1.6.1"
   Run: cd portfolio-dashboard && npm install

7. Run mvn compile in cas-injector to verify all changes compile.

8. Spring Retry @EnableRetry must be on a @Configuration or @SpringBootApplication class.
   Confirm CasInjectorApplication.java has @EnableRetry added.

Report: list each check with PASS / FAIL / FIXED.
```

---

## Summary Priority Table

| Priority | Task | Effort | Impact |
|---|---|---|---|
| 🔴 Critical | Fix `ZScoreBar` replacing meaningless `PriceZoneBar` | 30 min | High — this chart is misleading every user |
| 🔴 Critical | Fix `BenchmarkService` SQL for correct 1yr return | 15 min | High — wrong benchmark numbers |
| 🔴 Critical | Fix snapshot to save fresh data not cached data | 20 min | High — performance history would be wrong |
| 🟠 High | Add `portfolio_snapshot` table + `/dashboard/performance` endpoint | 2h | High — enables the missing Performance view |
| 🟠 High | Build `PerformanceView.tsx` + add to Dashboard | 2h | High — biggest UX gap |
| 🟠 High | Fix `resolveStatus()` NEW_ENTRY state | 30 min | Medium — better signal quality |
| 🟡 Medium | Spring WebSocket for engine progress | 1h | Medium — better UX for nightly run |
| 🟡 Medium | Parallelize math engine steps 5-7 | 30 min | Medium — faster nightly runs |
| 🟡 Medium | Spring Validation on controllers | 30 min | Medium — safety |
| 🟡 Medium | Spring Retry on GoogleSheetService | 20 min | Medium — resilience |
| 🟢 Low | Actuator health endpoint config | 15 min | Low — ops hygiene |
| 🟢 Low | Fix Kelly double-sizing in `weightSignalsByConviction` | 30 min | Low — correctness |
| 🟢 Low | Fix FundDetailView chart since-user-entry anchoring | 30 min | Low — better UX |
