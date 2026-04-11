import { useState } from 'react';
import { Search, Info } from 'lucide-react';
import LearnTooltip from '../ui/LearnTooltip';
import * as Tooltip from '@radix-ui/react-tooltip';
import { motion } from 'framer-motion';

// ─── helpers ──────────────────────────────────────────────────────────────────

function PriceZoneBar({ percentile }: { percentile: number }) {
  const pct = Math.min(100, Math.max(0, (percentile ?? 0.5) * 100));
  const zone = pct > 70 ? 'Expensive' : pct < 30 ? 'Cheap' : 'Fair';
  const color = pct > 70 ? 'text-exit' : pct < 30 ? 'text-buy' : 'text-muted';
  
  return (
    <div className="space-y-2">
      <div className="flex justify-between items-end">
        <div className="flex items-center gap-1">
          <LearnTooltip term="Z_SCORE">3yr range</LearnTooltip>
          <Tooltip.Provider delayDuration={200}>
            <Tooltip.Root>
              <Tooltip.Trigger asChild>
                <Info size={10} className="text-muted cursor-help" />
              </Tooltip.Trigger>
              <Tooltip.Portal>
                <Tooltip.Content className="bg-surface border border-border rounded-xl p-3 shadow-xl max-w-xs text-[12px] text-secondary z-50 animate-in fade-in zoom-in duration-200" sideOffset={5}>
                  <p className="text-[11px]">Where is this fund's current price compared to its own 3-year history? Green zone = historically cheap. Red zone = historically expensive.</p>
                  <Tooltip.Arrow className="fill-border" />
                </Tooltip.Content>
              </Tooltip.Portal>
            </Tooltip.Root>
          </Tooltip.Provider>
        </div>
        <span className={`text-[10px] font-bold ${color}`}>{pct.toFixed(0)}th %ile — {zone}</span>
      </div>
      <div className="relative w-full h-1.5 bg-white/5 rounded-full overflow-hidden flex">
        <div className="h-full bg-buy/20 w-[30%]" />
        <div className="h-full bg-white/5 w-[40%]" />
        <div className="h-full bg-exit/20 w-[30%]" />
        <motion.div 
          className="absolute top-0 w-1.5 h-1.5 bg-white rounded-full border border-black/50 shadow-[0_0_4px_rgba(255,255,255,0.5)]"
          initial={{ left: 0 }}
          animate={{ left: `calc(${pct}% - 3px)` }}
          transition={{ duration: 1, ease: "easeOut" }}
        />
      </div>
    </div>
  );
}

function MiniStat({ label, value, color, term }: { label: string; value: string; color?: string; term?: string }) {
  const content = (
    <div className="space-y-0.5">
      <p className="text-[8px] uppercase tracking-[0.15em] text-muted leading-none">{label}</p>
      <p className={`text-[11px] font-medium tabular-nums leading-none ${color ?? 'text-secondary'}`}>{value}</p>
    </div>
  );
  return term ? <LearnTooltip term={term}>{content}</LearnTooltip> : content;
}

function ActionPill({ action }: { action: string }) {
  const map: Record<string, string> = {
    BUY: 'bg-buy/10 text-buy border-buy/20',
    SELL: 'bg-exit/10 text-exit border-exit/20',
    EXIT: 'bg-exit/15 text-exit border-exit/30',
    HOLD: 'bg-white/5 text-muted border-white/10',
    WATCH: 'bg-yellow-500/10 text-yellow-400 border-yellow-500/20',
    HARVEST: 'bg-violet-500/10 text-violet-400 border-violet-500/20',
  };
  return (
    <span className={`px-2 py-0.5 rounded text-[9px] font-bold uppercase tracking-widest border ${map[action] ?? map.HOLD}`}>
      {action}
    </span>
  );
}

