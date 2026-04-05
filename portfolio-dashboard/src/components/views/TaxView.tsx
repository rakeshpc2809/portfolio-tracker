import { useEffect, useState } from 'react';
import { formatCurrency } from '../../utils/formatters';
import * as Progress from '@radix-ui/react-progress';
import { ShieldAlert, Info, TrendingDown, Receipt } from 'lucide-react';
import MetricWithTooltip from '../ui/MetricWithTooltip';
import CurrencyValue from '../ui/CurrencyValue';
import { fetchTlhOpportunities } from '../../services/api';

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
  const realizedLTCG = portfolioData.totalLTCG || 0;
  const ltcgPercent = Math.min(100, (realizedLTCG / ltcgLimit) * 100);
  
  const stcgFunds = (portfolioData.schemeBreakdown || [])
    .filter((s: any) => (s.stcgValue || 0) > 0)
    .map((s: any) => ({
      name: s.schemeName,
      stcg: s.stcgValue,
      tax: s.stcgValue * 0.20,
      days: s.daysToNextLtcg || 0
    }));

  useEffect(() => {
    fetchTlhOpportunities(pan)
      .then(setTlhOps)
      .catch(() => setTlhOps([]));
  }, [pan]);

  return (
    <div className="space-y-10 pb-32">
      <header>
        <h2 className="text-muted text-[10px] font-medium uppercase tracking-[0.2em] mb-1">Fiscal optimization</h2>
        <p className="text-xl font-medium text-primary tracking-tight">Tax position · FY 2025-26</p>
      </header>

      {/* SECTION A: LTCG PROGRESS */}
      <section className="bg-surface border border-white/5 p-8 rounded-xl space-y-6">
        <div className="flex justify-between items-end">
          <div>
            <h3 className="text-primary text-sm font-medium mb-1">Annual LTCG Headroom</h3>
            <p className="text-muted text-[11px]">₹1.25L tax-free limit for equity gains.</p>
          </div>
          <div className="text-right">
            <CurrencyValue isPrivate={isPrivate} value={realizedLTCG} className="text-primary font-medium tabular-nums" />
            <span className="text-muted text-xs tabular-nums"> / {formatCurrency(ltcgLimit)}</span>
          </div>
        </div>
        
        <div className="relative pt-2">
          <Progress.Root 
            className="relative overflow-hidden bg-white/5 rounded-full w-full h-2" 
            value={ltcgPercent}
          >
            <Progress.Indicator 
              className={`w-full h-full transition-transform duration-500 ease-[cubic-bezier(0.65,0,0.35,1)] ${
                ltcgPercent > 80 ? 'bg-warning' : 'bg-buy'
              }`}
              style={{ transform: `translateX(-${100 - ltcgPercent}%)` }}
            />
          </Progress.Root>
        </div>
        
        <div className="text-[11px] text-secondary leading-relaxed flex items-center gap-2">
          <Info size={14} className="text-accent" />
          {realizedLTCG < ltcgLimit 
            ? <span>You can harvest <CurrencyValue isPrivate={isPrivate} value={ltcgLimit - realizedLTCG} /> more in LTCG gains tax-free this year.</span>
            : `You have exceeded the tax-free limit. Further LTCG will be taxed at 12.5%.`
          }
        </div>
      </section>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
        {/* SECTION B: STCG EXPOSURE */}
        <section className="space-y-4">
          <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
            <ShieldAlert size={12} className="text-exit" /> STCG Lock List
          </h3>
          <div className="bg-surface border border-white/5 rounded-xl overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left">
                <thead className="bg-white/[0.02] text-muted text-[10px] uppercase tracking-widest border-b border-white/5">
                  <tr>
                    <th className="px-6 py-4 font-medium">Fund</th>
                    <th className="px-6 py-4 font-medium text-right">Potential Tax</th>
                    <th className="px-6 py-4 font-medium text-right">Wait Time</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/5">
                  {stcgFunds.map((f: any) => (
                    <tr key={f.name} className="hover:bg-white/[0.01]">
                      <td className="px-6 py-4 text-[12px] text-primary truncate max-w-[180px]">{f.name}</td>
                      <td className="px-6 py-4 text-right text-[12px] text-exit tabular-nums">
                        <CurrencyValue isPrivate={isPrivate} value={f.tax} />
                      </td>
                      <td className="px-6 py-4 text-right text-[12px] text-secondary tabular-nums">
                        {f.days} <span className="text-[10px] text-muted uppercase">days</span>
                      </td>
                    </tr>
                  ))}
                  {stcgFunds.length === 0 && (
                    <tr>
                      <td colSpan={3} className="px-6 py-8 text-center text-muted text-xs italic">
                        No short-term gains detected in current holdings.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </section>

        {/* SECTION C: TLH OPPORTUNITIES */}
        <section className="space-y-4">
          <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
            <TrendingDown size={12} className="text-buy" /> Harvesting Ops
          </h3>
          <div className="space-y-3">
            {tlhOps.length > 0 ? (
              tlhOps.map((op: any) => (
                <div key={op.schemeName} className="bg-surface border border-white/5 rounded-xl p-5 space-y-3">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-[10px] text-muted uppercase tracking-widest mb-1">Sell</p>
                      <p className="text-sm text-primary truncate max-w-[200px]">{op.schemeName}</p>
                    </div>
                    <div className="text-right">
                      <p className="text-[10px] text-muted uppercase tracking-widest mb-1">Buy</p>
                      <p className="text-sm text-buy truncate max-w-[200px]">{op.proxySchemeRecommendation}</p>
                    </div>
                  </div>
                  <div className="flex justify-between pt-3 border-t border-white/5">
                    <div>
                      <p className="text-[10px] text-muted uppercase">Loss to harvest</p>
                      <CurrencyValue isPrivate={isPrivate} value={Math.abs(op.estimatedCapitalLoss)} className="text-exit text-sm font-medium tabular-nums" />
                    </div>
                    <div className="text-right">
                      <p className="text-[10px] text-muted uppercase">Est. tax saving</p>
                      <CurrencyValue isPrivate={isPrivate} value={Math.abs(op.estimatedCapitalLoss) * 0.20} className="text-buy text-sm font-medium tabular-nums" />
                    </div>
                  </div>
                </div>
              ))
            ) : (
              <div className="bg-surface border border-white/5 rounded-xl p-6 min-h-[200px] flex flex-col justify-center">
                <div className="text-center space-y-3">
                  <Receipt size={32} className="text-muted mx-auto opacity-20" />
                  <p className="text-muted text-xs font-medium">No tax-loss harvesting opportunities identified today.</p>
                  <p className="text-[10px] text-muted uppercase tracking-widest leading-relaxed max-w-[240px] mx-auto">
                    The system monitors your FIFO lots for -5% drops exceeding ₹1,000 in absolute loss.
                  </p>
                </div>
              </div>
            )}
          </div>
        </section>
      </div>

      {/* SECTION D: YEAR SUMMARY */}
      <section className="bg-white/[0.02] border border-white/5 p-8 rounded-xl">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-8">
          <MetricWithTooltip label="Realised LTCG" value={<CurrencyValue isPrivate={isPrivate} value={portfolioData.totalLTCG} />} tooltip="Total long-term capital gains already booked this financial year." />
          <MetricWithTooltip label="Realised STCG" value={<CurrencyValue isPrivate={isPrivate} value={portfolioData.totalSTCG} />} tooltip="Total short-term capital gains already booked this financial year." />
          <MetricWithTooltip label="Est. Tax Paid" value={<CurrencyValue isPrivate={isPrivate} value={(portfolioData.totalSTCG || 0) * 0.20 + Math.max(0, (portfolioData.totalLTCG || 0) - 125000) * 0.125} />} tooltip="Rough estimate of taxes owed on realized gains." />
          <MetricWithTooltip label="Headroom" value={<CurrencyValue isPrivate={isPrivate} value={Math.max(0, ltcgLimit - realizedLTCG)} />} valueClass="text-buy" tooltip="Remaining tax-free LTCG limit for this year." />
        </div>
      </section>
    </div>
  );
}
