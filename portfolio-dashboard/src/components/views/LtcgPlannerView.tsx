import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { Calendar, Save, TrendingUp, AlertTriangle } from 'lucide-react';
import CurrencyValue from '../ui/CurrencyValue';
import type { ExitScheduleItem } from '../../types/signals';

export default function LtcgPlannerView({ pan, isPrivate }: { pan: string, isPrivate: boolean }) {
  const [schedule, setSchedule] = useState<ExitScheduleItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetch(`/api/dashboard/ltcg-exit-schedule/${pan}`)
      .then(r => r.json())
      .then(data => {
        setSchedule(data);
        setLoading(false);
      })
      .catch(err => {
        console.error("Failed to fetch LTCG schedule:", err);
        setLoading(false);
      });
  }, [pan]);

  if (loading) return <div className="py-20 text-center text-muted animate-pulse font-black uppercase tracking-widest text-[10px]">Calculating Optimal Exit Path...</div>;

  const totalSaving = schedule.reduce((acc, item) => acc + item.saving, 0);
  const currentFYExits = schedule.filter(item => item.suggestedFY === 'CURRENT_FY');
  const nextFYExits = schedule.filter(item => item.suggestedFY === 'NEXT_FY');

  return (
    <div className="space-y-10 pb-32">
      <header className="flex flex-col md:flex-row md:items-end justify-between gap-6">
        <div>
          <h2 className="text-muted text-[10px] font-black uppercase tracking-[0.2em] mb-1">Tax-Optimal Exit Planner</h2>
          <p className="text-xl font-black text-primary tracking-tight">LTCG Exemption Maximization · FY Strategy</p>
        </div>
        <div className="bg-buy/10 border border-buy/20 px-6 py-4 rounded-2xl">
          <p className="text-[9px] font-black text-buy uppercase tracking-widest mb-1">Total Strategy Savings</p>
          <p className="text-2xl font-black text-buy tabular-nums tracking-tighter">
            <CurrencyValue isPrivate={isPrivate} value={totalSaving} />
          </p>
        </div>
      </header>

      {/* SUMMARY BARS */}
      <section className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl">
          <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.2em] mb-6 flex items-center gap-2">
            <Calendar size={12} className="text-accent" /> Current FY Window
          </h3>
          <div className="space-y-2">
            <div className="flex justify-between text-[10px] font-black uppercase tracking-widest">
              <span className="text-muted">Exit Capacity Used</span>
              <span className="text-primary">{currentFYExits.length} Funds</span>
            </div>
            <div className="h-2 bg-white/5 rounded-full overflow-hidden">
              <div className="h-full bg-accent rounded-full" style={{ width: `${Math.min(100, (currentFYExits.length / Math.max(1, schedule.length)) * 100)}%` }} />
            </div>
          </div>
        </div>
        <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl">
          <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.2em] mb-6 flex items-center gap-2">
            <TrendingUp size={12} className="text-buy" /> Next FY Deferrals
          </h3>
          <div className="space-y-2">
            <div className="flex justify-between text-[10px] font-black uppercase tracking-widest">
              <span className="text-muted">Tax Savings Locked</span>
              <span className="text-buy"><CurrencyValue isPrivate={isPrivate} value={nextFYExits.reduce((a, b) => a + b.saving, 0)} /></span>
            </div>
            <div className="h-2 bg-white/5 rounded-full overflow-hidden">
              <div className="h-full bg-buy rounded-full" style={{ width: `${Math.min(100, (nextFYExits.length / Math.max(1, schedule.length)) * 100)}%` }} />
            </div>
          </div>
        </div>
      </section>

      {/* SCHEDULE TABLE */}
      <section className="space-y-4">
        {schedule.map((item, idx) => (
          <motion.div 
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: idx * 0.05 }}
            key={item.schemeName}
            className={`p-6 rounded-[2rem] border transition-all ${
              item.suggestedFY === 'CURRENT_FY' 
                ? 'bg-buy/5 border-buy/10 hover:border-buy/30' 
                : 'bg-warning/5 border-warning/10 hover:border-warning/30'
            }`}
          >
            <div className="flex flex-col lg:flex-row gap-8 items-center">
              <div className="flex-1 min-w-0 w-full lg:w-auto">
                <div className="flex items-center gap-3 mb-2">
                  <span className={`px-2 py-0.5 rounded text-[8px] font-black uppercase tracking-widest ${
                    item.suggestedFY === 'CURRENT_FY' ? 'bg-buy/20 text-buy' : 'bg-warning/20 text-warning'
                  }`}>
                    {item.suggestedFY === 'CURRENT_FY' ? 'Exit Now' : 'Defer to April 1'}
                  </span>
                  <h4 className="text-sm font-black text-primary truncate tracking-tight">{item.schemeName}</h4>
                </div>
                <p className="text-[10px] text-muted font-bold uppercase tracking-widest leading-relaxed">
                  {item.reason}
                </p>
              </div>

              <div className="flex items-center gap-12 w-full lg:w-auto shrink-0 pt-4 lg:pt-0 border-t lg:border-t-0 border-white/5">
                <div className="text-center lg:text-right">
                  <p className="text-[9px] font-black text-muted uppercase tracking-widest mb-1">Current Value</p>
                  <p className="text-sm font-black text-primary tabular-nums">
                    <CurrencyValue isPrivate={isPrivate} value={item.currentValue} />
                  </p>
                </div>
                
                <div className="h-8 w-px bg-white/5 hidden md:block" />

                <div className="grid grid-cols-2 gap-8">
                  <div className="text-center lg:text-right">
                    <p className="text-[9px] font-black text-muted uppercase tracking-widest mb-1">Tax This FY</p>
                    <p className="text-sm font-black text-exit tabular-nums">
                      <CurrencyValue isPrivate={isPrivate} value={item.taxIfThisFY} />
                    </p>
                  </div>
                  <div className="text-center lg:text-right">
                    <p className="text-[9px] font-black text-muted uppercase tracking-widest mb-1">Tax Next FY</p>
                    <p className="text-sm font-black text-buy tabular-nums">
                      <CurrencyValue isPrivate={isPrivate} value={item.taxIfNextFY} />
                    </p>
                  </div>
                </div>

                {item.saving > 0 && (
                  <div className="bg-buy/10 px-4 py-2 rounded-xl border border-buy/20 text-center lg:text-right">
                    <p className="text-[8px] font-black text-buy uppercase tracking-widest">Saving</p>
                    <p className="text-xs font-black text-buy tabular-nums">
                      +<CurrencyValue isPrivate={isPrivate} value={item.saving} />
                    </p>
                  </div>
                )}
              </div>
            </div>

            {item.daysToLtcgConversion > 0 && item.suggestedFY === 'NEXT_FY' && (
              <div className="mt-6 pt-6 border-t border-white/5">
                <div className="flex justify-between items-center text-[9px] font-black uppercase tracking-widest mb-2">
                  <span className="text-muted flex items-center gap-2">
                    <AlertTriangle size={10} className="text-warning" /> STCG Lock-in
                  </span>
                  <span className="text-warning">{item.daysToLtcgConversion} Days Remaining</span>
                </div>
                <div className="h-1 bg-white/5 rounded-full overflow-hidden">
                  <div 
                    className="h-full bg-warning rounded-full" 
                    style={{ width: `${Math.max(5, 100 - (item.daysToLtcgConversion / 3.65))}%` }} 
                  />
                </div>
              </div>
            )}
          </motion.div>
        ))}

        {schedule.length === 0 && (
          <div className="py-20 text-center border border-dashed border-white/10 rounded-[2.5rem] opacity-40">
            <Save size={40} className="mx-auto mb-4" />
            <p className="text-[10px] font-black uppercase tracking-widest">No dropped funds require scheduling</p>
          </div>
        )}
      </section>
    </div>
  );
}
