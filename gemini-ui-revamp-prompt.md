# Gemini CLI Prompt — Portfolio Dashboard Full UI Revamp

You are rebuilding the React frontend of a personal mutual fund portfolio tracker.
Read every section before writing a single line of code.

---

## Guiding principle

This app has one job: help one investor make confident, well-understood decisions.
Every screen must answer a specific question. If a screen does not answer a clear question,
it should not exist.

The current UI is broken in two ways:
1. It shows data without answering "so what?" — conviction scores appear with no explanation.
2. The tab names are internal jargon ("Alignment", "Matrix") — a user should not need
   to learn the system to navigate it.

The new UI is built around **decision support, not data display.**

---

## Tech stack (do not change these)

- React 19 + TypeScript + Vite
- Tailwind CSS v4
- Framer Motion (already installed)
- Recharts (already installed)
- Lucide React icons (already installed)
- shadcn/ui components (already installed)
- The `api.ts` service layer — keep all existing API calls intact.

**New packages to install:**
```
npm install @radix-ui/react-tooltip @radix-ui/react-dialog @radix-ui/react-progress
```
These are already part of the Radix ecosystem in the project — just add these three
primitives for tooltips, modals, and progress bars.

---

## Design language

**Colors (strictly semantic — use these and only these):**
```
Background:        #09090f  (root bg)
Surface:           #0f0f18  (cards, panels)
Surface elevated:  #14141f  (nested cards, inputs)
Border subtle:     rgba(255,255,255,0.05)
Border default:    rgba(255,255,255,0.08)

Buy / positive:    #34d399  (emerald-400 equivalent)
Exit / negative:   #f87171  (red-400 equivalent)
Hold / neutral:    #94a3b8  (slate-400 equivalent)
Warning / amber:   #fbbf24  (amber-400 equivalent)
Accent / indigo:   #818cf8  (indigo-400 equivalent)

Text primary:      #f1f5f9  (slate-100)
Text secondary:    rgba(241,245,249,0.5)
Text muted:        rgba(241,245,249,0.25)
```

**Typography:**
- Font: Geist Variable (already in `@fontsource-variable/geist`) — import it in `index.css`
- Numbers and values: `font-variant-numeric: tabular-nums` on all financial figures
- Labels above values: 9–10px, uppercase, letter-spacing 0.1em, text-muted color
- Main values: 18–24px, font-weight 500
- Fund names: 13px, font-weight 400, truncate with `truncate` class

**Component rules:**
- Cards: `bg-[#0f0f18] border border-white/5 rounded-xl` — no `rounded-3xl` or `rounded-[3rem]`
- No blur effects (`backdrop-blur`) on anything except the sticky topbar
- No `shadow-2xl`, no `glow` effects
- Hover states: `hover:border-white/10 hover:bg-white/[0.02]` — subtle, not dramatic
- Transitions: `transition-colors duration-150` only — no spring animations on data cards
- Use Framer Motion only for: page transitions, the fund detail modal sliding in, the
  today-brief cards staggering in on load. Remove all other framer-motion usage.

**Tooltips on every metric:** Every score, ratio, or percentage that is not self-evident
must have a Radix Tooltip showing a one-sentence plain-English explanation on hover.
Examples:
- Conviction score → "A 0–100 score combining your personal return, fund risk profile,
  and current valuation. Higher = stronger case to hold or buy more."
- Sortino ratio → "Measures return relative to downside risk only. Above 1.0 is good."
- NAV percentile → "Where today's NAV sits within the past 3 years. 20% means near a
  3-year low — a potential entry opportunity."
- Max drawdown → "The worst peak-to-trough fall in the past 3 years."
- CVaR → "Expected loss on the worst 5% of trading days."

---

## App structure

Replace the current tab system entirely. New structure:

```
App.tsx
  └── Dashboard.tsx  (layout shell with topbar + nav)
       ├── TodayBriefView.tsx      (default/home tab)
       ├── PortfolioView.tsx       (replaces OverviewTab)
       ├── FundDetailView.tsx      (replaces HoldingsTab — full screen per fund)
       ├── RebalanceView.tsx       (replaces DeviationTab)
       ├── TaxView.tsx             (replaces part of TacticalPanel)
       └── LedgerView.tsx          (keeps TransactionTab logic, new skin)
```

Navigation tab labels (plain English, no jargon):
```
Today | Portfolio | Each Fund | Rebalance | Tax | Ledger
```

---

## View 1: TodayBriefView.tsx  (the home screen)

**Question it answers:** "What should I do today and why?"

**Layout:**

