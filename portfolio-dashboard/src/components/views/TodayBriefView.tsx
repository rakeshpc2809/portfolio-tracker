import { motion } from 'framer-motion';
import { Zap, Info, ShieldAlert } from 'lucide-react';
import { buildPlainEnglishReason } from '../../utils/formatters';
import ConvictionBadge from '../ui/ConvictionBadge';
import MetricWithTooltip from '../ui/MetricWithTooltip';
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
  
  const actionSignals = (portfolioData.schemeBreakdown || []).filter((s: any) => s.action === 'BUY' || s.action === 'EXIT');
  const holdCount = (portfolioData.schemeBreakdown || []).filter((s: any) => s.action === 'HOLD').length;

  const exitProceeds = (portfolioData.schemeBreakdown || [])
    .filter((s: any) => s.action === 'EXIT')
    .reduce((acc: number, s: any) => acc + (s.signalAmount || 0), 0);

  const totalWarChest = sipAmount + lumpsum + exitProceeds;

  const buySignals = (portfolioData.schemeBreakdown || []).filter((s: any) => s.action === 'BUY');
  const apiTotalBuyRequest = buySignals.reduce((acc: number, s: any) => acc + (s.signalAmount || 0), 0);

  const scaledBuySignals = buySignals.map((s: any) => {
    const weight = (s.signalAmount || 0) / (apiTotalBuyRequest || 1);
    return {
      ...s,
      displayAmount: weight * totalWarChest
    };
  });

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
    <div className="space-y-10 pb-32">
      <header>
        <h2 className="text-muted text-[10px] font-medium uppercase tracking-[0.2em] mb-1">Portfolio intelligence</h2>
        <p className="text-xl font-medium text-primary tracking-tight">Today's brief · {dateStr}</p>
      </header>

      {/* ACTION CARDS */}
      <section>
        <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest mb-4 flex items-center gap-2">
          <Zap size={12} className="text-warning" /> Priority Actions
        </h3>
        <motion.div 
          variants={container}
          initial="hidden"
          animate="show"
          className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4"
        >
          {actionSignals.map((signal: any) => (
            <motion.div 
              key={signal.schemeName}
              variants={item}
              onClick={() => onFundClick(signal.schemeName)}
              className={`p-5 rounded-xl border transition-all cursor-pointer group hover:bg-white/[0.02] ${
                signal.action === 'BUY' 
                  ? 'border-buy/10 bg-buy/[0.02] hover:border-buy/20' 
                  : 'border-exit/10 bg-exit/[0.02] hover:border-exit/20'
              }`}
            >
              <div className="flex items-center justify-between mb-4">
                <span className={`text-[10px] font-bold uppercase tracking-widest ${signal.action === 'BUY' ? 'text-buy' : 'text-exit'}`}>
                  {signal.action}
                </span>
                <ConvictionBadge score={signal.convictionScore} />
              </div>
              <p className="text-[13px] font-medium text-primary mb-1 truncate group-hover:text-white transition-colors">{signal.schemeName}</p>
              <div className={`text-2xl font-medium tabular-nums mb-4 ${signal.action === 'BUY' ? 'text-buy' : 'text-exit'}`}>
                <CurrencyValue 
                  isPrivate={isPrivate} 
                  value={signal.action === 'BUY' 
                    ? scaledBuySignals.find((b: any) => b.schemeName === signal.schemeName)?.displayAmount 
                    : signal.signalAmount
                  } 
                />
              </div>
              <p className="text-[11px] text-secondary leading-relaxed line-clamp-2">
                {buildPlainEnglishReason(signal)}
              </p>
            </motion.div>
          ))}
          
          {actionSignals.length === 0 && (
            <div className="col-span-full py-12 flex flex-col items-center justify-center bg-surface border border-white/5 rounded-xl">
              <Info className="text-muted mb-3" size={24} />
              <p className="text-secondary text-xs font-medium">No urgent actions identified for today.</p>
            </div>
          )}
        </motion.div>
      </section>

      {/* SIP CALCULATOR */}
      <section className="bg-surface border border-white/5 rounded-xl overflow-hidden">
        <div className="p-6 border-b border-white/5 flex flex-col md:flex-row md:items-center justify-between gap-6">
          <div>
            <h3 className="text-primary text-sm font-medium mb-1 tracking-tight">SIP Deployment Calculator</h3>
            <p className="text-muted text-[11px]">Proportional distribution based on conviction and target drift.</p>
          </div>
          <div className="flex flex-wrap items-center gap-8">
            <div className="space-y-2 min-w-[200px]">
              <div className="flex justify-between items-center text-[10px] uppercase tracking-widest text-muted">
                <span>Monthly SIP</span>
                <CurrencyValue isPrivate={isPrivate} value={sipAmount} className="text-primary tabular-nums font-medium" />
              </div>
              <input 
                type="range" min="0" max="200000" step="1000" 
                value={sipAmount} 
                onChange={(e) => setSipAmount(parseInt(e.target.value) || 0)}
                className="w-full h-1 bg-white/10 rounded-lg appearance-none cursor-pointer accent-accent"
              />
            </div>
            <div className="space-y-2">
              <label className="block text-[10px] uppercase tracking-widest text-muted">Extra Lumpsum</label>
              <input 
                type="number" 
                value={lumpsum || ''} 
                onChange={(e) => setLumpsum(parseInt(e.target.value) || 0)}
                placeholder="₹0"
                className="bg-surface-elevated border border-white/10 rounded-md px-3 py-1.5 text-sm text-primary tabular-nums focus:outline-none focus:border-indigo-500/50 transition-colors w-32"
              />
            </div>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left">
            <thead className="bg-white/[0.02] text-muted text-[10px] uppercase tracking-widest border-b border-white/5">
              <tr>
                <th className="px-6 py-4 font-medium">Fund name</th>
                <th className="px-6 py-4 font-medium text-right">Conviction</th>
                <th className="px-6 py-4 font-medium text-right">Planned %</th>
                <th className="px-6 py-4 font-medium text-right">Deploy amount</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {scaledBuySignals.map((signal: any) => (
                <tr key={signal.schemeName} className="hover:bg-white/[0.01] transition-colors group">
                  <td className="px-6 py-4">
                    <button 
                      onClick={() => onFundClick(signal.schemeName)}
                      className="text-[13px] text-primary hover:text-accent transition-colors truncate max-w-xs block text-left"
                    >
                      {signal.schemeName}
                    </button>
                  </td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex justify-end">
                      <MetricWithTooltip 
                        label="" 
                        value={<ConvictionBadge score={signal.convictionScore} />} 
                        tooltip="A 0–100 score combining your personal return, fund risk profile, and current valuation. Higher = stronger case to hold or buy more."
                      />
                    </div>
                  </td>
                  <td className="px-6 py-4 text-right text-[13px] text-secondary tabular-nums">
                    {signal.plannedPercentage}%
                  </td>
                  <td className="px-6 py-4 text-right text-[13px] text-buy font-medium tabular-nums">
                    <CurrencyValue isPrivate={isPrivate} value={signal.displayAmount} />
                  </td>
                </tr>
              ))}
              {scaledBuySignals.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-6 py-8 text-center text-muted text-xs italic">
                    All funds are currently within target allocation or marked for exit.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <footer className="flex items-center gap-3 px-6 py-4 bg-white/[0.02] border border-white/5 rounded-xl text-secondary">
        <ShieldAlert size={14} className="text-hold" />
        <p className="text-[11px] font-medium uppercase tracking-widest">
          {holdCount} funds on hold · Weights within tolerance
        </p>
      </footer>
    </div>
  );
}
