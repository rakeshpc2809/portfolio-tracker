import { motion } from 'framer-motion';
import { Zap, Info, ShieldAlert, TrendingUp, Receipt, Target } from 'lucide-react';
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
  const payload = portfolioData.tacticalPayload || { sipPlan: [], opportunisticSignals: [], exitQueue: [] };

  const exitCount = payload.exitQueue?.length || 0;
  const sipTotal = payload.sipPlan?.reduce((a: number, s: any) => a + s.amount, 0) || 0;
  const opCount = payload.opportunisticSignals?.length || 0;

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
          <h2 className="text-muted text-[10px] font-medium uppercase tracking-[0.2em] mb-1">Decision Support</h2>
          <p className="text-xl font-medium text-primary tracking-tight">Today's brief · {dateStr}</p>
        </div>

        {/* Design Improvement 4: Portfolio Health Banner */}
        <div className="flex flex-wrap items-center gap-6 px-6 py-4 bg-white/[0.02] border border-white/5 rounded-xl">
          <div className="flex items-center gap-3">
            <div className={`w-2 h-2 rounded-full ${exitCount > 0 ? 'bg-exit' : 'bg-buy'}`} />
            <span className="text-[11px] text-secondary">
              {exitCount > 0 
                ? `${exitCount} dropped funds need clearing` 
                : 'No exit actions pending'}
            </span>
          </div>
          <div className="flex items-center gap-3 border-l border-white/5 pl-6">
            <div className="w-2 h-2 rounded-full bg-accent" />
            <span className="text-[11px] text-secondary">
              SIP this month: <CurrencyValue isPrivate={isPrivate} value={sipTotal} className="text-primary font-medium" />
            </span>
          </div>
          {opCount > 0 && (
            <div className="flex items-center gap-3 border-l border-white/5 pl-6">
              <div className="w-2 h-2 rounded-full bg-warning" />
              <span className="text-[11px] text-secondary">{opCount} opportunistic signals active</span>
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
                    <p className="text-[10px] uppercase tracking-widest non-italic">Check Google Sheet strategy & ensure funds have sip% &gt; 0</p>
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

      {/* SECTION 2: OPPORTUNISTIC TOP-UPS */}
      <section className="space-y-6">
        <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
          <Zap size={12} className="text-warning" /> Opportunistic Signals
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
                <span className="text-[10px] font-bold uppercase tracking-widest text-buy">BUY OPPORTUNITY</span>
                <ConvictionBadge score={signal.convictionScore} />
              </div>
              <p className="text-[13px] font-medium text-primary mb-1 truncate group-hover:text-white transition-colors">{signal.schemeName}</p>
              <div className="text-2xl font-medium tabular-nums mb-4 text-buy">
                <CurrencyValue isPrivate={isPrivate} value={signal.amount} />
              </div>
              <p className="text-[11px] text-secondary leading-relaxed line-clamp-2">
                {signal.justifications[0]}
              </p>
            </motion.div>
          ))}
          
          {payload.opportunisticSignals.length === 0 && (
            <div className="col-span-full py-8 flex items-center gap-3 bg-white/[0.02] border border-white/5 rounded-xl px-6">
              <Info className="text-muted" size={16} />
              <p className="text-muted text-[11px] font-medium uppercase tracking-widest">No opportunistic entry points detected at current NAV levels.</p>
            </div>
          )}
        </motion.div>
      </section>

      {/* SECTION 3: EXIT QUEUE */}
      {payload.exitQueue.length > 0 && (
        <section className="space-y-6">
          <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
            <TrendingUp size={12} className="text-exit rotate-180" /> Priority Exit Queue
          </h3>
          <div className="bg-surface border border-white/5 rounded-xl overflow-hidden">
            <div className="p-4 bg-exit/[0.03] border-b border-white/5 flex items-center justify-between">
              <p className="text-[11px] text-secondary">
                <span className="text-exit font-bold uppercase tracking-tighter mr-2">Warning:</span> 
                {payload.droppedFundsCount} dropped funds holding <CurrencyValue isPrivate={isPrivate} value={payload.totalExitValue} /> identified for liquidation.
              </p>
              <div className="flex items-center gap-2">
                <ShieldAlert size={14} className="text-warning" />
                <span className="text-[10px] font-bold uppercase tracking-widest text-muted">Tax Efficiency Optimized</span>
              </div>
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
