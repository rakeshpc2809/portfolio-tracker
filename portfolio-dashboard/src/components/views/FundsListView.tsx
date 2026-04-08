import { useState } from 'react';
import { Search } from 'lucide-react';
import ConvictionBadge from '../ui/ConvictionBadge';

// ─── helpers ──────────────────────────────────────────────────────────────────

function NavRangeBar({ percentile }: { percentile: number }) {
  const pct = Math.min(100, Math.max(0, (percentile ?? 0.5) * 100));
  const color = pct > 80 ? '#f87171' : pct < 30 ? '#34d399' : '#94a3b8';
  return (
    <div className="relative w-full h-[3px] bg-white/[0.06] rounded-full overflow-visible">
      <div className="absolute top-0 left-0 h-full rounded-full transition-all duration-500"
           style={{ width: `${pct}%`, background: color }} />
      <div className="absolute top-1/2 -translate-y-1/2 w-[5px] h-[5px] rounded-full border border-black/40"
           style={{ left: `calc(${pct}% - 2.5px)`, background: color }} />
    </div>
  );
}

function MiniStat({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div className="space-y-0.5">
      <p className="text-[8px] uppercase tracking-[0.15em] text-muted leading-none">{label}</p>
      <p className={`text-[11px] font-medium tabular-nums leading-none ${color ?? 'text-secondary'}`}>{value}</p>
    </div>
  );
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
          const navPct = (fund.navPercentile3yr ?? 0.5) * 100;
          const mdd   = Math.abs(fund.maxDrawdown ?? 0);
          const sortino = fund.sortinoRatio ?? 0;
          const daysToLtcg = fund.daysToNextLtcg ?? 0;

          const driftColor = drift > 2 ? 'text-exit' : drift < -2 ? 'text-buy' : 'text-muted';
          const xirrColor  = xirr >= 0 ? 'text-buy' : 'text-exit';

          return (
            <div
              key={fund.schemeName}
              onClick={() => onFundClick(fund.schemeName)}
              className="group bg-surface border border-white/[0.06] rounded-xl p-5 cursor-pointer hover:border-white/15 hover:bg-white/[0.015] transition-all duration-150 space-y-4"
            >
              {/* ── Row 1: Name + Action + Conviction ── */}
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
                  <ConvictionBadge score={fund.convictionScore} />
                  <ActionPill action={fund.action ?? 'HOLD'} />
                </div>
              </div>

              {/* ── Row 2: NAV Range Bar ── */}
              <div className="space-y-1.5">
                <div className="flex justify-between text-[8px] text-muted uppercase tracking-widest">
                  <span>3yr low</span>
                  <span className={navPct > 80 ? 'text-exit' : navPct < 30 ? 'text-buy' : 'text-muted'}>
                    {navPct.toFixed(0)}th %ile
                  </span>
                  <span>3yr high</span>
                </div>
                <NavRangeBar percentile={fund.navPercentile3yr} />
              </div>

              {/* ── Row 3: 6 Mini Stats ── */}
              <div className="grid grid-cols-3 gap-x-3 gap-y-3 pt-1">
                <MiniStat
                  label="Value"
                  value={isPrivate ? '••••' : `₹${((fund.currentValue ?? 0) / 1000).toFixed(0)}k`}
                />
                <MiniStat
                  label="XIRR"
                  value={`${xirr >= 0 ? '+' : ''}${xirr.toFixed(1)}%`}
                  color={xirrColor}
                />
                <MiniStat
                  label="Drift"
                  value={`${drift >= 0 ? '+' : ''}${drift.toFixed(1)}%`}
                  color={driftColor}
                />
                <MiniStat
                  label="Sortino"
                  value={sortino > 0 ? sortino.toFixed(2) : '—'}
                  color={sortino > 1.5 ? 'text-buy' : sortino > 0.8 ? 'text-secondary' : 'text-exit'}
                />
                <MiniStat
                  label="Max DD"
                  value={mdd > 0 ? `-${mdd.toFixed(1)}%` : '—'}
                  color={mdd > 25 ? 'text-exit' : mdd > 10 ? 'text-warning' : 'text-secondary'}
                />
                <MiniStat
                  label={daysToLtcg > 0 ? `${daysToLtcg}d→LTCG` : 'Tax'}
                  value={daysToLtcg > 0 ? 'Wait' : 'LTCG OK'}
                  color={daysToLtcg > 0 && daysToLtcg <= 45 ? 'text-yellow-400' : 'text-secondary'}
                />
              </div>

              {/* ── Row 4: Conviction Score Bar ── */}
              <div className="pt-1 border-t border-white/[0.04] space-y-1.5">
                <div className="flex justify-between text-[8px] uppercase tracking-widest text-muted">
                  <span>Conviction</span>
                  <span className={(fund.convictionScore ?? 0) >= 65 ? 'text-buy' : (fund.convictionScore ?? 0) >= 45 ? 'text-secondary' : 'text-exit'}>
                    {fund.convictionScore ?? 0}/100
                  </span>
                </div>
                <div className="h-[2px] w-full bg-white/[0.05] rounded-full overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{
                      width: `${fund.convictionScore ?? 0}%`,
                      background: (fund.convictionScore ?? 0) >= 65 ? '#34d399' : (fund.convictionScore ?? 0) >= 45 ? '#94a3b8' : '#f87171',
                    }}
                  />
                </div>
              </div>
            </div>
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
