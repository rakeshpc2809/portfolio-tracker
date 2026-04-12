import { useState, useMemo } from 'react';
import { Search, Info, TrendingUp, ShieldAlert, Zap } from 'lucide-react';
import LearnTooltip from '../ui/LearnTooltip';
import * as Tooltip from '@radix-ui/react-tooltip';
import { motion, AnimatePresence } from 'framer-motion';

// ─── helpers ──────────────────────────────────────────────────────────────────

function ZScoreBar({ zScore, rarityPct }: { zScore: number; rarityPct: number }) {
  const z = Math.max(-4, Math.min(4, zScore ?? 0));
  const pct = ((z + 4) / 8) * 100; // Map -4..+4 -> 0..100%
  const zone = z <= -2 ? 'Statistically cheap' 
             : z <= -1 ? 'Mild discount'
             : z >= 2  ? 'Statistically stretched'
             : z >= 1  ? 'Mild premium'
             : 'Fair value';
  const color = z <= -1.5 ? 'text-buy' : z >= 1.5 ? 'text-exit' : 'text-muted';
  
  return (
    <div className="space-y-3">
      <div className="flex justify-between items-end">
        <div className="flex items-center gap-1.5 group cursor-help">
          <LearnTooltip term="Z_SCORE">
            <span className="text-[10px] font-black uppercase tracking-widest text-muted group-hover:text-secondary transition-colors">Statistical Pricing</span>
          </LearnTooltip>
          <Info size={10} className="text-hint group-hover:text-accent transition-all" />
        </div>
        <div className="text-right">
          <span className={`text-[10px] font-black uppercase tracking-tighter ${color}`}>{z.toFixed(1)}σ — {zone}</span>
          {Math.abs(z) >= 1.5 && (
            <p className="text-[8px] font-black text-muted uppercase tracking-widest mt-0.5 opacity-60">
              Only {rarityPct.toFixed(1)}% of days this {z < 0 ? 'cheap' : 'expensive'}
            </p>
          )}
        </div>
      </div>
      <div 
        className="relative w-full h-2 rounded-full overflow-hidden border border-white/5 bg-black/20 shadow-inner"
        style={{
          background: 'linear-gradient(to right, #313244 0%, #313244 25%, #181825 25%, #181825 40%, #181825 60%, #181825 75%, #313244 75%, #313244 100%)'
        }}
      >
        {/* Zone Tints */}
        <div className="absolute inset-0 flex">
          <div className="h-full bg-buy/10" style={{ width: '25%' }} />
          <div className="h-full bg-buy/5" style={{ width: '15%' }} />
          <div className="h-full" style={{ width: '20%' }} />
          <div className="h-full bg-exit/5" style={{ width: '15%' }} />
          <div className="h-full bg-exit/10" style={{ width: '25%' }} />
        </div>

        <motion.div 
          className="absolute top-0 w-2 h-2 bg-white rounded-full border border-black/50 shadow-[0_0_10px_rgba(255,255,255,0.8)] z-10"
          initial={{ left: 0 }}
          animate={{ left: `calc(${pct}% - 4px)` }}
          transition={{ type: "spring", damping: 15, stiffness: 100 }}
        />
      </div>
    </div>
  );
}

function MiniStat({ label, value, color, term }: { label: string; value: string; color?: string; term?: string }) {
  const content = (
    <div className="space-y-1">
      <p className="text-[8px] font-black uppercase tracking-[0.2em] text-muted leading-none opacity-60">{label}</p>
      <p className={`text-xs font-bold tabular-nums leading-none ${color ?? 'text-primary'}`}>{value}</p>
    </div>
  );
  return term ? <LearnTooltip term={term}>{content}</LearnTooltip> : content;
}