```
┌─────────────────────────────────────────────────────────┐
│ DATE HEADER  "Today's brief · 5 Apr 2026"               │
├─────────────────────────────────────────────────────────┤
│ ACTION CARDS (horizontal scroll on mobile, grid on lg)  │
│  ┌─────────────────┐  ┌──────────────────┐             │
│  │ [BUY]           │  │ [EXIT]           │             │
│  │ Parag Parikh    │  │ SBI Contra       │             │
│  │ ₹12,400         │  │ ₹38K harvestable │             │
│  │ ─────────────── │  │ ─────────────── │             │
│  │ Plain English   │  │ Plain English   │             │
│  │ reason (2 lines)│  │ reason          │             │
│  └─────────────────┘  └──────────────── ┘             │
├─────────────────────────────────────────────────────────┤
│ SIP DEPLOYMENT CALCULATOR                               │
│  SIP amount: [slider 0–200K]  Lumpsum: [input]         │
│  ────────────────────────────────────────────────────── │
│  Fund name        Conviction   Allocation   Amount      │
│  Parag Parikh     82           16.4%        ₹12,300     │
│  HDFC Midcap      74           14.8%        ₹11,100     │
│  ...                                                    │
├─────────────────────────────────────────────────────────┤
│ HOLDS: 18 funds — nothing to do                        │
└─────────────────────────────────────────────────────────┘
```

**Action cards — implementation details:**

Each BUY or EXIT signal from `rawSignals` becomes an action card.
HOLD signals do NOT get individual cards — they appear as a collapsed count at the bottom.

Card anatomy:
```tsx
<div className="border border-emerald-500/20 bg-emerald-500/[0.03] rounded-xl p-4">
  <div className="flex items-center justify-between mb-3">
    <span className="text-[9px] font-medium uppercase tracking-widest text-emerald-400">Buy</span>
    <ConvictionBadge score={signal.convictionScore} />  {/* score pill */}
  </div>
  <p className="text-sm font-medium text-slate-100 mb-1 truncate">{signal.schemeName}</p>
  <p className="text-xl font-medium tabular-nums text-emerald-400 mb-3">{formatCurrency(signal.displayAmount)}</p>
  <p className="text-[11px] text-slate-400 leading-relaxed">{buildPlainEnglishReason(signal)}</p>
</div>
```

`buildPlainEnglishReason(signal)` is a utility function that converts the backend's
`justifications` array into a single readable sentence. Logic:
- If action is BUY: "Currently at [navPercentile]% of its 3-year NAV range. You're
  [deviation]% underweight your target. Conviction score: [score]/100."
- If action is EXIT: "Removed from strategy. [If LTCG > 0: ₹X in tax-free LTCG units
  ready to harvest.] [If STCG > 0: ₹Y in STCG locked — selling [N] more days.]"
- Strip the emoji and jargon from the raw `justifications` strings.

**SIP deployment calculator:**
- SIP slider: `min=0 max=200000 step=1000`
- Lumpsum: number input
- Table rows come from `rawSignals.filter(s => s.action === 'BUY')`
- Allocation % and amount recalculate live as sliders change
- Each row has a `<Tooltip>` on conviction score
- Use the same proportional allocation logic already in `TacticalPanel.tsx` —
  move it here and keep it

---

## View 2: PortfolioView.tsx

**Question it answers:** "How is my portfolio doing overall?"

**Layout — four sections stacked:**

**Section A — four stat cards in a row:**
Portfolio value | XIRR | Unrealised P&L | Tax exposure (STCG)

**Section B — allocation by bucket (horizontal bar chart):**
Each bucket (AGGRESSIVE_GROWTH, SAFE_REBALANCER, GOLD_HEDGE, DEBT_SLAB) as a labelled
horizontal bar. Target allocation shown as a thin vertical line on the bar.
Use Recharts `BarChart` horizontal layout.
Color-code by bucket (use the `BUCKET_COLORS` from the existing `OverviewTab.tsx`).

**Section C — four health gauges in a 2×2 grid:**
Each gauge is a label + progress track + current value.

| Gauge | Source | Good direction |
|---|---|---|
| Drift index | `totalDriftMagnitude` | Lower is better (amber if >5, red if >10) |
| Portfolio CVaR | weighted average `cvar5` | Higher (less negative) is better |
| Avg conviction | mean of all `convictionScore` | Higher is better |
| Tax efficiency | LTCG / (LTCG + STCG) | Higher is better |

Each gauge label has a Tooltip explaining what it means.

