import { motion } from 'framer-motion';
import { Zap, TrendingUp, Receipt, Target, ArrowDownRight, Scissors } from 'lucide-react';
import ConvictionBadge from '../ui/ConvictionBadge';
import CurrencyValue from '../ui/CurrencyValue';

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

  const container = {
    hidden: { opacity: 0 },
    show: {
      opacity: 1,
      transition: { staggerChildren: 0.05 }
    }
  };

  const item = {
    hidden: { opacity: 0, y: 10 },
    show: { opacity: 1, y: 0 }
  };

  return (
    <div className="space-y-12 pb-32">
      <header className="space-y-6">
        <div>
          <h2 className="text-muted text-[10px] font-medium uppercase tracking-[0.2em] mb-1">Intelligent Tactical Orchestrator</h2>
          <p className="text-xl font-medium text-primary tracking-tight">Today's brief · {dateStr}</p>
        </div>

        <div className="flex flex-wrap items-center gap-6 px-6 py-4 bg-white/[0.02] border border-white/5 rounded-xl">
          <div className="flex items-center gap-3">
            <div className={`w-2 h-2 rounded-full ${exitCount + sellCount > 0 ? 'bg-exit' : 'bg-buy'}`} />
            <span className="text-[11px] text-secondary">
              {exitCount + sellCount > 0 
                ? `${exitCount + sellCount} funds need attention/exit` 
                : 'No urgent sell actions'}
            </span>
          </div>
          <div className="flex items-center gap-3 border-l border-white/5 pl-6">
            <div className="w-2 h-2 rounded-full bg-accent" />
            <span className="text-[11px] text-secondary">
              SIP Budget: <CurrencyValue isPrivate={isPrivate} value={sipTotal} className="text-primary font-medium" />
            </span>
          </div>
          {harvestCount > 0 && (
            <div className="flex items-center gap-3 border-l border-white/5 pl-6">
              <div className="w-2 h-2 rounded-full bg-buy" />
              <span className="text-[11px] text-secondary">{harvestCount} TLH opportunities identified</span>
            </div>
          )}
        </div>
      </header>

      {/* SECTION 1: MONTHLY SIP PLAN */}
      <section className="space-y-6">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-6">
          <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
            <Target size={12} className="text-accent" /> Monthly SIP Strategy
          </h3>
          <div className="flex flex-wrap items-center gap-8">
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2 text-[10px] uppercase tracking-widest text-muted">
                <span>SIP Amount</span>
                <CurrencyValue isPrivate={isPrivate} value={sipAmount} className="text-primary tabular-nums font-medium" />
              </div>
              <input 
                type="range" min="0" max="200000" step="1000" 
                value={sipAmount} 
                onChange={(e) => setSipAmount(parseInt(e.target.value) || 0)}
                className="w-32 h-1 bg-white/10 rounded-lg appearance-none cursor-pointer accent-accent"
              />
            </div>
            <div className="flex items-center gap-3">
              <label className="text-[10px] uppercase tracking-widest text-muted">Extra Lumpsum</label>
              <input 
                type="number" 
                value={lumpsum || ''} 
                onChange={(e) => setLumpsum(parseInt(e.target.value) || 0)}
                placeholder="₹0"
                className="bg-surface-elevated border border-white/10 rounded-md px-3 py-1.5 text-xs text-primary tabular-nums focus:outline-none focus:border-indigo-500/50 transition-colors w-28"
              />
            </div>
          </div>
        </div>

        <div className="bg-surface border border-white/5 rounded-xl overflow-hidden">
          <table className="w-full text-left">
            <thead className="bg-white/[0.02] text-muted text-[10px] uppercase tracking-widest border-b border-white/5">
              <tr>
                <th className="px-6 py-4 font-medium">Fund Name</th>
                <th className="px-6 py-4 font-medium text-right">Strategy %</th>
                <th className="px-6 py-4 font-medium text-right">Deploy Amount</th>
                <th className="px-6 py-4 font-medium text-right">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {payload.sipPlan.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-6 py-12 text-center text-muted text-xs italic">
                    <p className="mb-2">SIP plan not loaded.</p>
                  </td>
                </tr>
              ) : (
                payload.sipPlan.map((s: any) => (
                  <tr key={s.isin} className="hover:bg-white/[0.01] transition-colors group">
                    <td className="px-6 py-4">
                      <button onClick={() => onFundClick(s.schemeName)} className="text-[13px] text-primary hover:text-accent transition-colors truncate max-w-xs block text-left">
                        {s.schemeName}
                      </button>
                    </td>
                    <td className="px-6 py-4 text-right text-[12px] text-secondary tabular-nums">{s.sipPct}%</td>
                    <td className="px-6 py-4 text-right text-[12px] text-buy font-medium tabular-nums">
                      <CurrencyValue isPrivate={isPrivate} value={s.amount} />
                    </td>
                    <td className="px-6 py-4 text-right">
                      <span className={`text-[9px] font-bold uppercase tracking-widest px-2 py-0.5 rounded ${
                        s.deployFlag === 'DEPLOY' ? 'text-buy bg-buy/10' : 'text-warning bg-warning/10'
                      }`}>
                        {s.deployFlag === 'DEPLOY' ? 'Ready' : 'Expensive'}
                      </span>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
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
          {payload.opportunisticSignals.map((signal: any) => (
            <motion.div 
              key={signal.schemeName}
              variants={item}
              onClick={() => onFundClick(signal.schemeName)}
              className="p-5 rounded-xl border border-buy/10 bg-buy/[0.02] hover:border-buy/20 transition-all cursor-pointer group hover:bg-white/[0.02]"
            >
              <div className="flex items-center justify-between mb-4">
                <span className="text-[10px] font-bold uppercase tracking-widest text-buy">{signal.action}</span>
                <ConvictionBadge score={signal.convictionScore} />
              </div>
              <p className="text-[13px] font-medium text-primary mb-1 truncate group-hover:text-white transition-colors">{signal.schemeName}</p>
              <div className="text-xl font-medium tabular-nums mb-3 text-buy">
                <CurrencyValue isPrivate={isPrivate} value={parseFloat(signal.amount)} />
              </div>
              <p className="text-[11px] text-secondary leading-relaxed line-clamp-2">
                {signal.justifications[0]}
              </p>
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
            {payload.activeSellSignals.map((signal: any) => (
              <div 
                key={signal.schemeName}
                onClick={() => onFundClick(signal.schemeName)}
                className={`p-5 rounded-xl border transition-all cursor-pointer group hover:bg-white/[0.02] ${
                  signal.action === 'SELL' ? 'border-exit/20 bg-exit/[0.02]' : 'border-warning/20 bg-warning/[0.02]'
                }`}
              >
                <div className="flex items-center justify-between mb-4">
                  <span className={`text-[10px] font-bold uppercase tracking-widest ${
                    signal.action === 'SELL' ? 'text-exit' : 'text-warning'
                  }`}>{signal.action} SIGNAL</span>
                  <ConvictionBadge score={signal.convictionScore} />
                </div>
                <p className="text-[13px] font-medium text-primary mb-1 truncate">{signal.schemeName}</p>
                <div className={`text-xl font-medium tabular-nums mb-3 ${
                  signal.action === 'SELL' ? 'text-exit' : 'text-warning'
                }`}>
                  {signal.action === 'SELL' 
                    ? <CurrencyValue isPrivate={isPrivate} value={parseFloat(signal.amount)} />
                    : 'TAX-LOCKED'
                  }
                </div>
                <div className="space-y-2">
                  {signal.justifications.slice(0, 2).map((j: string, idx: number) => (
                    <p key={idx} className="text-[10px] text-secondary leading-relaxed border-l border-white/10 pl-2">
                      {j}
                    </p>
                  ))}
                </div>
              </div>
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
                {payload.exitQueue.map((s: any) => (
                  <tr key={s.schemeName} className="hover:bg-white/[0.01]">
                    <td className="px-6 py-4">
                      <p className="text-[13px] text-primary font-medium">{s.schemeName}</p>
                      <p className="text-[10px] text-muted uppercase tracking-widest mt-0.5">{s.fundStatus}</p>
                    </td>
                    <td className="px-6 py-4 text-right">
                      <p className="text-[10px] text-muted uppercase mb-0.5">Liquidation</p>
                      <div className="text-[13px] text-exit font-medium tabular-nums">
                        <CurrencyValue isPrivate={isPrivate} value={s.amount} />
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-start gap-2 max-w-xs">
                        <Receipt size={12} className="text-muted mt-0.5 shrink-0" />
                        <p className="text-[11px] text-secondary leading-relaxed">{s.justifications[0]}</p>
                      </div>
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
