import { useEffect, useState } from 'react';
import { formatCurrency } from '../../utils/formatters';
import { ShieldAlert, ShieldCheck, TrendingDown, ArrowRight } from 'lucide-react';
import MetricWithTooltip from '../ui/MetricWithTooltip';
import CurrencyValue from '../ui/CurrencyValue';
import { fetchTlhOpportunities } from '../../services/api';
import { motion } from 'framer-motion';
import * as Tooltip from '@radix-ui/react-tooltip';

export default function TaxView({ 
  portfolioData,
  isPrivate,
  pan
}: { 
  portfolioData: any;
  isPrivate: boolean;
  pan: string;
}) {
  const [tlhOps, setTlhOps] = useState<any[]>([]);
  const ltcgLimit = 125000;
  const realizedLTCG = parseFloat(portfolioData.totalLTCG || 0);
  const realizedSTCG = parseFloat(portfolioData.totalSTCG || 0);
  const ltcgPercent = Math.min(100, (realizedLTCG / ltcgLimit) * 100);
  
  const stcgFunds = (portfolioData.schemeBreakdown || [])
    .filter((s: any) => (s.stcgUnrealizedGain || 0) > 0)
    .map((s: any) => ({
      name: s.schemeName,
      stcgValue: s.stcgUnrealizedGain,
      stcgGain: s.stcgUnrealizedGain || 0,
      tax: (s.stcgUnrealizedGain || 0) * 0.20,
      days: s.daysToNextLtcg || 0,
      saving: (s.stcgUnrealizedGain || 0) * (0.20 - 0.125),
    }));

  useEffect(() => {
    fetchTlhOpportunities(pan)
      .then(setTlhOps)
      .catch(() => setTlhOps([]));
  }, [pan]);

  const circumference = 2 * Math.PI * 36; // r=36
  const strokeDashoffset = circumference * (1 - ltcgPercent / 100);

  const estTaxPaid = realizedSTCG * 0.20 + Math.max(0, realizedLTCG - ltcgLimit) * 0.125;

  return (
    <div className="space-y-10 pb-32">
      <header>
        <h2 className="text-muted text-[10px] font-medium uppercase tracking-[0.2em] mb-1">Fiscal optimization</h2>
        <p className="text-xl font-medium text-primary tracking-tight">Tax position · FY 2025-26</p>
      </header>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* LTCG Circular Gauge Card */}
        <section className="lg:col-span-2 bg-surface border border-white/5 p-8 rounded-2xl flex items-center gap-10 shadow-lg relative overflow-hidden">
          <div className="relative w-32 h-32 shrink-0">
            <svg className="w-full h-full -rotate-90">
              <circle
                cx="64" cy="64" r="36"
                fill="transparent"
                stroke="currentColor"
                strokeWidth="8"
                className="text-white/5"
              />
              <motion.circle
                cx="64" cy="64" r="36"
                fill="transparent"
                stroke="currentColor"
                strokeWidth="8"
                strokeDasharray={circumference}
                initial={{ strokeDashoffset: circumference }}
                animate={{ strokeDashoffset }}
                transition={{ duration: 1.5, ease: "easeOut" }}
                strokeLinecap="round"
                className={`${ltcgPercent > 80 ? 'text-exit' : ltcgPercent > 50 ? 'text-warning' : 'text-buy'}`}
              />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span className="text-[10px] font-bold text-muted uppercase tracking-tighter">Used</span>
              <span className="text-sm font-bold text-primary">{ltcgPercent.toFixed(0)}%</span>
            </div>
          </div>

          <div className="space-y-4">
            <div>
              <h3 className="text-primary text-sm font-semibold mb-1">Annual LTCG Headroom</h3>
              <p className="text-muted text-[11px] leading-relaxed max-w-sm">
                You've utilized <CurrencyValue isPrivate={isPrivate} value={realizedLTCG} className="text-primary font-bold" /> of your 
                ₹1.25L tax-free equity gain limit.
              </p>
            </div>
            
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-buy/10 border border-buy/20">
                <div className="w-1.5 h-1.5 rounded-full bg-buy animate-pulse-dot" />
                <span className="text-[10px] font-bold text-buy uppercase tracking-widest">
                  {isPrivate ? '₹••••' : formatCurrency(Math.max(0, ltcgLimit - realizedLTCG))} Available
                </span>
              </div>
              <p className="text-[10px] text-muted font-medium">Remaining headroom this FY</p>
            </div>
          </div>
        </section>

        {/* Actionable Summary Cards */}
        <div className="grid grid-cols-1 gap-4">
          <div className={`p-6 rounded-2xl border border-white/5 surface-tonal-exit ${realizedSTCG > 0 ? 'shadow-exit' : ''}`}>
            <MetricWithTooltip 
              label="Realised STCG" 
              value={<CurrencyValue isPrivate={isPrivate} value={realizedSTCG} />} 
              tooltip="Short-term gains booked this year. Taxed at 20%." 
            />
          </div>
          <div className={`p-6 rounded-2xl border border-white/5 ${estTaxPaid > 0 ? 'surface-tonal-exit shadow-exit' : 'surface-tonal-buy'}`}>
            <div className="flex justify-between items-start">
              <MetricWithTooltip 
                label="Est. Tax Liability" 
                value={<CurrencyValue isPrivate={isPrivate} value={estTaxPaid} />} 
                tooltip="Estimated tax owed on realized gains." 
              />
              {estTaxPaid === 0 && <ShieldCheck className="text-buy" size={20} />}
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
        {/* STCG Lock List Upgraded to Cards */}
        <section className="space-y-4">
          <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
            <ShieldAlert size={12} className="text-exit" /> STCG Lock List
          </h3>
          
          {stcgFunds.length > 0 ? (
            <div className="space-y-3">
              {stcgFunds.map((f: any) => {
                const waitColor = f.days > 180 ? 'bg-exit' : f.days > 45 ? 'bg-warning' : 'bg-buy';
                const waitProgress = Math.min(100, (365 - f.days) / 365 * 100);
                
                return (
                  <div key={f.name} className="bg-surface border border-white/5 p-5 rounded-2xl space-y-4 hover:border-white/10 transition-colors">
                    <div className="flex justify-between items-start">
                      <p className="text-xs font-semibold text-primary truncate max-w-[200px]">{f.name}</p>
                      <div className="text-right">
                        <p className="text-[9px] text-muted uppercase font-bold tracking-widest mb-0.5">Wait Time</p>
                        <p className={`text-xs font-bold ${f.days <= 45 ? 'text-buy' : 'text-primary'}`}>{f.days} days</p>
                      </div>
                    </div>
                    
                    <div className="h-1 w-full bg-white/5 rounded-full overflow-hidden">
                      <motion.div 
                        initial={{ width: 0 }}
                        animate={{ width: `${waitProgress}%` }}
                        className={`h-full ${waitColor}`}
                      />
                    </div>

                    <div className="flex justify-between items-center pt-1">
                      <div className="flex items-center gap-1.5">
                        <span className="text-[9px] text-muted font-bold uppercase tracking-widest">Potential Tax</span>
                        <CurrencyValue isPrivate={isPrivate} value={f.tax} className="text-xs font-bold text-exit" />
                      </div>
                      <div className="flex items-center gap-1.5 px-2 py-1 rounded-lg bg-white/[0.03] border border-white/5">
                        <span className="text-[9px] text-muted font-bold uppercase tracking-widest">Save</span>
                        <CurrencyValue isPrivate={isPrivate} value={f.saving} className="text-xs font-bold text-buy" />
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="flex flex-col items-center gap-3 py-12 text-center bg-surface border border-white/5 rounded-2xl">
              <div className="w-12 h-12 rounded-2xl bg-buy/10 flex items-center justify-center">
                <ShieldCheck className="text-buy" size={20} />
              </div>
              <p className="text-sm font-medium text-primary">All clear — no STCG exposure</p>
              <p className="text-[11px] text-muted max-w-xs">
                None of your open lots have short-term gains that would attract the 20% STCG rate.
              </p>
            </div>
          )}
        </section>

        {/* TLH Opportunities Actionable Cards */}
        <section className="space-y-4">
          <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
            <TrendingDown size={12} className="text-buy" /> Tax Loss Harvesting
          </h3>
          
          <div className="space-y-3">
            {tlhOps.length > 0 ? tlhOps.map((op: any) => (
              <div key={op.schemeName} className="bg-surface border border-white/5 rounded-2xl p-5 space-y-4 hover:border-white/10 transition-colors">
                <div className="flex items-center justify-between gap-4">
                  <div className="flex-1 min-w-0">
                    <p className="text-[9px] text-muted uppercase font-bold tracking-widest mb-1">Sell (In Loss)</p>
                    <p className="text-xs font-semibold text-primary truncate">{op.schemeName}</p>
                  </div>
                  <ArrowRight className="text-muted shrink-0" size={14} />
                  <div className="flex-1 min-w-0 text-right">
                    <p className="text-[9px] text-muted uppercase font-bold tracking-widest mb-1">Buy (Proxy)</p>
                    <p className="text-xs font-semibold text-buy truncate">
                      {op.proxySchemeRecommendation || 'Similar Fund'}
                    </p>
                  </div>
                </div>

                <div className="flex justify-between items-end pt-3 border-t border-white/5">
                  <div>
                    <p className="text-[9px] text-muted uppercase font-bold tracking-widest mb-1">Harvestable Loss</p>
                    <CurrencyValue isPrivate={isPrivate} value={Math.abs(op.estimatedCapitalLoss)} className="text-sm font-bold text-exit" />
                  </div>
                  
                  <Tooltip.Provider delayDuration={200}>
                    <Tooltip.Root>
                      <Tooltip.Trigger asChild>
                        <button className="px-4 py-1.5 rounded-full bg-violet-500/10 border border-violet-500/20 text-[10px] font-bold text-violet-400 uppercase tracking-widest hover:bg-violet-500/20 transition-all cursor-help">
                          Harvest Now
                        </button>
                      </Tooltip.Trigger>
                      <Tooltip.Portal>
                        <Tooltip.Content className="bg-surface-overlay border border-white/10 p-3 rounded-xl shadow-2xl max-w-xs text-[11px] text-secondary z-[100] animate-in fade-in zoom-in-95" sideOffset={5}>
                          Selling this fund at a loss and immediately buying a similar fund lets you book a tax loss without losing market exposure.
                          <Tooltip.Arrow className="fill-white/10" />
                        </Tooltip.Content>
                      </Tooltip.Portal>
                    </Tooltip.Root>
                  </Tooltip.Provider>
                </div>
              </div>
            )) : (
              <div className="bg-surface border border-white/5 rounded-2xl p-12 text-center space-y-4 shadow-xl opacity-60">
                <TrendingDown size={48} className="text-muted mx-auto opacity-10" />
                <p className="text-primary text-sm font-medium">No harvesting opportunities</p>
                <p className="text-muted text-[11px] uppercase tracking-widest mt-2 leading-relaxed max-w-sm mx-auto">
                  System monitors for losses &gt; 5% to book tax benefits while keeping exposure.
                </p>
              </div>
            )}
          </div>
        </section>
      </div>

      {/* Year Summary */}
      <section className="bg-white/[0.02] border border-white/5 p-8 rounded-2xl">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-8">
          <MetricWithTooltip label="Realised LTCG" value={<CurrencyValue isPrivate={isPrivate} value={realizedLTCG} />} tooltip="Total long-term capital gains already booked this financial year." />
          <MetricWithTooltip label="Realised STCG" value={<CurrencyValue isPrivate={isPrivate} value={realizedSTCG} />} tooltip="Total short-term capital gains already booked this financial year." />
          <MetricWithTooltip label="Est. Tax Paid" value={<CurrencyValue isPrivate={isPrivate} value={estTaxPaid} />} tooltip="Rough estimate of taxes owed on realized gains." />
          <MetricWithTooltip label="Headroom" value={<CurrencyValue isPrivate={isPrivate} value={Math.max(0, ltcgLimit - realizedLTCG)} />} valueClass="text-buy font-bold" tooltip="Remaining tax-free LTCG limit for this year." />
        </div>
      </section>
    </div>
  );
}