function RegimeBadge({ metaphor, multiScaleRegime }: { metaphor: string; multiScaleRegime: string }) {
  let label = '';
  let colorClass = '';
  let icon = '';
  let tooltip = '';

  if (multiScaleRegime === "FRACTAL_BREAKOUT") {
    label = "Breakout"; colorClass = "text-violet-400"; icon = "🚀";
    tooltip = "All time horizons show a trending signal — strong momentum confirmed across short and long term.";
  } else if (multiScaleRegime === "STRONG_HOLD") {
    label = "Momentum"; colorClass = "text-green-400"; icon = "🏄";
  } else if (multiScaleRegime === "MEAN_REVERSION_RALLY") {
    label = "Rally"; colorClass = "text-amber-400"; icon = "🎯";
  } else if (metaphor === "RUBBER_BAND") {
    label = "Rubber Band"; colorClass = "text-blue-400"; icon = "🔴";
    tooltip = "This fund has stretched away from its fair value — like a rubber band, it tends to snap back.";
  } else if (metaphor === "WAVE_RIDER") {
    label = "Wave Rider"; colorClass = "text-teal-400"; icon = "🌊";
    tooltip = "This fund is in a momentum trend. The engine is letting it run rather than trimming.";
  } else if (metaphor === "VOLATILITY_HARVEST") {
    label = "Harvest"; colorClass = "text-yellow-400"; icon = "⚡";
    tooltip = "The market's ups and downs are creating a mathematical 'free money' opportunity here.";
  } else if (metaphor === "COOLING_OFF") {
    label = "Cooling Off"; colorClass = "text-gray-400"; icon = "🧊";
  }

  if (!label) return null;

  return (
    <Tooltip.Provider delayDuration={200}>
      <Tooltip.Root>
        <Tooltip.Trigger asChild>
          <span className={`text-[9px] font-medium uppercase tracking-widest px-1.5 py-0.5 rounded bg-white/5 border border-white/10 ${colorClass} cursor-help`}>
            {icon} {label}
          </span>
        </Tooltip.Trigger>
        <Tooltip.Portal>
          <Tooltip.Content className="bg-surface border border-border rounded-xl p-3 shadow-xl max-w-xs text-[12px] text-secondary z-50 animate-in fade-in zoom-in duration-200" sideOffset={5}>
            <p className="text-[11px]">{tooltip || label}</p>
            <Tooltip.Arrow className="fill-border" />
          </Tooltip.Content>
        </Tooltip.Portal>
      </Tooltip.Root>
    </Tooltip.Provider>
  );
}

// ─── main ─────────────────────────────────────────────────────────────────────

