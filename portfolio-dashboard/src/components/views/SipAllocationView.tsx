import { motion } from 'framer-motion';
import { Target, TrendingUp, Info, AlertCircle, CheckCircle2 } from 'lucide-react';
import CurrencyValue from '../ui/CurrencyValue';
import { formatCurrency } from '../../utils/formatters';

export default function SipAllocationView({ 
  portfolioData, 
  sipAmount, 
  isPrivate 
}: { 
  portfolioData: any;
  sipAmount: number;
  isPrivate: boolean;
}) {
  const payload = portfolioData.tacticalPayload || { sipPlan: [] };
  const sipPlan = payload.sipPlan || [];

  const totalAllocated = sipPlan.reduce((acc: number, item: any) => acc + item.amount, 0);

  return (
    <motion.div 
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className="space-y-8 pb-24"
    >
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-6 bg-surface/30 backdrop-blur-xl border border-white/5 p-8 rounded-[2.5rem]">
        <div className="space-y-2">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-accent/20 rounded-lg text-accent">
              <Target size={20} />
            </div>
            <h2 className="text-xl font-black tracking-tight text-primary">SIP Allocation Strategy</h2>
          </div>
          <p className="text-sm text-muted">Smart deployment of your ₹{formatCurrency(sipAmount)} monthly budget.</p>
        </div>

        <div className="flex items-center gap-8 bg-black/20 px-8 py-4 rounded-2xl border border-white/5">
          <div>
            <p className="text-[9px] font-black uppercase tracking-widest text-muted mb-1">Allocated</p>
            <p className="text-xl font-black text-buy tabular-nums">
              <CurrencyValue value={totalAllocated} isPrivate={isPrivate} />
            </p>
          </div>
          <div className="w-px h-8 bg-white/5" />
          <div>
            <p className="text-[9px] font-black uppercase tracking-widest text-muted mb-1">Residual</p>
            <p className="text-xl font-black text-primary tabular-nums">
              <CurrencyValue value={sipAmount - totalAllocated} isPrivate={isPrivate} />
            </p>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4">
        {sipPlan.length === 0 ? (
          <div className="py-20 text-center border border-dashed border-white/10 rounded-[2.5rem] opacity-40">
            <p className="text-sm font-bold uppercase tracking-widest">No SIP plan generated. Check strategy settings.</p>
          </div>
        ) : (
          sipPlan.map((item: any, idx: number) => (
            <motion.div
              key={item.isin}
              initial={{ opacity: 0, x: -20 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: idx * 0.05 }}
              className="group bg-surface/40 hover:bg-surface/60 border border-white/5 hover:border-accent/30 rounded-[2rem] p-6 transition-all flex flex-col md:flex-row md:items-center gap-6"
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-3 mb-2">
                  <span className={`w-2 h-2 rounded-full ${item.deployFlag === 'DEPLOY' ? 'bg-buy shadow-[0_0_8px_rgba(166,227,161,0.6)]' : 'bg-warning'}`} />
                  <h3 className="text-sm font-black text-primary truncate group-hover:text-white transition-colors">
                    {item.schemeName}
                  </h3>
                </div>
                <div className="flex flex-wrap gap-3">
                  <span className="text-[8px] font-black uppercase tracking-widest px-2 py-0.5 bg-white/5 rounded text-muted">
                    {item.category || 'Equity'}
                  </span>
                  <span className="text-[8px] font-black uppercase tracking-widest px-2 py-0.5 bg-accent/10 rounded text-accent">
                    Target: {item.sipPct}%
                  </span>
                  {item.deployFlag === 'DEPLOY' ? (
                    <span className="flex items-center gap-1 text-[8px] font-black uppercase tracking-widest text-buy">
                      <CheckCircle2 size={10} /> Market Signal: Active
                    </span>
                  ) : (
                    <span className="flex items-center gap-1 text-[8px] font-black uppercase tracking-widest text-warning">
                      <AlertCircle size={10} /> Awaiting Signal
                    </span>
                  )}
                </div>
              </div>

              <div className="flex items-center gap-6 text-right">
                <div className="space-y-1">
                  <p className="text-[9px] font-black uppercase tracking-widest text-muted">Allocation</p>
                  <p className="text-2xl font-black text-primary tabular-nums">
                    <CurrencyValue value={item.amount} isPrivate={isPrivate} />
                  </p>
                </div>
                <div className="p-3 rounded-2xl bg-white/5 group-hover:bg-accent/20 text-muted group-hover:text-accent transition-all">
                  <TrendingUp size={18} />
                </div>
              </div>
            </motion.div>
          ))
        )}
      </div>

      <div className="bg-accent/5 border border-accent/10 rounded-[2rem] p-8 flex gap-6 items-start">
        <Info className="text-accent shrink-0 mt-1" size={20} />
        <div className="space-y-2">
          <h4 className="text-[11px] font-black uppercase tracking-widest text-accent">Allocation Logic</h4>
          <p className="text-xs text-muted leading-relaxed">
            The SIP allocation engine prioritizes funds where the current weight is below your defined target. 
            <strong> EXECUTE</strong> signals are generated for funds with neutral or bullish momentum. 
            For funds in a <strong>BEAR</strong> regime, the engine waits for a technical pulse before deploying capital to avoid "catching falling knives".
          </p>
        </div>
      </div>
    </motion.div>
  );
}