**Section D — XIRR comparison bar chart:**
For each fund with `currentValue > 0`, show three bars side by side:
- Your personal XIRR (from `schemeBreakdown[].xirr`)
- Category average (hardcode reasonable benchmarks: Nifty 50 CAGR 14%, Midcap 18%, etc.)
- Sorted by your XIRR descending
Use Recharts `BarChart` with `layout="vertical"` so fund names fit.
Use short names (first 20 chars).

---

## View 3: FundDetailView.tsx

**Question it answers:** "Tell me everything about this one fund."

This is a **full-screen slide-over panel** (not a tab navigation — it's a modal that
slides in from the right when you click any fund name anywhere in the app).

Trigger: clicking a fund name in `TodayBriefView`, `PortfolioView`, or `RebalanceView`
sets a `selectedFund` state in `Dashboard.tsx`, which renders `FundDetailView`.

**Layout inside the slide-over:**

**Header row:**
Fund name (full) | Category badge | Action badge (BUY/HOLD/EXIT) | Conviction score (large)

**Section A — NAV position (the key valuation visual):**
A horizontal track showing the 3-year NAV range (min → max) with:
- A vertical line at `min` labeled "3yr low"
- A vertical line at `max` labeled "3yr high"  
- A filled dot at current NAV position
- Text below: "Currently at [navPercentile]% of its 3-year range"
- A second row showing drawdown from ATH: "Down [X]% from all-time high"

This is the single most important visual — it shows intuitively where the fund is in its
cycle. Use a simple SVG element (not a charting library) since it's a 1D track.

**Section B — Conviction score breakdown (5 horizontal gauges):**
One row per component, showing the sub-score and its weight:

| Component | Score | Weight | What it measures |
|---|---|---|---|
| Yield | [0–100] | 20% | Your personal CAGR |
| Risk | [0–100] | 25% | Sortino ratio |
| Value | [0–100] | 25% | NAV position + drawdown from ATH |
| Pain | [0–100] | 15% | Max drawdown resilience |
| Friction | [0–100] | 15% | Tax drag |

Each row: label + gauge bar + sub-score number + Tooltip explaining the component.

**Section C — Your holdings summary:**
Current value | Units held | Avg cost NAV | Personal XIRR | Unrealised P&L

**Section D — Tax lot timeline:**
A horizontal timeline showing each open tax lot as a dot.
- X axis: buy date
- Dot color: red if < 365 days old (STCG), green if ≥ 365 days (LTCG)
- Dot size: proportional to lot value
- Hover tooltip: lot value, buy date, days to LTCG, unrealised gain/loss
- Source: derive from the `justifications` text or add a dedicated API endpoint
  (if lot-level data is not in the current API response, show a note saying
  "lot detail requires API update" and skip this section for now — do NOT fake data)

**Section E — Plain English verdict:**
A single paragraph (generated by `buildPlainEnglishReason` extended for the full detail view)
that reads like a fund manager's note. Example:
"This fund is trading near the lower end of its 3-year NAV range, suggesting a reasonable
entry point. Its Sortino ratio of 1.8 indicates it recovers well from drawdowns. You are
currently 3.2% underweight your target allocation. Conviction score: 82/100 — Buy."

---

## View 4: RebalanceView.tsx

**Question it answers:** "How far is my portfolio from where I want it?"

Keep the divergence chart logic from `DeviationTab.tsx` — it is correct.

**Changes:**
1. Rename the chart title to "Allocation drift — actual vs target"
2. Use cleaner bar colors: `#34d399` for underweight (needs buying), `#f87171` for
   overweight (needs trimming), neutral gray for on-target (within ±1%)
3. Add a "target vs actual" table below the chart showing:
   Fund name | Target % | Actual % | Drift | Suggested action
4. Remove the "Cumulative Drift Index" jargon card — replace with plain:
   "Total drift: 4.2% across your portfolio. 3 funds need rebalancing."
5. Add a SIP simulator at the bottom (same slider as TodayBriefView — they share state
   via a `sipAmount` context or prop lifted to `Dashboard.tsx`)

---

## View 5: TaxView.tsx

**Question it answers:** "What is my tax situation and what can I do about it?"

**Layout:**

**Section A — LTCG progress bar:**
```
Annual LTCG limit: ₹1,25,000
[████████░░░░░░░░░░░░] ₹38,400 used  (30.7%)
"You can harvest ₹86,600 more in LTCG gains tax-free this year."
```
Use a thick progress bar (height 8px). Color: amber if >70%, green if <70%.

**Section B — STCG exposure:**
List of funds with unrealised STCG. For each:
Fund name | STCG amount | Tax at 20% | Days until LTCG | Recommendation
Recommendation logic:
- If days to LTCG < 30: "Wait [N] days to avoid ₹X in STCG tax"
- If days to LTCG > 180: "No rush — LTCG threshold is [N] days away"