export default function FundsListView({
  portfolioData,
  onFundClick,
  isPrivate,
}: {
  portfolioData: any;
  onFundClick: (schemeName: string) => void;
  isPrivate: boolean;
}) {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('ALL');
  const [sort, setSort] = useState<'conviction' | 'value' | 'xirr' | 'drift'>('conviction');

  const allFunds: any[] = portfolioData.schemeBreakdown ?? [];

  const categories = ['ALL', 'EQUITY', 'DEBT', 'GOLD', 'ARBITRAGE'];

  const funds = allFunds
    .filter((f) => {
      const matchSearch = f.schemeName.toLowerCase().includes(search.toLowerCase());
      const matchFilter = filter === 'ALL' || (f.category ?? '').toUpperCase().includes(filter);
      return matchSearch && matchFilter;
    })
    .sort((a, b) => {
      if (sort === 'conviction') return (b.convictionScore ?? 0) - (a.convictionScore ?? 0);
      if (sort === 'value')      return (b.currentValue ?? 0) - (a.currentValue ?? 0);
      if (sort === 'xirr')       return parseFloat(b.xirr ?? '0') - parseFloat(a.xirr ?? '0');
      if (sort === 'drift')      return Math.abs(parseFloat(b.deviation ?? '0')) - Math.abs(parseFloat(a.deviation ?? '0'));
      return 0;
    });

  const renderConvictionDots = (score: number) => {
    if (score === 0) return <span className="text-[10px] text-muted italic">Calculating...</span>;
    const filledDots = Math.floor(score / 20);
    return (
      <Tooltip.Provider delayDuration={200}>
        <Tooltip.Root>
          <Tooltip.Trigger asChild>
            <div className="flex gap-1 cursor-help">
              {[...Array(5)].map((_, i) => (
                <div key={i} className={`w-1.5 h-1.5 rounded-full ${i < filledDots ? 'bg-buy shadow-[0_0_4px_rgba(52,211,153,0.4)]' : 'bg-white/10'}`} />
              ))}
            </div>
          </Tooltip.Trigger>
          <Tooltip.Portal>
            <Tooltip.Content className="bg-surface border border-border rounded-xl p-3 shadow-xl max-w-xs text-[12px] text-secondary z-50 animate-in fade-in zoom-in duration-200" sideOffset={5}>
              <p className="text-[11px]">Conviction score blends risk, value, and tax efficiency on a 0-100 scale.</p>
              <Tooltip.Arrow className="fill-border" />
            </Tooltip.Content>
          </Tooltip.Portal>
        </Tooltip.Root>
      </Tooltip.Provider>
    );
  };

  return (
    <div className="space-y-6 pb-32">
      {/* ── Header ── */}
      <header className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <p className="text-[9px] font-medium uppercase tracking-[0.2em] text-muted mb-0.5">Asset Matrix</p>
          <p className="text-lg font-medium text-primary tracking-tight">
            {allFunds.length} funds &middot; sorted by{' '}
            <span className="text-accent">{sort}</span>
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          {/* Search */}
          <div className="relative">
            <Search size={12} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search…"
              className="bg-surface border border-white/5 rounded-lg pl-8 pr-3 py-1.5 text-xs text-primary focus:outline-none focus:border-accent/40 transition-colors w-44"
            />
          </div>

          {/* Category filter */}
          <div className="flex bg-surface border border-white/5 p-0.5 rounded-lg">
            {categories.map((cat) => (
              <button
                key={cat}
                onClick={() => setFilter(cat)}
                className={`px-3 py-1 rounded-md text-[9px] font-bold uppercase tracking-widest transition-all ${
                  filter === cat ? 'bg-white/10 text-primary' : 'text-muted hover:text-secondary'
                }`}
              >
                {cat}
              </button>
            ))}
          </div>

          {/* Sort */}
          <div className="flex bg-surface border border-white/5 p-0.5 rounded-lg">
            {(['conviction', 'value', 'xirr', 'drift'] as const).map((s) => (
              <button
                key={s}
                onClick={() => setSort(s)}
                className={`px-3 py-1 rounded-md text-[9px] font-bold uppercase tracking-widest transition-all ${
                  sort === s ? 'bg-white/10 text-primary' : 'text-muted hover:text-secondary'
                }`}
              >
                {s}
              </button>
            ))}
          </div>
        </div>
      </header>

      {/* ── Fund Cards Grid ── */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
        {funds.map((fund: any) => {
          const drift = parseFloat(fund.deviation ?? '0');
          const xirr  = parseFloat(fund.xirr ?? '0');
          const mdd   = Math.abs(fund.maxDrawdown ?? 0);
          const sortino = fund.sortinoRatio ?? 0;
          const daysToLtcg = fund.daysToNextLtcg ?? 0;

          const driftColor = drift > 2 ? 'text-exit' : drift < -2 ? 'text-buy' : 'text-muted';
          const xirrColor  = xirr >= 0 ? 'text-buy' : 'text-exit';

          const signalSentence = fund.reasoningMetadata?.noobHeadline || (
            fund.action === 'BUY' ? "Engine says: rare discount — worth adding" :
            fund.action === 'SELL' ? "Engine says: trim overheated position" :
            fund.action === 'WATCH' ? "Engine says: market is shaky — hold off" :
            fund.action === 'HOLD' ? "Engine says: on track — no action needed" :
            fund.action === 'EXIT' ? "Engine says: this fund is being wound down" : ""
          );

          return (
            <motion.div
              key={fund.schemeName}
              layout
              onClick={() => onFundClick(fund.schemeName)}
              className="group bg-surface border border-white/[0.06] rounded-xl p-5 cursor-pointer hover:border-white/15 hover:bg-white/[0.015] transition-all duration-150 space-y-4"
            >
              {/* ── Row 1: Category + Action + Conviction ── */}
              <div className="flex items-start justify-between gap-3">
                <div className="space-y-1.5 min-w-0">
                  <p className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em] truncate">
                    {fund.category ?? '—'}
                  </p>
                  <p className="text-[13px] font-medium text-primary leading-snug line-clamp-2 group-hover:text-white transition-colors">
                    {fund.schemeName}
                  </p>
                </div>
                <div className="flex flex-col items-end gap-1.5 shrink-0">
                  <div className="flex items-center gap-2">
                    <RegimeBadge metaphor={fund.reasoningMetadata?.uiMetaphor} multiScaleRegime={fund.multiScaleRegime} />
                    <ActionPill action={fund.action ?? 'HOLD'} />
                  </div>
                  {renderConvictionDots(fund.convictionScore)}
                </div>
              </div>

              {/* ── Row 2: Price Zone Bar ── */}
              <PriceZoneBar percentile={fund.navPercentile3yr} />

              {/* ── Row 3: 4 Primary Stats ── */}
              <div className="grid grid-cols-4 gap-x-2 pt-1">
                <MiniStat
                  label="Value"
                  value={isPrivate ? '••••' : `₹${((fund.currentValue ?? 0) / 1000).toFixed(0)}k`}
                />
                <MiniStat
                  label="XIRR"
                  value={`${xirr >= 0 ? '+' : ''}${xirr.toFixed(1)}%`}
                  color={xirrColor}
                  term="XIRR"
                />
                <MiniStat
                  label="Drift"
                  value={`${drift >= 0 ? '+' : ''}${drift.toFixed(1)}%`}
                  color={driftColor}
                  term="Drift"
                />
                <MiniStat
                  label="Max DD"
                  value={mdd > 0 ? `-${mdd.toFixed(1)}%` : '—'}
                  color={mdd > 25 ? 'text-exit' : mdd > 10 ? 'text-warning' : 'text-secondary'}
                />
              </div>

              {/* ── Row 4: Signal Sentence + Secondary Stats ── */}
              <div className="pt-3 border-t border-white/[0.04] space-y-2">
                <div className="flex justify-between items-center">
                  <p className="text-[10px] text-secondary font-medium italic opacity-80">{signalSentence}</p>
                </div>
                <div className="flex gap-4">
                  <div className="flex items-center gap-1.5">
                    <span className="text-[8px] text-muted uppercase tracking-wider font-bold">Sortino</span>
                    <span className={`text-[10px] font-mono ${sortino > 1.5 ? 'text-buy' : sortino > 0.8 ? 'text-secondary' : 'text-exit'}`}>
                      {sortino > 0 ? sortino.toFixed(2) : '—'}
                    </span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <span className="text-[8px] text-muted uppercase tracking-wider font-bold">Tax</span>
                    <span className={`text-[10px] font-medium ${daysToLtcg > 0 && daysToLtcg <= 45 ? 'text-yellow-400' : 'text-secondary'}`}>
                      {daysToLtcg > 0 ? `⚠ STCG (${daysToLtcg}d)` : 'LTCG OK'}
                    </span>
                  </div>
                </div>
              </div>
            </motion.div>
          );
        })}
      </div>

      {funds.length === 0 && (
        <div className="py-24 flex flex-col items-center justify-center bg-surface/50 border border-dashed border-white/5 rounded-xl">
          <p className="text-muted text-xs font-medium">No funds match your criteria.</p>
        </div>
      )}
    </div>
  );
}
