import { motion } from 'framer-motion';
import { X, Zap, Info } from 'lucide-react';
import { convictionColor, buildPlainEnglishReason } from '../../utils/formatters';
import * as Dialog from '@radix-ui/react-dialog';
import * as Tooltip from '@radix-ui/react-tooltip';
import CurrencyValue from '../ui/CurrencyValue';

export default function FundDetailView({ 
  fund, 
  isOpen, 
  onClose,
  isPrivate
}: { 
  fund: any; 
  isOpen: boolean; 
  onClose: () => void; 
  isPrivate: boolean;
}) {
  if (!fund) return null;

  // Design Improvement 5: Use real sub-scores if available, otherwise estimate
  const hasRealScores = fund.yieldScore > 0 || fund.riskScore > 0 || fund.valueScore > 0;
  
  const components = [
    { 
      label: 'Yield', 
      score: hasRealScores ? fund.yieldScore : (fund.convictionScore || 0) * 0.8, 
      weight: '20%', 
      tooltip: 'Your personal CAGR from tax lots.' 
    },
    { 
      label: 'Risk', 
      score: hasRealScores ? fund.riskScore : (fund.sortinoRatio || 0) * 40, 
      weight: '25%', 
      tooltip: 'Sortino ratio - return relative to downside risk.' 
    },
    { 
      label: 'Value', 
      score: hasRealScores ? fund.valueScore : (1 - (fund.navPercentile3yr || 0)) * 100, 
      weight: '25%', 
      tooltip: 'NAV position + drawdown from ATH.' 
    },
    { 
      label: 'Pain', 
      score: hasRealScores ? fund.painScore : (1 - Math.abs((fund.maxDrawdown || 0) / 100)) * 100, 
      weight: '15%', 
      tooltip: 'Max drawdown resilience.' 
    },
    { 
      label: 'Friction', 
      score: hasRealScores ? fund.frictionScore : 85, 
      weight: '15%', 
      tooltip: 'Tax drag simulation results.' 
    },
  ];

  return (
    <Dialog.Root open={isOpen} onOpenChange={onClose}>
      <Dialog.Portal>
        <Dialog.Overlay asChild>
          <motion.div 
            initial={{ opacity: 0 }} 
            animate={{ opacity: 1 }} 
            exit={{ opacity: 0 }}
            className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[100]" 
          />
        </Dialog.Overlay>
        <Dialog.Content asChild>
          <motion.div 
            initial={{ x: '100%' }} 
            animate={{ x: 0 }} 
            exit={{ x: '100%' }}
            transition={{ type: 'spring', damping: 25, stiffness: 200 }}
            className="fixed right-0 top-0 bottom-0 w-full max-w-3xl bg-surface border-l border-white/5 z-[101] shadow-2xl overflow-y-auto focus:outline-none"
          >
            <div className="p-8 space-y-10">
              <header className="flex items-start justify-between">
                <div className="space-y-4">
                  <div className="flex flex-wrap items-center gap-3">
                    <span className="px-2 py-0.5 bg-white/5 border border-white/10 rounded text-[10px] font-medium text-secondary uppercase tracking-widest">
                      {fund.category}
                    </span>
                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-widest ${
                      fund.action === 'BUY' ? 'text-buy bg-buy/10 border border-buy/20' : 
                      fund.action === 'EXIT' || fund.action === 'SELL' ? 'text-exit bg-exit/10 border border-exit/20' : 'text-hold bg-hold/10 border border-hold/20'
                    }`}>
                      {fund.action}
                    </span>
                  </div>
                  <h2 className="text-2xl font-medium text-primary tracking-tight leading-tight max-w-md">{fund.schemeName}</h2>
                </div>
                <div className="flex items-center gap-6">
                  <div className="text-right">
                    <p className="text-[10px] uppercase tracking-[0.2em] text-muted mb-1">Conviction</p>
                    <p className="text-3xl font-medium tabular-nums text-primary">{fund.convictionScore}</p>
                  </div>
                  <Dialog.Close asChild>
                    <button className="p-2 hover:bg-white/5 rounded-full transition-colors text-muted hover:text-primary cursor-pointer">
                      <X size={20} />
                    </button>
                  </Dialog.Close>
                </div>
              </header>

              {/* Sticky financial strip */}
              <div className="grid grid-cols-3 gap-3 -mx-8 px-8 py-4 bg-surface-elevated border-y border-border">
                <div>
                  <p className="text-[9px] uppercase tracking-[0.2em] text-muted mb-1 font-bold">Value</p>
                  <CurrencyValue isPrivate={isPrivate} value={fund.currentValue}
                    className="text-base font-medium text-primary tabular-nums" />
                </div>
                <div>
                  <p className="text-[9px] uppercase tracking-[0.2em] text-muted mb-1 font-bold">XIRR</p>
                  <p className={`text-base font-medium tabular-nums ${parseFloat(fund.xirr) >= 0 ? 'text-buy glow-buy' : 'text-exit glow-exit'}`}>
                    {fund.xirr}
                  </p>
                </div>
                <div>
                  <p className="text-[9px] uppercase tracking-[0.2em] text-muted mb-1 font-bold">Unrealised P&L</p>
                  <CurrencyValue isPrivate={isPrivate} value={fund.unrealizedGain}
                    className={`text-base font-medium tabular-nums ${fund.unrealizedGain >= 0 ? 'text-buy' : 'text-exit'}`} />
                </div>
              </div>

              <section className="bg-surface-elevated border border-white/5 p-6 rounded-xl space-y-6 shadow-inner">
                <div className="flex items-center justify-between">
                  <h3 className="text-primary text-xs font-medium uppercase tracking-widest">Valuation Visual</h3>
                  <span className="text-muted text-[10px] font-medium">3-Year Range</span>
                </div>
                
                <div className="space-y-8">
                  <div className="relative h-1 w-full bg-white/5 rounded-full my-4">
                    {/* end markers */}
                    <div className="absolute top-0 left-0 h-3 w-px bg-white/20 -translate-y-1" />
                    <div className="absolute top-0 right-0 h-3 w-px bg-white/20 -translate-y-1" />
                    
                    <span className="absolute -top-6 left-0 text-[9px] font-bold text-muted uppercase tracking-widest opacity-50">3yr Low</span>
                    <span className="absolute -top-6 right-0 text-[9px] font-bold text-muted uppercase tracking-widest opacity-50">3yr High</span>
                    
                    {/* animated dot — using style with clamping */}
                    <div 
                      className="absolute top-1/2 -translate-y-1/2 -translate-x-1/2 w-3 h-3 bg-accent rounded-full border-2 border-[#14141f] transition-all duration-1000 shadow-[0_0_15px_rgba(129,140,248,0.5)]"
                      style={{ left: `${Math.min(98, Math.max(2, (fund.navPercentile3yr || 0) * 100))}%` }}
                    />
                  </div>
                  
                  <div className="grid grid-cols-2 gap-8 pt-4">
                    <div>
                      <p className="text-muted text-[10px] font-medium uppercase tracking-widest mb-1">Positioning</p>
                      <p className="text-sm text-primary font-medium">
                        At {Math.round((fund.navPercentile3yr || 0) * 100)}% of its range
                      </p>
                    </div>
                    <div>
                      <p className="text-muted text-[10px] font-medium uppercase tracking-widest mb-1">ATH Drawdown</p>
                      <p className="text-sm text-exit font-medium font-mono">
                        {((fund.drawdownFromAth || 0) * 100).toFixed(1)}% from peak
                      </p>
                    </div>
                  </div>
                </div>
              </section>

              <section className="space-y-4">
                <div className="flex items-center justify-between">
                  <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
                    <Zap size={12} className="text-warning" /> Scoring Engine Diagnostics
                  </h3>
                  {!hasRealScores && (
                    <span className="text-[9px] text-muted italic">Showing estimates</span>
                  )}
                </div>
                <div className="space-y-3">
                  {components.map((c) => (
                    <div key={c.label} className="bg-white/[0.02] border border-white/5 p-4 rounded-lg flex items-center gap-6 group hover:bg-white/[0.04] transition-colors">
                      <div className="w-20">
                        <p className="text-[10px] font-bold text-secondary uppercase tracking-widest">{c.label}</p>
                        <p className="text-[9px] text-muted font-medium">{c.weight} weight</p>
                      </div>
                      <div className="flex-1 h-1.5 bg-white/5 rounded-full overflow-hidden">
                        <motion.div 
                          initial={{ width: 0 }}
                          animate={{ width: `${c.score}%` }}
                          transition={{ duration: 0.8, delay: 0.3 }}
                          style={{ background: convictionColor(c.score) }}
                          className="h-full rounded-full transition-all"
                        />
                      </div>
                      <div className="flex items-center gap-3">
                        <span className="text-xs font-medium tabular-nums text-primary">{Math.round(c.score)}</span>
                        <Tooltip.Provider delayDuration={200}>
                          <Tooltip.Root>
                            <Tooltip.Trigger asChild>
                              <Info size={13} className="text-muted cursor-help hover:text-secondary transition-colors" />
                            </Tooltip.Trigger>
                            <Tooltip.Portal>
                              <Tooltip.Content className="bg-[#1a1a2e] border border-white/10 text-slate-300 text-[11px] rounded-lg px-3 py-2 max-w-[200px] leading-relaxed z-[200] shadow-xl animate-in fade-in zoom-in-95">
                                {c.tooltip}
                                <Tooltip.Arrow className="fill-[#1a1a2e]" />
                              </Tooltip.Content>
                            </Tooltip.Portal>
                          </Tooltip.Root>
                        </Tooltip.Provider>
                      </div>
                    </div>
                  ))}
                </div>
                {!hasRealScores && (
                  <p className="text-[10px] text-muted italic mt-1 px-1">
                    Component scores are estimated from available metrics. Run a full scan to update.
                  </p>
                )}
              </section>

              <section className="bg-accent/5 border border-accent/10 p-6 rounded-xl">
                <div className="flex items-center gap-3 mb-4 text-accent">
                  <Info size={16} />
                  <h3 className="text-[10px] font-bold uppercase tracking-widest">System Verdict</h3>
                </div>
                <div className="space-y-2">
                  {(fund.justifications && fund.justifications.length > 0
                    ? fund.justifications
                    : [buildPlainEnglishReason(fund)]
                  ).map((j: string, i: number) => (
                    <div key={i} className="flex gap-3 text-[12px] text-secondary leading-relaxed">
                      <span className="text-accent/40 shrink-0 mt-0.5">›</span>
                      <p>{j}</p>
                    </div>
                  ))}
                </div>
              </section>
            </div>
          </motion.div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