**Section C — TLH opportunities:**
Data from the TLH scanner endpoint (if available from `rawSignals` justifications).
For each opportunity:
```
Sell: [Fund A]  →  Buy: [Fund B proxy]
Loss to harvest: ₹8,400  |  Est. tax saving: ₹1,680 (at 20% STCG rate)
```
If no TLH data in current API, show the section header with "No TLH opportunities
identified today" — do NOT show fake data.

**Section D — Full year summary:**
Realised LTCG | Realised STCG | Total tax paid | Remaining LTCG headroom

---

## View 6: LedgerView.tsx

Keep `TransactionTab.tsx` logic entirely.

**Visual changes only:**
1. Remove the "Ledger Synchronization" jargon — title should just be "Transaction history"
2. Replace the chunky rounded pill filter buttons with slim tab-style filters
3. The monthly group headers should show: Month + year | Net invested that month | Count
4. Each transaction row: Date | Fund (truncated) | Type badge | Units | Amount
5. Type badge colors: BUY = emerald/10 text-emerald, SELL = red/10 text-red,
   STAMP_DUTY = slate/10 text-slate

---

## Global layout shell — Dashboard.tsx

**Sticky topbar:**
```
[Logo: "Portfolio OS"]    [₹38.4L  14.2% XIRR  +₹6.1L P&L  ₹42K tax]  [🔒 privacy toggle]
```
- Logo: 10px uppercase, indigo accent
- Stats: 4 numbers, always visible, masked when privacy toggle is on
- Privacy toggle: Radix Switch, masks all rupee amounts with "••••" when on

**Navigation:**
```
[ Today | Portfolio | Each Fund | Rebalance | Tax | Ledger ]
```
- Pill-style nav, NOT the current rounded full-width bar
- Active: indigo/15 background, indigo-400 text
- Inactive: no background, slate-400 text

**Background:**
A single very subtle radial gradient on the root element:
```css
background: radial-gradient(ellipse 80% 50% at 20% 0%, rgba(99,102,241,0.06) 0%, transparent 60%), #09090f;
```
No animated blobs. No `blur-[120px]` divs. This replaces the current atmospheric background.

---

## Utility functions to add/update in `formatters.ts`

```typescript
// Plain English reason builder
export const buildPlainEnglishReason = (signal: any): string => {
  const pct = signal.navPercentile3yr != null
    ? `At ${Math.round(signal.navPercentile3yr * 100)}% of its 3-year NAV range.`
    : '';
  const drift = signal.deviation != null
    ? `${Math.abs(parseFloat(signal.deviation)).toFixed(1)}% ${parseFloat(signal.deviation) < 0 ? 'underweight' : 'overweight'} target.`
    : '';

  if (signal.action === 'BUY') {
    return `${pct} ${drift} Conviction: ${signal.convictionScore}/100.`.trim();
  }
  if (signal.action === 'EXIT') {
    const ltcg = signal.ltcgValue > 0 ? `₹${Math.round(signal.ltcgValue).toLocaleString('en-IN')} in LTCG-eligible units ready.` : '';
    const stcg = signal.stcgValue > 0 ? `₹${Math.round(signal.stcgValue).toLocaleString('en-IN')} in STCG — wait ${signal.daysToNextLtcg} days to avoid tax.` : '';
    return `Removed from strategy. ${ltcg} ${stcg}`.trim();
  }
  return 'Within target range. No action needed.';
};

// Conviction score to color
export const convictionColor = (score: number): string => {
  if (score >= 65) return '#34d399';
  if (score >= 45) return '#fbbf24';
  return '#f87171';
};

// Format as Indian rupee shorthand
export const formatCurrencyShort = (val: number): string => {
  if (val >= 10000000) return `₹${(val / 10000000).toFixed(1)}Cr`;
  if (val >= 100000) return `₹${(val / 100000).toFixed(1)}L`;
  if (val >= 1000) return `₹${(val / 1000).toFixed(0)}K`;
  return `₹${Math.round(val)}`;
};
```

---

## What to DELETE (clean up the dead code)

Remove these files entirely:
- `components/layout/Header.tsx` — replaced by topbar inside `Dashboard.tsx`
- `components/layout/Navigation.tsx` — replaced by new nav inside `Dashboard.tsx`
- `components/cards/SignalCard.tsx` — replaced by action cards in `TodayBriefView.tsx`

