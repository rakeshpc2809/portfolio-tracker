import { motion, type Variants } from 'framer-motion';
import { useMemo } from 'react';
import { Zap, TrendingUp, Target, ArrowDownRight, Scissors, ShieldAlert } from 'lucide-react';
import CurrencyValue from '../ui/CurrencyValue';
import { RecommendationDetailCard } from '../ui/RecommendationDetailCard';
import { resolveReasoningMetadata, formatCurrency } from '../../utils/formatters';
import type { TacticalSignal } from '../../types/signals';

export default function TodayBriefView({ 
  portfolioData, 
  sipAmount, 
  setSipAmount, 
  lumpsum, 
  setLumpsum,
  onFundClick,
  isPrivate
}: { 
  portfolioData: any;
  sipAmount: number;
  setSipAmount: (val: number) => void;
  lumpsum: number;
  setLumpsum: (val: number) => void;
  onFundClick: (schemeName: string) => void;
  isPrivate: boolean;
}) {
  const dateStr = new Date().toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  const payload = portfolioData.tacticalPayload || { 
    sipPlan: [], 
    opportunisticSignals: [], 
    activeSellSignals: [],
    exitQueue: [],
    harvestOpportunities: [],
    totalExitValue: 0,
    totalHarvestValue: 0,
    droppedFundsCount: 0
  };

  const exitCount = payload.exitQueue?.length || 0;
  const sellCount = payload.activeSellSignals?.length || 0;
  const harvestCount = payload.harvestOpportunities?.length || 0;
  const sipTotal = payload.sipPlan?.reduce((a: number, s: any) => a + s.amount, 0) || 0;

  const activeSipPlan = useMemo(() => 
    (payload.sipPlan || []).filter((s: any) => s.amount > 0),
    [payload.sipPlan]
  );

  const container: Variants = {
    hidden: { opacity: 0 },
    show: {
      opacity: 1,
      transition: { staggerChildren: 0.08 }
    }
  };

  const item: Variants = {
    hidden: { opacity: 0, y: 20, scale: 0.95 },
    show: { 
      opacity: 1, 
      y: 0, 
      scale: 1, 
      transition: { 
        type: "spring" as const, 
        damping: 20, 
        stiffness: 200 
      } 
    }
  };

  return (
    <div className="space-y-12 pb-32">
      <header className="space-y-8">
        <div className="flex justify-between items-start">
          <div className="space-y-1">
            <h2 className="text-accent text-[10px] font-black uppercase tracking-[0.3em] animate-pulse">Tactical Orbit Control</h2>
            <p className="text-2xl font-black text-primary tracking-tighter tabular-nums">Briefing · {dateStr}</p>
          </div>
          <div className="px-4 py-2 rounded-2xl bg-white/[0.03] border border-white/5 backdrop-blur-xl shadow-xl flex flex-col items-end group hover:border-buy/20 transition-all">
            <span className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">System Orbit Return</span>
            <span className={`text-lg font-black tabular-nums ${parseFloat(portfolioData.overallReturn) >= 0 ? 'text-buy glow-buy' : 'text-exit glow-exit'}`}>
              {portfolioData.overallReturn}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {[
            { label: 'Attention Required', value: `${exitCount + sellCount} Assets`, color: exitCount + sellCount > 0 ? 'text-exit' : 'text-buy', icon: <ShieldAlert size={14}/>, bg: exitCount + sellCount > 0 ? 'bg-exit/10' : 'bg-buy/10' },
            { label: 'Deployment Budget', value: formatCurrency(sipTotal), color: 'text-accent', icon: <Zap size={14} className="fill-current"/>, bg: 'bg-accent/10' },
            { label: 'Tax Optimization', value: `${harvestCount} Signals`, color: 'text-buy', icon: <Scissors size={14}/>, bg: 'bg-buy/10' }
          ].map((stat, i) => (
            <motion.div 
              key={stat.label}
              initial={{ opacity: 0, x: -20 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: i * 0.1 }}
              className="px-6 py-4 bg-surface/40 backdrop-blur-xl border border-white/5 rounded-3xl flex items-center gap-4 group hover:border-white/10 transition-all shadow-lg"
            >
              <div className={`p-3 rounded-2xl ${stat.bg} ${stat.color} group-hover:scale-110 transition-transform shadow-inner`}>
                {stat.icon}
              </div>
              <div className="space-y-0.5">
                <p className="text-[9px] font-black uppercase tracking-widest text-muted opacity-60">{stat.label}</p>
                <p className={`text-base font-black tabular-nums ${stat.color}`}>{isPrivate && stat.label.includes('Budget') ? '••••' : stat.value}</p>
              </div>
            </motion.div>
          ))}
        </div>
      </header>

      <section className="space-y-8">
        <div className="flex flex-col md:flex-row md:items-end justify-between gap-6 px-2">
          <div className="space-y-1">
            <h3 className="text-muted text-[10px] font-black uppercase tracking-[0.3em] flex items-center gap-2">
              <Target size={12} className="text-accent" /> Structural Deployment Plan
            </h3>
            <p className="text-sm font-bold text-secondary">Phased entry strategy based on current market pulse.</p>
          </div>
          
          <div className="flex flex-wrap items-center gap-10 bg-surface/40 backdrop-blur-md p-5 rounded-[2rem] border border-white/5 shadow-xl">
            <div className="flex items-center gap-6">
              <div className="space-y-1.5">
                <div className="flex justify-between text-[9px] font-black uppercase tracking-widest text-muted">
                  <span>Monthly Budget</span>
                  <span className="text-accent"><CurrencyValue isPrivate={isPrivate} value={sipAmount} /></span>
                </div>
                <input 
                  type="range" min="0" max="200000" step="5000" 
                  value={sipAmount} 
                  onChange={(e) => setSipAmount(parseInt(e.target.value) || 0)}
                  className="w-40 h-1.5 bg-white/10 rounded-full appearance-none cursor-pointer accent-accent hover:accent-accent-bright transition-all"
                />
              </div>
            </div>
            <div className="flex flex-col gap-1.5 border-l border-white/5 pl-8">
              <label className="text-[9px] font-black uppercase tracking-widest text-muted">Tactical Lumpsum</label>
              <input 
                type="number" 
                value={lumpsum || ''} 
                onChange={(e) => setLumpsum(parseInt(e.target.value) || 0)}
                placeholder="₹0"
                className="bg-black/40 border border-white/10 rounded-xl px-4 py-2 text-xs text-primary tabular-nums font-black focus:outline-none focus:border-accent/50 focus:ring-1 focus:ring-accent/20 transition-all w-32 shadow-inner"
              />
            </div>
          </div>
        </div>

        <motion.div 
          variants={container}
          initial="hidden"
          animate="show"
          className="grid grid-cols-1 md:grid-cols-2 gap-4"
        >
          {activeSipPlan.length === 0 ? (
            <div className="col-span-full py-20 text-center bg-surface/20 backdrop-blur-xl border border-dashed border-white/10 rounded-[2.5rem] space-y-4">
              <Target size={40} className="text-muted/10 mx-auto" />
              <p className="text-muted text-[10px] font-black uppercase tracking-widest opacity-40">No active SIP deployment targets</p>
            </div>
          ) : (
            activeSipPlan.map((s: any) => (
              <motion.div
                key={s.isin}
                variants={item}
                onClick={() => onFundClick(s.schemeName)}
                className="flex items-center gap-5 px-8 py-6 bg-surface/40 backdrop-blur-xl border border-white/5 rounded-[2rem] hover:border-accent/20 hover:bg-white/[0.03] cursor-pointer transition-all group shadow-lg"
              >
                <div className={`w-2.5 h-2.5 rounded-full shrink-0 shadow-lg ${s.deployFlag === 'DEPLOY' ? 'bg-buy animate-pulse glow-buy' : 'bg-warning'}`} />
                <div className="flex-1 min-w-0">
                  <p className="text-[15px] text-primary group-hover:text-white transition-colors truncate font-black tracking-tight">
                    {s.simpleName || s.schemeName}
                  </p>
                  <p className="text-[10px] text-muted font-bold uppercase tracking-widest opacity-60 mt-1">Target: {s.sipPct}% Allocation</p>
                </div>
                <div className="text-right space-y-1">
                  <p className={`text-lg font-black tabular-nums tracking-tighter ${s.deployFlag === 'DEPLOY' ? 'text-buy' : 'text-warning'}`}>
                    {isPrivate ? '••••' : `₹${(s.amount / 1000).toFixed(1)}k`}
                  </p>
                  <div className={`text-[9px] font-black uppercase tracking-[0.2em] px-3 py-1 rounded-full inline-block border ${
                    s.deployFlag === 'DEPLOY' ? 'bg-buy/10 text-buy border-buy/20' : 'bg-warning/10 text-warning border-warning/20'
                  }`}>
                    {s.deployFlag === 'DEPLOY' ? 'Execute' : 'Await Pulse'}
                  </div>
                </div>
              </motion.div>
            ))
          )}
        </motion.div>
      </section>

      {/* SECTION 2: OPPORTUNISTIC TOP-UPS (BUY) */}
      <section className="space-y-6">
        <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
          <Zap size={12} className="text-warning" /> Opportunistic Signals (Half-Kelly Sized)
        </h3>
        <motion.div 
          variants={container}
          initial="hidden"
          animate="show"
          className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4"
        >
          {payload.opportunisticSignals.map((signal: TacticalSignal) => (
            <motion.div key={signal.schemeName} variants={item}>
              <RecommendationDetailCard
                signal={{
                  ...signal,
                  reasoningMetadata: resolveReasoningMetadata(signal)
                }}
                isPrivate={isPrivate}
                defaultExpanded={signal.action === 'BUY' && (signal.returnZScore ?? 0) <= -2.0}
              />
            </motion.div>
          ))}
          
          {payload.opportunisticSignals.length === 0 && (
            <div className="col-span-full py-6 px-6 bg-white/[0.02] border border-white/5 rounded-xl space-y-2">
              <p className="text-muted text-[11px] font-medium uppercase tracking-widest">
                No dip entries or rebalancer deploys triggered today
              </p>
            </div>
          )}
        </motion.div>
      </section>

      {/* SECTION 3: ACTIVE SELL/HOLD SIGNALS (Gate B) */}
      {payload.activeSellSignals?.length > 0 && (
        <section className="space-y-6">
          <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
            <ArrowDownRight size={12} className="text-exit" /> Active Rebalance (Gate B)
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {payload.activeSellSignals.map((signal: TacticalSignal) => (
              <RecommendationDetailCard 
                key={signal.schemeName}
                signal={{
                  ...signal,
                  reasoningMetadata: resolveReasoningMetadata(signal)
                }}
                isPrivate={isPrivate}
              />
            ))}
          </div>
        </section>
      )}

      {/* SECTION 4: TAX-LOSS HARVESTING (HARVEST) */}
      {payload.harvestOpportunities?.length > 0 && (
        <section className="space-y-6">
          <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
            <Scissors size={12} className="text-buy" /> Tax-Loss Harvesting (HARVEST)
          </h3>
          <div className="bg-surface border border-white/5 rounded-xl overflow-hidden">
            <div className="p-4 bg-buy/[0.03] border-b border-white/5 flex items-center justify-between">
              <p className="text-[11px] text-secondary">
                <span className="text-buy font-bold uppercase tracking-tighter mr-2">Opportunity:</span> 
                Harvest <CurrencyValue isPrivate={isPrivate} value={payload.totalHarvestValue} /> in losses to offset future gains.
              </p>
            </div>
            <table className="w-full text-left">
              <tbody className="divide-y divide-white/5">
                {payload.harvestOpportunities.map((opp: any) => (
                  <tr key={opp.amfiCode} className="hover:bg-white/[0.01]">
                    <td className="px-6 py-4">
                      <p className="text-[13px] text-primary font-medium">{opp.schemeName}</p>
                      <p className="text-[9px] text-muted uppercase tracking-widest mt-0.5">Bucket: {opp.taxBucket}</p>
                    </td>
                    <td className="px-6 py-4 text-right">
                      <p className="text-[10px] text-muted uppercase mb-0.5">Harvestable</p>
                      <div className="text-[13px] text-buy font-medium tabular-nums">
                        <CurrencyValue isPrivate={isPrivate} value={opp.harvestableAmount} />
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-start gap-3">
                        <div>
                          <p className="text-[10px] text-muted uppercase mb-0.5">Proxy Recommendation</p>
                          <p className="text-[11px] text-accent font-medium">{opp.proxySchemeRecommendation}</p>
                        </div>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* SECTION 5: EXIT QUEUE */}
      {payload.exitQueue?.length > 0 && (
        <section className="space-y-6">
          <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
            <TrendingUp size={12} className="text-exit rotate-180" /> Priority Exit Queue
          </h3>
          <div className="bg-surface border border-white/5 rounded-xl overflow-hidden">
            <div className="p-4 bg-exit/[0.03] border-b border-white/5 flex items-center justify-between">
              <p className="text-[11px] text-secondary">
                <span className="text-exit font-bold uppercase tracking-tighter mr-2">Warning:</span> 
                {payload.droppedFundsCount} dropped funds identified for liquidation.
              </p>
            </div>
            <table className="w-full text-left">
              <tbody className="divide-y divide-white/5">
                {payload.exitQueue.map((signal: TacticalSignal) => (
                  <tr key={signal.schemeName} className="hover:bg-white/[0.01]">
                    <td colSpan={3} className="p-2">
                      <RecommendationDetailCard
                        signal={{
                          ...signal,
                          reasoningMetadata: resolveReasoningMetadata(signal)
                        }}
                        isPrivate={isPrivate}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
