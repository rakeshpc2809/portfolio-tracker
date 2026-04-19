import { motion, type Variants } from 'framer-motion';
import { useMemo } from 'react';
import { 
  Zap, 
  Target, 
  Scissors, 
  ShieldAlert, 
  TrendingUp, 
  Activity,
  Layers,
  Wallet,
  ArrowRight
} from 'lucide-react';
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
  const rebalancingTrades = payload.rebalancingTrades || [];
  const droppedFundSummaries = payload.droppedFundSummaries || [];

  const totalRebalanceSell = rebalancingTrades.reduce((acc: number, t: any) => acc + t.sellAmount, 0);
  const totalRebalanceTax = rebalancingTrades.reduce((acc: number, t: any) => acc + t.estimatedSellTax, 0);
  const totalRebalanceBuy = rebalancingTrades.reduce((acc: number, t: any) => acc + t.buyAmount, 0);

  const standingSips = useMemo(() => 
    (payload.sipPlan || []).filter((s: any) => s.amount > 0 && s.mode === "SIP_STANDING"),
    [payload.sipPlan]
  );

  const additionalSips = useMemo(() => 
    (payload.sipPlan || []).filter((s: any) => s.amount > 0 && s.mode === "SIP_ADDITIONAL"),
    [payload.sipPlan]
  );

  const container: Variants = {
    hidden: { opacity: 0 },
    show: {
      opacity: 1,
      transition: { staggerChildren: 0.1 }
    }
  };

  const bentoItem: Variants = {
    hidden: { opacity: 0, y: 30, scale: 0.98 },
    show: { 
      opacity: 1, 
      y: 0, 
      scale: 1, 
      transition: { 
        type: "spring", 
        damping: 25, 
        stiffness: 120 
      } 
    }
  };

  return (
    <motion.div 
      variants={container}
      initial="hidden"
      animate="show"
      className="grid grid-cols-1 md:grid-cols-12 gap-6 pb-24"
    >
      {/* 1. PORTFOLIO HERO CARD (4x2 or similar) */}
      <motion.div 
        variants={bentoItem}
        className="md:col-span-8 glass-card-premium p-10 flex flex-col justify-between relative overflow-hidden group min-h-[360px]"
      >
        <div className="absolute top-0 right-0 p-8 opacity-5 group-hover:opacity-10 transition-opacity">
          <TrendingUp size={160} />
        </div>
        
        {/* Abstract Gradient Orbs for Hero */}
        <div className="absolute -top-24 -left-24 w-64 h-64 bg-accent/10 rounded-full blur-[100px] pointer-events-none" />
        <div className="absolute -bottom-24 -right-24 w-64 h-64 bg-buy/5 rounded-full blur-[100px] pointer-events-none" />

        <div className="space-y-4 relative z-10">
          <div className="flex items-center gap-3">
             <div className="h-px w-8 bg-accent/40" />
             <h2 className="text-accent text-[10px] font-black uppercase tracking-[0.4em]">Portfolio Orbit</h2>
          </div>
          
          <div className="space-y-1">
            <p className="text-[11px] font-black uppercase tracking-widest text-muted/60">Current Liquidity Value</p>
            <div className="text-6xl font-black text-primary tracking-tighter tabular-nums flex items-baseline gap-4">
              <CurrencyValue isPrivate={isPrivate} value={portfolioData.currentValueAmount} />
              <motion.span 
                initial={{ opacity: 0, x: -10 }}
                animate={{ opacity: 1, x: 0 }}
                className={`text-sm font-black tracking-widest uppercase px-3 py-1 rounded-lg bg-black/20 border border-white/5 ${parseFloat(portfolioData.overallReturn) >= 0 ? 'text-buy glow-buy' : 'text-exit glow-exit'}`}>
                {portfolioData.overallReturn}
              </motion.span>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-8 pt-10 border-t border-white/5 relative z-10">
          <div className="space-y-1">
            <p className="text-[9px] font-black uppercase tracking-widest text-muted/40 mb-1">Overall XIRR</p>
            <p className={`text-2xl font-black tabular-nums tracking-tight ${parseFloat(portfolioData.overallXirr) >= 0 ? 'text-buy' : 'text-exit'}`}>
              {portfolioData.overallXirr}
            </p>
          </div>
          <div className="space-y-1">
            <p className="text-[9px] font-black uppercase tracking-widest text-muted/40 mb-1">Capital Invested</p>
            <p className="text-2xl font-black tabular-nums text-primary tracking-tight">
              <CurrencyValue isPrivate={isPrivate} value={portfolioData.currentInvestedAmount} />
            </p>
          </div>
          <div className="group cursor-help relative space-y-2">
            <div className="flex justify-between items-center">
              <p className="text-[9px] font-black uppercase tracking-widest text-muted/40 group-hover:text-harvest transition-colors">Tax Headroom</p>
              <span className="text-[8px] font-black text-harvest/60 tabular-nums">₹1.25L Cap</span>
            </div>
            <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden border border-white/5">
              <motion.div 
                initial={{ width: 0 }}
                animate={{ width: `${Math.min(100, (portfolioData.fyLtcgAlreadyRealized / 125000) * 100)}%` }}
                className="h-full bg-gradient-to-r from-harvest to-accent shadow-[0_0_12px_rgba(180,190,254,0.3)]"
              />
            </div>
            <div className="flex justify-between items-baseline">
               <p className="text-[10px] font-black text-harvest tabular-nums">
                {isPrivate ? '••••' : formatCurrency(125000 - portfolioData.fyLtcgAlreadyRealized)} <span className="text-[8px] opacity-40">LEFT</span>
              </p>
              <span className="text-[8px] font-bold text-muted/30">FY 25-26</span>
            </div>
          </div>
        </div>
      </motion.div>

      {/* 2. TACTICAL OVERVIEW CARD (4x2) */}
      <motion.div 
        variants={bentoItem}
        className="md:col-span-4 bg-surface/40 backdrop-blur-2xl border border-white/[0.04] rounded-[2.5rem] p-10 flex flex-col shadow-2xl relative overflow-hidden group"
      >
        <h2 className="text-warning text-[10px] font-black uppercase tracking-[0.4em] mb-8">System Signals</h2>
        <div className="space-y-6 flex-1">
          {[
            { label: 'Critical Actions', value: `${exitCount + sellCount} Assets`, icon: <ShieldAlert size={18}/>, color: exitCount + sellCount > 0 ? 'text-exit' : 'text-buy', bg: exitCount + sellCount > 0 ? 'bg-exit/10' : 'bg-buy/10' },
            { label: 'Budget Ready', value: formatCurrency(sipTotal), icon: <Zap size={18} className="fill-current"/>, color: 'text-accent', bg: 'bg-accent/10' },
            { label: 'Tax Optimized', value: `${harvestCount} Signals`, icon: <Scissors size={18}/>, color: 'text-harvest', bg: 'bg-harvest/10' }
          ].map((stat) => (
            <div key={stat.label} className="flex items-center gap-5 group/stat">
              <div className={`p-4 rounded-2xl ${stat.bg} ${stat.color} group-hover/stat:scale-110 transition-transform`}>
                {stat.icon}
              </div>
              <div>
                <p className="text-[9px] font-black uppercase tracking-widest text-muted opacity-60 mb-0.5">{stat.label}</p>
                <p className={`text-lg font-black tabular-nums ${stat.color}`}>{isPrivate && stat.label.includes('Budget') ? '••••' : stat.value}</p>
              </div>
            </div>
          ))}
        </div>
        <div className="pt-6 border-t border-white/5 mt-6">
          <div className="flex items-center gap-3 text-muted">
            <Activity size={12} className="animate-pulse text-buy" />
            <span className="text-[9px] font-bold uppercase tracking-[0.2em]">Engine Pulse Nominal</span>
          </div>
        </div>
      </motion.div>

      {/* 3. STRUCTURAL DEPLOYMENT CARD (8x4 or 12x4) */}
      <motion.div 
        variants={bentoItem}
        className="md:col-span-12 bg-surface/40 backdrop-blur-2xl border border-white/[0.04] rounded-[2.5rem] p-10 shadow-2xl space-y-10"
      >
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-8">
          <div className="space-y-2">
            <h2 className="text-buy text-[10px] font-black uppercase tracking-[0.4em]">SIP This Month</h2>
            <p className="text-2xl font-black text-primary tracking-tighter tabular-nums flex items-center gap-3">
              <Target size={24} className="text-accent" /> Standing Instructions
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-8 bg-black/20 p-6 rounded-[2rem] border border-white/5">
            <div className="space-y-2">
              <div className="flex justify-between text-[9px] font-black uppercase tracking-widest text-muted">
                <span>Monthly Budget</span>
                <span className="text-accent font-black tracking-tighter"><CurrencyValue isPrivate={isPrivate} value={sipAmount} /></span>
              </div>
              <input 
                type="range" min="0" max="1000000" step="10000" 
                value={sipAmount} 
                onChange={(e) => setSipAmount(parseInt(e.target.value) || 0)}
                className="w-48 h-1.5 bg-white/10 rounded-full appearance-none cursor-pointer accent-accent hover:accent-accent-bright transition-all"
              />
            </div>
            <div className="h-10 w-px bg-white/5 hidden sm:block" />
            <div className="space-y-2">
              <label className="text-[9px] font-black uppercase tracking-widest text-muted">Tactical Lumpsum</label>
              <input 
                type="number" 
                value={lumpsum || ''} 
                onChange={(e) => setLumpsum(parseInt(e.target.value) || 0)}
                placeholder="₹0"
                className="bg-black/40 border border-white/10 rounded-xl px-4 py-2 text-sm text-primary tabular-nums font-black focus:outline-none focus:border-accent/50 transition-all w-32 shadow-inner"
              />
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {standingSips.length === 0 ? (
            <div className="col-span-full py-16 text-center border border-dashed border-white/10 rounded-[2.5rem] opacity-40">
              <Target size={40} className="mx-auto mb-4" />
              <p className="text-[10px] font-black uppercase tracking-widest">No standing SIP targets</p>
            </div>
          ) : (
            standingSips.map((s: any) => (
              <motion.div
                key={s.isin}
                whileHover={{ scale: 1.02 }}
                onClick={() => onFundClick(s.schemeName)}
                className="flex flex-col justify-between p-8 bg-surface/60 border border-white/5 rounded-[2rem] hover:border-accent/30 cursor-pointer transition-all group shadow-xl relative overflow-hidden"
              >
                <div className="flex justify-between items-start mb-6">
                  <div className={`w-3 h-3 rounded-full shadow-lg ${s.deployFlag === 'DEPLOY' ? 'bg-buy animate-pulse glow-buy' : 'bg-warning'}`} />
                  <div className={`text-[8px] font-black uppercase tracking-[0.2em] px-3 py-1 rounded-full border ${
                    s.deployFlag === 'DEPLOY' ? 'bg-buy/10 text-buy border-buy/20' : 'bg-warning/10 text-warning border-warning/20'
                  }`}>
                    {s.deployFlag === 'DEPLOY' ? 'EXECUTE' : 'AWAIT PULSE'}
                  </div>
                </div>
                <div>
                  <p className="text-lg text-primary font-black tracking-tight leading-tight mb-1 group-hover:text-white transition-colors">
                    {s.simpleName || s.schemeName}
                  </p>
                  <p className="text-[9px] text-muted font-bold uppercase tracking-widest opacity-60">Target: {s.sipPct}% Allocation</p>
                </div>
                <div className="mt-8 flex items-baseline gap-1">
                  <p className={`text-2xl font-black tabular-nums tracking-tighter ${s.deployFlag === 'DEPLOY' ? 'text-buy' : 'text-warning'}`}>
                    {isPrivate ? '••••' : `₹${(s.amount / 1000).toFixed(1)}k`}
                  </p>
                  <span className="text-[10px] font-black text-muted opacity-40 uppercase tracking-widest">Deploy</span>
                </div>
              </motion.div>
            ))
          )}
        </div>
      </motion.div>

      {/* 4. REBALANCING & OPPORTUNISTIC (6x...) */}
      <motion.div 
        variants={bentoItem}
        className="md:col-span-6 space-y-6"
      >
        <div className="flex items-center justify-between px-2">
          <div className="flex items-center gap-3">
            <Layers size={16} className="text-warning" />
            <h3 className="text-muted text-[10px] font-black uppercase tracking-[0.3em]">Portfolio Rebalancing</h3>
          </div>
          {totalRebalanceSell > 0 && (
            <span className="text-[9px] font-black text-muted uppercase tracking-widest">
              Tax Cost: <span className={totalRebalanceTax > 5000 ? 'text-exit' : 'text-buy'}><CurrencyValue isPrivate={isPrivate} value={totalRebalanceTax} /></span>
            </span>
          )}
        </div>

        {rebalancingTrades.length > 0 && (
          <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-[2rem] shadow-xl space-y-4">
             <p className="text-[10px] font-black text-muted uppercase tracking-widest leading-relaxed">
              Sell <span className="text-exit"><CurrencyValue isPrivate={isPrivate} value={totalRebalanceSell} /></span> and redeploy <span className="text-buy"><CurrencyValue isPrivate={isPrivate} value={totalRebalanceBuy} /></span> to optimize allocation.
            </p>
            <div className="space-y-3">
              {rebalancingTrades.map((trade: any, idx: number) => (
                <div key={idx} className="flex items-center gap-3 p-3 bg-black/20 rounded-xl border border-white/5">
                  <div className="flex-1 min-w-0">
                    <p className="text-[10px] font-black text-primary truncate">{trade.sellFundName.split(' ')[0]}... → {trade.buyFundName.split(' ')[0]}...</p>
                    <p className="text-[8px] font-bold text-muted uppercase tracking-widest">Rotate <CurrencyValue isPrivate={isPrivate} value={trade.buyAmount} /></p>
                  </div>
                  <ArrowRight size={12} className="text-muted" />
                  <div className="bg-buy/10 px-2 py-0.5 rounded text-[8px] font-black text-buy uppercase tracking-widest">
                    +{trade.convictionDelta.toFixed(0)} Conv.
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="flex items-center gap-3 px-2 pt-4">
          <Zap size={16} className="text-warning" />
          <h3 className="text-muted text-[10px] font-black uppercase tracking-[0.3em]">Optional Top-ups</h3>
        </div>
        <div className="space-y-4">
          {additionalSips.map((s: any) => (
            <div key={s.isin} className="flex items-center justify-between p-6 bg-surface/40 border border-white/5 rounded-[2rem] hover:border-accent/30 cursor-pointer transition-all group shadow-xl">
               <div className="min-w-0">
                  <p className="text-[11px] font-black text-primary truncate tracking-tight">{s.simpleName || s.schemeName}</p>
                  <p className="text-[8px] font-black text-muted uppercase tracking-widest mt-0.5">Strategy Gap: <CurrencyValue isPrivate={isPrivate} value={s.amount} /></p>
                </div>
                <div className="text-right pl-4">
                   <div className="bg-accent/10 px-3 py-1 rounded-full text-[9px] font-black text-accent uppercase tracking-widest">Additional</div>
                </div>
            </div>
          ))}
          {payload.opportunisticSignals.map((signal: TacticalSignal) => (
            <RecommendationDetailCard
              key={signal.schemeName}
              signal={{
                ...signal,
                reasoningMetadata: resolveReasoningMetadata(signal)
              }}
              isPrivate={isPrivate}
              defaultExpanded={signal.action === 'BUY' && (signal.returnZScore ?? 0) <= -2.0}
            />
          ))}
          {payload.opportunisticSignals.length === 0 && additionalSips.length === 0 && (
            <div className="p-8 bg-surface/20 border border-dashed border-white/10 rounded-[2rem] text-center opacity-40">
              <p className="text-[10px] font-black uppercase tracking-widest">No opportunistic signals today</p>
            </div>
          )}
        </div>
      </motion.div>

      {/* 5. TAX & ACTIVE REBALANCE (6x...) */}
      <motion.div 
        variants={bentoItem}
        className="md:col-span-6 space-y-6"
      >
        <div className="flex items-center gap-3 px-2">
          <Wallet size={16} className="text-harvest" />
          <h3 className="text-muted text-[10px] font-black uppercase tracking-[0.3em]">Optimization Stream</h3>
        </div>
        
        {/* Active Sells */}
        {payload.activeSellSignals?.length > 0 && (
          <div className="space-y-4">
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
        )}

        {/* Harvest Summary */}
        {payload.harvestOpportunities?.length > 0 && (
          <div className="bg-harvest/5 border border-harvest/20 rounded-[2rem] p-8 space-y-4 shadow-xl">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <Scissors size={18} className="text-harvest" />
                <span className="text-[11px] font-black uppercase tracking-widest text-harvest">Tax Harvest Ready</span>
              </div>
              <span className="px-3 py-1 bg-harvest/10 text-harvest rounded-full text-[9px] font-black tracking-widest">
                {harvestCount} SIGNALS
              </span>
            </div>
            <p className="text-xl font-black text-primary tracking-tight">
              Unlock <span className="text-harvest"><CurrencyValue isPrivate={isPrivate} value={payload.totalHarvestValue} /></span> in harvestable losses.
            </p>
            <button className="w-full py-3 bg-harvest/10 hover:bg-harvest/20 text-harvest border border-harvest/20 rounded-xl text-[10px] font-black uppercase tracking-[0.2em] transition-all">
              View Optimization Matrix
            </button>
          </div>
        )}

        {/* Exit Queue */}
        {droppedFundSummaries.length > 0 && (
          <div className="bg-exit/5 border border-exit/20 rounded-[2rem] p-8 space-y-6 shadow-xl">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <ShieldAlert size={18} className="text-exit" />
                <span className="text-[11px] font-black uppercase tracking-widest text-exit">Dropped Fund Decisions</span>
              </div>
            </div>
            
            <div className="space-y-4">
              {droppedFundSummaries.map((s: any) => (
                <div key={s.amfiCode} className="flex items-center justify-between p-4 bg-black/20 rounded-2xl border border-white/5">
                  <div className="min-w-0">
                    <p className="text-[11px] font-black text-primary truncate tracking-tight">{s.schemeName}</p>
                    <p className="text-[8px] font-bold text-muted uppercase tracking-widest mt-0.5">Value: <CurrencyValue isPrivate={isPrivate} value={s.currentValue} /></p>
                  </div>
                  <div className="flex flex-col items-end gap-2">
                    {s.recommendedAction === 'EXIT_NOW_TAX_FREE' && <span className="px-2 py-0.5 bg-buy/10 text-buy border border-buy/20 rounded text-[8px] font-black">Tax-Free Exit</span>}
                    {s.recommendedAction === 'WAIT_FOR_LTCG' && <span className="px-2 py-0.5 bg-warning/10 text-warning border border-warning/20 rounded text-[8px] font-black">Wait {s.daysToNextLtcg}d</span>}
                    {s.recommendedAction === 'HOLD_WAVE_RIDER' && <span className="px-2 py-0.5 bg-violet-500/10 text-violet-400 border border-violet-500/20 rounded text-[8px] font-black">Momentum Hold</span>}
                    {s.recommendedAction === 'EXIT_NOW' && <span className="px-2 py-0.5 bg-exit/10 text-exit border border-exit/20 rounded text-[8px] font-black">Exit Recommended</span>}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {payload.harvestOpportunities?.length === 0 && droppedFundSummaries.length === 0 && payload.activeSellSignals?.length === 0 && (
          <div className="p-8 bg-surface/20 border border-dashed border-white/10 rounded-[2rem] text-center opacity-40">
            <p className="text-[10px] font-black uppercase tracking-widest">Orbit is clean. No active optimizations.</p>
          </div>
        )}
      </motion.div>
    </motion.div>
  );
}