Remove these sections from existing files:
- The `atmospheric background` div in `Dashboard.tsx` (the two `blur-[120px]` blobs)
- All `text-[8px]`, `text-[9px]` font sizes — replace with `text-[10px]` minimum
- All `rounded-[3rem]`, `rounded-[2rem]` — replace with `rounded-xl` or `rounded-2xl` max
- All ALL_CAPS labels like `"QUANTUM PORTFOLIO OS"`, `"INTELLIGENCE MATRIX"` —
  replace with normal sentence case
- The jargon in the loading screen (`"Syncing Architectures"`, `"Establishing Secure Uplink"`)
  — replace with `"Loading your portfolio..."` and a simple spinner
- `reboundBonus` logic if still present in frontend calculations

---

## Component: ConvictionBadge

Used everywhere a conviction score appears. Create at
`components/ui/ConvictionBadge.tsx`:

```tsx
export default function ConvictionBadge({ score }: { score: number }) {
  const color = score >= 65 ? 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20'
              : score >= 45 ? 'text-amber-400 bg-amber-400/10 border-amber-400/20'
              : 'text-red-400 bg-red-400/10 border-red-400/20';
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-md border text-[10px] font-medium tabular-nums ${color}`}>
      {score}
    </span>
  );
}
```

---

## Component: MetricWithTooltip

Used for every metric label+value pair with an explanation:

```tsx
import * as Tooltip from '@radix-ui/react-tooltip';

export default function MetricWithTooltip({
  label, value, tooltip, valueClass = ''
}: { label: string; value: string | number; tooltip: string; valueClass?: string }) {
  return (
    <Tooltip.Provider delayDuration={200}>
      <Tooltip.Root>
        <Tooltip.Trigger asChild>
          <div className="cursor-help">
            <p className="text-[10px] uppercase tracking-widest text-slate-500 mb-0.5">{label}</p>
            <p className={`text-lg font-medium tabular-nums text-slate-100 ${valueClass}`}>{value}</p>
          </div>
        </Tooltip.Trigger>
        <Tooltip.Portal>
          <Tooltip.Content className="bg-[#1a1a2e] border border-white/10 text-slate-300 text-xs rounded-lg px-3 py-2 max-w-[220px] leading-relaxed z-50">
            {tooltip}
            <Tooltip.Arrow className="fill-[#1a1a2e]" />
          </Tooltip.Content>
        </Tooltip.Portal>
      </Tooltip.Root>
    </Tooltip.Provider>
  );
}
```

---

## Data contracts — what the API already provides

The API call `fetchMasterPortfolio` now hits `/api/dashboard/full/{pan}` and returns:

```typescript
{
  investorName: string,
  overallXirr: string,
  currentValueAmount: number,
  totalUnrealizedGain: number,
  totalSTCG: number,
  totalLTCG: number,
  totalInvestedAmount: number,
  schemeBreakdown: SchemePerformance[],  // one per fund
  rawSignals: TacticalSignal[],          // one per fund from the rebalancing engine
}
```

`TacticalSignal` now has (after previous refactor):
```typescript
{
  schemeName, action, amount, plannedPercentage, actualPercentage,
  convictionScore, sortinoRatio, maxDrawdown,
  navPercentile3yr, drawdownFromAth, returnZScore,
  lastBuyDate, justifications: string[]
}
```

`SchemePerformance` has:
```typescript
{
  schemeName, isin, category, bucket, benchmarkIndex,
  totalInvested, currentInvested, currentValue,
  realizedGain, unrealizedGain, xirr, status
}
```

Do not add new API calls. If a view needs data not in these contracts, derive it
client-side or show a "data not available" placeholder.

---

## Validation checklist

1. `Today` tab loads and shows BUY/EXIT action cards with plain-English reasons.
   HOLD funds appear only as a collapsed count.
2. Clicking any fund name anywhere opens the `FundDetailView` slide-over.
3. `FundDetailView` shows the NAV position track (the horizontal 3yr range bar).
4. `FundDetailView` conviction breakdown shows all 5 components with tooltips.
5. `TaxView` LTCG progress bar reflects real data from `totalLTCG / 125000`.
6. `RebalanceView` drift bars are green for underweight, red for overweight.
7. SIP slider in `TodayBriefView` and `RebalanceView` share state — changing one
   updates the other (lift `sipAmount` state to `Dashboard.tsx`).
8. Privacy toggle masks all `₹` values with `••••••` across all tabs.
9. No ALL_CAPS labels, no `rounded-[3rem]`, no `blur-[120px]` anywhere in the codebase.
10. Loading screen says "Loading your portfolio..." with a simple spinner.
11. All financial figures use `tabular-nums` and Indian number formatting.
12. Every conviction score, Sortino ratio, and NAV percentile has a Tooltip.