function ActionPill({ action }: { action: string }) {
  const map: Record<string, string> = {
    BUY: 'chip-buy glow-buy',
    SELL: 'chip-exit glow-exit',
    EXIT: 'chip-exit glow-exit',
    HOLD: 'chip-hold glow-accent',
    WATCH: 'chip-watch',
    HARVEST: 'chip-harvest glow-harvest',
  };
  return (
    <span className={`chip h-5 px-2.5 ${map[action] || 'chip-hold'}`}>
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
    tooltip = "Strong momentum confirmed across short and long term horizons.";
  } else if (multiScaleRegime === "STRONG_HOLD") {
    label = "Momentum"; colorClass = "text-emerald-400"; icon = "🏄";
  } else if (multiScaleRegime === "MEAN_REVERSION_RALLY") {
    label = "Rally"; colorClass = "text-amber-400"; icon = "🎯";
  } else if (metaphor === "RUBBER_BAND") {
    label = "Oversold"; colorClass = "text-blue-400"; icon = "🔴";
    tooltip = "Stretched away from fair value — likely snapback ahead.";
  } else if (metaphor === "WAVE_RIDER") {
    label = "Trending"; colorClass = "text-teal-400"; icon = "🌊";
  } else if (metaphor === "VOLATILITY_HARVEST") {
    label = "Harvest"; colorClass = "text-yellow-400"; icon = "⚡";
  } else if (metaphor === "COOLING_OFF") {
    label = "Cooling"; colorClass = "text-slate-400"; icon = "🧊";
  }

  if (!label) return null;

  return (
    <Tooltip.Provider delayDuration={200}>
      <Tooltip.Root>
        <Tooltip.Trigger asChild>
          <span className={`text-[9px] font-black uppercase tracking-widest px-2 py-0.5 rounded-full bg-white/5 border border-white/10 ${colorClass} cursor-help hover:bg-white/10 transition-colors shadow-sm`}>
            {icon} {label}
          </span>
        </Tooltip.Trigger>
        <Tooltip.Portal>
          <Tooltip.Content className="bg-surface border border-border rounded-xl p-3 shadow-2xl max-w-xs text-[11px] text-secondary z-50 animate-in fade-in zoom-in duration-200" sideOffset={5}>
            <p className="font-medium">{tooltip || label}</p>
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
  const [sort, setSort] = useState<'conviction' | 'value' | 'xirr' | 'drift' | 'risk-adj'>('conviction');

  // 1. FILTER OUT ZERO VALUE FUNDS
  const activeFunds = useMemo(() => 
    (portfolioData.schemeBreakdown ?? []).filter((f: any) => (f.currentValue || 0) > 0),
    [portfolioData.schemeBreakdown]
  );

  const categories = ['ALL', 'EQUITY', 'DEBT', 'GOLD', 'ARBITRAGE'];

  const funds = useMemo(() => activeFunds
    .filter((f: any) => {
      const matchSearch = f.schemeName.toLowerCase().includes(search.toLowerCase());
      const matchFilter = filter === 'ALL' || (f.cleanCategory ?? '').toUpperCase().includes(filter);
      return matchSearch && matchFilter;
    })
    .sort((a: any, b: any) => {
      if (sort === 'conviction') return (b.convictionScore ?? 0) - (a.convictionScore ?? 0);
      if (sort === 'value')      return (b.currentValue ?? 0) - (a.currentValue ?? 0);
      if (sort === 'xirr')       return parseFloat(b.xirr ?? '0') - parseFloat(a.xirr ?? '0');
      if (sort === 'drift')      return Math.abs(parseFloat(b.deviation ?? '0')) - Math.abs(parseFloat(a.deviation ?? '0'));
      if (sort === 'risk-adj')   return (parseFloat(b.xirr ?? '0') / Math.abs(b.maxDrawdown || 1)) - (parseFloat(a.xirr ?? '0') / Math.abs(a.maxDrawdown || 1));
      return 0;
    }), [activeFunds, search, filter, sort]);

  const renderConvictionDots = (score: number) => {
    if (score === 0) return <span className="text-[10px] text-muted italic">Calculating...</span>;
    const dots = Math.round(score / 20); // 0-5
    return (
      <Tooltip.Provider delayDuration={200}>
        <Tooltip.Root>
          <Tooltip.Trigger asChild>
            <div className="flex gap-1.5 cursor-help items-center h-3">
              {Array.from({ length: 5 }).map((_, i) => (
                <motion.div
                  key={i}
                  className="w-1.5 h-1.5 rounded-full shadow-[0_0_8px_rgba(0,0,0,0.5)]"
                  style={{
                    background: i < dots
                      ? `hsl(${120 + (dots - i) * 10}deg, 80%, 60%)`
                      : 'rgba(255,255,255,0.08)'
                  }}
                  initial={{ scale: 0, opacity: 0 }}
                  animate={{ scale: 1, opacity: 1 }}
                  transition={{ delay: i * 0.08, type: 'spring', stiffness: 400, damping: 10 }}
                />
              ))}
            </div>
          </Tooltip.Trigger>
          <Tooltip.Portal>
            <Tooltip.Content className="bg-surface border border-border rounded-xl p-3 shadow-2xl max-w-xs text-[11px] text-secondary z-50 animate-in fade-in zoom-in duration-200" sideOffset={5}>
              <p className="font-bold text-buy mb-1">Conviction: {score}/100</p>
              <p className="opacity-80">Relative health index based on risk, momentum, and tax efficiency.</p>
              <Tooltip.Arrow className="fill-border" />
            </Tooltip.Content>
          </Tooltip.Portal>
        </Tooltip.Root>
      </Tooltip.Provider>
    );
  };

  return (
    <div className="space-y-8 pb-32">
      {/* ── Header ── */}
      <header className="flex flex-col md:flex-row md:items-end justify-between gap-6 px-2">
        <div className="space-y-1">
          <p className="text-[10px] font-black uppercase tracking-[0.3em] text-accent animate-pulse">Asset Intelligence</p>
          <p className="text-2xl font-black text-primary tracking-tighter">
            {activeFunds.length} <span className="text-muted font-light">Positions Active</span>
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-4">
          {/* Liquid Search */}
          <div className="relative group">
            <Search size={12} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted group-focus-within:text-accent transition-colors" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search assets..."
              className="bg-surface/40 backdrop-blur-xl border border-white/5 rounded-xl pl-9 pr-4 py-2 text-xs text-primary focus:outline-none focus:border-accent/40 focus:ring-1 focus:ring-accent/20 transition-all w-56 shadow-lg"
            />
          </div>

          {/* Pill Filters */}
          <div className="flex bg-surface/40 backdrop-blur-xl border border-white/5 p-1 rounded-xl shadow-lg">
            {categories.map((cat) => (
              <button
                key={cat}
                onClick={() => setFilter(cat)}
                className={`px-4 py-1.5 rounded-lg text-[10px] font-black uppercase tracking-widest transition-all duration-300 ${
                  filter === cat ? 'bg-white/10 text-primary shadow-sm' : 'text-muted hover:text-secondary'
                }`}
              >
                {cat}
              </button>
            ))}
          </div>

          <div className="flex bg-surface/40 backdrop-blur-xl border border-white/5 p-1 rounded-xl shadow-lg">
            {(['conviction', 'value', 'xirr', 'drift', 'risk-adj'] as const).map((s) => (
              <button
                key={s}
                onClick={() => setSort(s)}
                className={`px-4 py-1.5 rounded-lg text-[10px] font-black uppercase tracking-widest transition-all duration-300 ${
                  sort === s ? 'bg-white/10 text-primary shadow-sm' : 'text-muted hover:text-secondary'
                }`}
              >
                {s}
              </button>
            ))}
          </div>
        </div>
      </header>

      {/* ── Fund Cards Grid ── */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        <AnimatePresence mode="popLayout">
          {funds.map((fund: any, idx: number) => {
            const xirr  = parseFloat(fund.xirr ?? '0');
            const mdd   = Math.abs(fund.maxDrawdown ?? 0);
            const sortino = fund.sortinoRatio ?? 0;
            const daysToLtcg = fund.daysToNextLtcg ?? 0;
            const riskAdjReturn = mdd !== 0 ? (xirr / mdd).toFixed(2) : 'N/A';

            const xirrColor  = xirr >= 0 ? 'text-buy' : 'text-exit';

            const signalSentence = fund.reasoningMetadata?.noobHeadline || (
              fund.action === 'BUY' ? "Statistical discount — structural entry advised" :
              fund.action === 'SELL' ? "Position overheated — logic suggests trim" :
              fund.action === 'WATCH' ? "High volatility — waiting for mean reversion" :
              fund.action === 'HOLD' ? "Stable orbit — maintaining target allocation" :
              fund.action === 'EXIT' ? "Strategy purge — phased exit in progress" : ""
            );

            const signalIcon = {
              BUY: <TrendingUp size={14}/>, 
              SELL: <ShieldAlert size={14}/>, 
              HOLD: <Info size={14}/>, 
              WATCH: <Zap size={14}/>, 
              EXIT: <ShieldAlert size={14}/>
            }[fund.action as string || 'HOLD'];

            const cardTone = {
              BUY: 'surface-tonal-buy shadow-buy border-l-buy/40',
              SELL: 'surface-tonal-exit shadow-exit border-l-exit/40',
              EXIT: 'surface-tonal-exit shadow-exit border-l-exit/40',
              HOLD: 'surface-tonal-accent border-l-hold/20',
              WATCH: 'surface-tonal-accent border-l-watch/40',
            }[fund.action as string || 'HOLD'] || '';

            return (
              <motion.div
                key={fund.schemeName}
                layout
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.9 }}
                transition={{ type: "spring", damping: 20, stiffness: 200, delay: idx * 0.02 }}
                onClick={() => onFundClick(fund.schemeName)}
                className={`group bg-surface/40 backdrop-blur-2xl border border-white/[0.06] border-l-4 rounded-3xl p-6 cursor-pointer hover:border-white/20 hover:bg-white/[0.03] transition-all duration-500 space-y-6 shadow-xl ${cardTone}`}
              >
                {/* ── Top Row ── */}
                <div className="flex items-start justify-between gap-4">
                  <div className="space-y-2 min-w-0">
                    <p className="text-[10px] font-black text-muted uppercase tracking-[0.2em] group-hover:text-accent transition-colors">
                      {fund.cleanCategory ?? fund.category ?? '—'}
                    </p>
                    <p className="text-[15px] font-black text-primary leading-tight line-clamp-2 group-hover:text-white transition-colors tracking-tight">
                      {fund.simpleName || fund.schemeName}
                    </p>
                  </div>
                  <div className="flex flex-col items-end gap-2.5 shrink-0">
                    <div className="flex items-center gap-2">
                      <RegimeBadge metaphor={fund.reasoningMetadata?.uiMetaphor} multiScaleRegime={fund.multiScaleRegime} />
                      <ActionPill action={fund.action ?? 'HOLD'} />
                    </div>
                    {renderConvictionDots(fund.convictionScore)}
                  </div>
                </div>

                {/* ── Price Pulse ── */}
                <ZScoreBar zScore={fund.rollingZScore252} rarityPct={fund.historicalRarityPct ?? 50} />

                {/* ── Performance Matrix ── */}
                <div className="grid grid-cols-4 gap-2 py-2">
                  <MiniStat label="Value" value={isPrivate ? '••••' : `₹${((fund.currentValue ?? 0) / 1000).toFixed(0)}k`} />
                  <MiniStat label="XIRR" value={`${xirr >= 0 ? '+' : ''}${xirr.toFixed(1)}%`} color={xirrColor} term="XIRR" />
                  <MiniStat label="Risk-Adj" value={riskAdjReturn} term="SORTINO" />
                  <MiniStat label="Max DD" value={mdd > 0 ? `-${mdd.toFixed(1)}%` : '—'} color={mdd > 25 ? 'text-exit' : mdd > 10 ? 'text-warning' : 'text-secondary'} />
                </div>

                {/* ── System Intel ── */}
                <div className="pt-5 border-t border-white/5 space-y-4">
                  <div className="flex items-center gap-3 px-3 py-2.5 rounded-2xl bg-black/20 border border-white/5 group-hover:bg-black/40 transition-all">
                    <div className={`p-1.5 rounded-lg bg-white/5 ${funds.indexOf(fund) % 2 === 0 ? 'text-accent' : 'text-buy'}`}>
                      {signalIcon}
                    </div>
                    <p className="text-[11px] font-bold text-secondary leading-snug tracking-tight">
                      {signalSentence}
                    </p>
                  </div>
                  
                  <div className="flex justify-between items-center px-1">
                    <div className="flex gap-5">
                      <div className="flex flex-col gap-0.5">
                        <span className="text-[8px] text-muted uppercase font-black tracking-widest opacity-50">Sortino</span>
                        <span className={`text-xs font-black tabular-nums ${sortino > 1.5 ? 'text-buy glow-buy' : sortino > 0.8 ? 'text-secondary' : 'text-exit'}`}>
                          {sortino > 0 ? sortino.toFixed(2) : '—'}
                        </span>
                      </div>
                      <div className="flex flex-col gap-0.5">
                        <span className="text-[8px] text-muted uppercase font-black tracking-widest opacity-50">Tax Risk</span>
                        <span className={`text-xs font-black ${daysToLtcg > 0 && daysToLtcg <= 45 ? 'text-warning' : 'text-buy'}`}>
                          {daysToLtcg > 0 ? `STCG (${daysToLtcg}d)` : 'LTCG Safe'}
                        </span>
                      </div>
                    </div>
                    <div className="h-8 w-px bg-white/5" />
                    <div className="text-right">
                      <p className="text-[8px] text-muted uppercase font-black tracking-widest opacity-50">Units</p>
                      <p className="text-xs font-bold text-secondary tabular-nums">{(fund.units || 0).toFixed(2)}</p>
                    </div>
                  </div>
                </div>
              </motion.div>
            );
          })}
        </AnimatePresence>
      </div>

      {funds.length === 0 && (
        <motion.div 
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          className="py-32 flex flex-col items-center justify-center bg-surface/20 backdrop-blur-xl border border-dashed border-white/10 rounded-[3rem]"
        >
          <div className="w-16 h-16 rounded-3xl bg-white/5 flex items-center justify-center mb-6">
            <Search size={32} className="text-muted/20" />
          </div>
          <p className="text-secondary text-sm font-black uppercase tracking-widest">No matching assets found</p>
          <p className="text-muted text-[10px] uppercase tracking-widest mt-2">Adjust filters or search query</p>
        </motion.div>
      )}
    </div>
  );
}
