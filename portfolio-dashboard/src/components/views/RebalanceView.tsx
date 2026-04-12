import { useMemo } from 'react';
import { 
  ResponsiveContainer, XAxis, YAxis, Tooltip, 
  BarChart, Bar, Cell, CartesianGrid, ReferenceLine
} from "recharts";
import CurrencyValue from '../ui/CurrencyValue';
import { motion } from 'framer-motion';
import { Target, Clock } from 'lucide-react';

export default function RebalanceView({ 
  portfolioData, 
  sipAmount, 
  setSipAmount,
  isPrivate 
}: { 
  portfolioData: any; 
  sipAmount: number; 
  setSipAmount: (val: number) => void;
  isPrivate: boolean;
}) {
  const data = useMemo(() => (portfolioData.schemeBreakdown || [])
    .filter((s: any) => (s.currentValue || 0) > 0) // Only active positions
    .map((s: any) => {
      const drift = (s.allocationPercentage || 0) - (s.plannedPercentage || 0);
      return {
        ...s,
        name: s.simpleName || s.schemeName.substring(0, 22),
        drift: parseFloat(drift.toFixed(2)),
        color: drift > 1 ? "#f38ba8" : drift < -1 ? "#a6e3a1" : "#6c7086"
      };
    })
    .sort((a: any, b: any) => b.drift - a.drift), [portfolioData.schemeBreakdown]);

  const totalDrift = data.reduce((acc: number, s: any) => acc + Math.abs(s.drift), 0);

  // Step 3.6: Use server-computed SIP plan
  const sipPlan = portfolioData.tacticalPayload?.sipPlan || [];
  const portfolioValue = portfolioData.currentValueAmount || 1;

  // Step 3.4: Rebalance Timeline Data
  const timelines = useMemo(() => sipPlan
    .filter((s: any) => s.amount > 0)
    .map((s: any) => {
      const fund = data.find((f: any) => f.isin === s.isin);
      const drift = Math.abs(fund?.drift || 0);
      const gapValue = (drift / 100) * portfolioValue;
      const months = Math.ceil(gapValue / s.amount);
      return {
        name: s.simpleName || s.schemeName.substring(0, 22),
        months: months > 36 ? 36 : months, // Cap at 3 years for visual
        actualMonths: months,
        color: months <= 6 ? '#a6e3a1' : months <= 12 ? '#f9e2af' : '#f38ba8'
      };
    })
    .sort((a: any, b: any) => a.months - b.months), [sipPlan, data, portfolioValue]);

  return (
    <div className="space-y-10 pb-32">
      <header>
        <h2 className="text-muted text-[10px] font-black uppercase tracking-[0.2em] mb-1">Target Alignment</h2>
        <p className="text-xl font-black text-primary tracking-tight">Allocation Drift · Strategic Balance</p>
      </header>

      {/* DRIFT CHART */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl shadow-xl">
        <div className="h-80 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 20, right: 30, left: 20, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.02)" vertical={false} />
              <XAxis 
                dataKey="name" 
                axisLine={false} 
                tickLine={false} 
                tick={{ fill: 'rgba(241,245,249,0.3)', fontSize: 10, fontWeight: 700 }}
              />
              <YAxis 
                axisLine={false} 
                tickLine={false} 
                tick={{ fill: 'rgba(241,245,249,0.3)', fontSize: 10, fontWeight: 700 }}
                unit="%"
              />
              {!isPrivate && (
                <Tooltip 
                  cursor={{ fill: 'rgba(255,255,255,0.02)' }}
                  contentStyle={{ backgroundColor: 'rgba(15,15,24,0.9)', backdropFilter: 'blur(20px)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '12px' }}
                />
              )}
              <ReferenceLine y={0} stroke="rgba(255,255,255,0.1)" />
              <Bar dataKey="drift" radius={[4, 4, 0, 0]} barSize={24}>
                {data.map((entry: any, index: number) => (
                  <Cell key={`cell-${index}`} fill={entry.color} fillOpacity={0.8} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
        <div className="mt-8 pt-8 border-t border-white/5 flex flex-wrap items-center gap-8">
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-md bg-exit shadow-[0_0_12px_rgba(248,113,113,0.4)]" />
            <span className="text-[10px] font-black uppercase tracking-widest text-muted">Overweight</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-md bg-buy shadow-[0_0_12px_rgba(74,222,128,0.4)]" />
            <span className="text-[10px] font-black uppercase tracking-widest text-muted">Underweight</span>
          </div>
          <div className="ml-auto flex items-center gap-4">
            <div className="text-right">
              <p className="text-[9px] font-black text-muted uppercase tracking-widest">Total Portfolio Drift</p>
              <p className="text-lg font-black text-primary">{totalDrift.toFixed(1)}%</p>
            </div>
            <div className={`px-4 py-2 rounded-2xl border font-black text-[10px] uppercase tracking-widest ${
              totalDrift < 5 ? 'text-buy bg-buy/10 border-buy/20' : 'text-warning bg-warning/10 border-warning/20'
            }`}>
              {totalDrift < 5 ? 'Stable' : 'Unbalanced'}
            </div>
          </div>
        </div>
      </section>

      {/* REBALANCE TIMELINE */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl shadow-xl">
        <div className="flex items-center justify-between mb-8">
          <div className="space-y-1">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em] flex items-center gap-2">
              <Clock size={12} className="text-accent" /> Correction Timeline
            </h3>
            <p className="text-xs text-muted font-bold uppercase tracking-widest opacity-60">Estimated months to fix underweight drift via SIP only</p>
          </div>
        </div>

        <div className="space-y-4">
          {timelines.map((t: any) => (
            <div key={t.name} className="space-y-2">
              <div className="flex justify-between items-end px-1">
                <span className="text-[11px] font-bold text-primary">{t.name}</span>
                <span className="text-[10px] font-black uppercase tracking-tighter text-secondary">
                  {t.actualMonths === Infinity ? '∞' : t.actualMonths} Months
                </span>
              </div>
              <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden border border-white/5">
                <motion.div 
                  initial={{ width: 0 }}
                  animate={{ width: `${(t.months / 36) * 100}%` }}
                  transition={{ duration: 1, ease: "easeOut" }}
                  className="h-full rounded-full"
                  style={{ background: t.color, boxShadow: `0 0 10px ${t.color}44` }}
                />
              </div>
            </div>
          ))}
          {timelines.length === 0 && (
            <div className="py-10 text-center border border-dashed border-white/5 rounded-2xl">
              <p className="text-muted text-[10px] font-black uppercase tracking-widest">No major underweight positions to track</p>
            </div>
          )}
        </div>
      </section>

      {/* SIP SIMULATOR */}
      <section className="bg-accent/5 border border-accent/10 p-10 rounded-[2.5rem] shadow-2xl relative overflow-hidden flex flex-col lg:flex-row gap-12">
        <div className="flex-1 space-y-8">
          <div className="flex justify-between items-center">
            <div className="space-y-1">
              <h3 className="text-primary text-sm font-black uppercase tracking-[0.1em]">Deployment Control</h3>
              <p className="text-[10px] text-muted font-bold uppercase tracking-widest">Adjust monthly fire-power</p>
            </div>
            <CurrencyValue isPrivate={isPrivate} value={sipAmount} className="text-2xl font-black text-primary tabular-nums tracking-tighter glow-accent" />
          </div>
          
          <input 
            type="range" min="0" max="200000" step="5000" 
            value={sipAmount} 
            onChange={(e) => setSipAmount(parseInt(e.target.value) || 0)}
            className="w-full h-2 bg-white/5 rounded-full appearance-none cursor-pointer accent-accent hover:accent-accent-bright transition-all shadow-inner"
          />

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mt-8">
            {sipPlan.filter((s: any) => s.amount > 0).map((s: any) => (
              <div key={s.isin} className="bg-black/20 border border-white/5 p-4 rounded-2xl flex items-center justify-between group hover:border-white/10 transition-all shadow-lg">
                <div className="min-w-0">
                  <p className="text-[11px] font-black text-secondary truncate tracking-tight">{s.simpleName || s.schemeName}</p>
                  <p className="text-[9px] font-black text-muted uppercase tracking-widest mt-0.5">{s.sipPct}% Load</p>
                </div>
                <div className="text-right pl-4">
                  <CurrencyValue isPrivate={isPrivate} value={s.amount} className="text-[13px] font-black text-buy tabular-nums" />
                </div>
              </div>
            ))}
          </div>
        </div>
        
        <div className="lg:w-64 shrink-0 flex flex-col items-center justify-center p-8 bg-surface/60 backdrop-blur-3xl border border-white/10 rounded-[2rem] text-center shadow-2xl">
          <div className="p-4 bg-accent/10 rounded-3xl mb-4 text-accent">
            <Target size={32} />
          </div>
          <p className="text-muted text-[10px] font-black uppercase tracking-[0.2em] mb-2">Portfolio Sync</p>
          <p className="text-3xl font-black text-primary tabular-nums tracking-tighter">{(100 - totalDrift).toFixed(1)}%</p>
          <p className="text-[9px] font-bold text-secondary uppercase tracking-widest mt-1">Accuracy Score</p>
          
          <div className="mt-8 space-y-4 w-full pt-6 border-t border-white/5">
            <div className="flex justify-between text-[10px] font-black uppercase tracking-tighter">
              <span className="text-muted">Status</span>
              <span className={totalDrift < 5 ? 'text-buy' : 'text-warning'}>{totalDrift < 5 ? 'Optimal' : 'Drifting'}</span>
            </div>
            <div className="flex justify-between text-[10px] font-black uppercase tracking-tighter">
              <span className="text-muted">Target ETA</span>
              <span className="text-primary">{timelines[0]?.actualMonths || 0} Mo</span>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
