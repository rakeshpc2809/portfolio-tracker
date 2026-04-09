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
    .filter((s: any) => (s.stcgValue || 0) > 0 && (s.daysToNextLtcg || 0) > 0)
    .map((s: any) => ({
      name: s.schemeName,
      stcgValue: s.stcgValue,
      stcgGain: s.stcgAmount || 0,
      tax: (s.stcgAmount || 0) * 0.20,
      days: s.daysToNextLtcg || 0,
      saving: (s.stcgAmount || 0) * (0.20 - 0.125), // tax saved by waiting for LTCG
    }));

  useEffect(() => {
    fetchTlhOpportunities(pan)
      .then(setTlhOps)
      .catch(() => setTlhOps([]));
  }, [pan]);

  const stcgSection = (
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
                <th className="px-6 py-4 font-medium text-right">Save by Waiting</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {stcgFunds.map((f: any) => (
                <tr key={f.name} className="hover:bg-white/[0.01]">
                  <td className="px-6 py-4 text-[12px] text-primary truncate max-w-[180px] font-medium">{f.name}</td>
                  <td className="px-6 py-4 text-right text-[12px] text-exit tabular-nums font-bold">
                    <CurrencyValue isPrivate={isPrivate} value={f.tax} />
                  </td>
                  <td className={`px-6 py-4 text-right text-[12px] tabular-nums font-bold ${f.days <= 45 ? 'text-buy' : 'text-secondary'}`}>
                    {f.days} <span className="text-[10px] text-muted uppercase font-normal">days</span>
                  </td>
                  <td className="px-6 py-4 text-right">
                    <CurrencyValue isPrivate={isPrivate} value={f.saving}
                      className={`text-[12px] font-medium tabular-nums ${f.days <= 45 ? 'text-buy' : 'text-muted'}`} />
                    {f.days <= 45 && (
                      <p className="text-[9px] text-buy/70 mt-0.5 font-bold uppercase tracking-tighter">Soon!</p>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );

  const tlhSection = (
    <section className="space-y-4">
      <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest flex items-center gap-2">
        <TrendingDown size={12} className="text-buy" /> Harvesting Ops
      </h3>
      <div className="space-y-3">
        {tlhOps.map((op: any) => (
          <div key={op.schemeName} className="bg-surface border border-white/5 rounded-xl p-5 space-y-3">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-[10px] text-muted uppercase tracking-widest mb-1 font-bold">Sell</p>
                <p className="text-sm text-primary truncate max-w-[200px] font-medium">{op.schemeName}</p>
              </div>
              <div className="text-right">
                <p className="text-[10px] text-muted uppercase tracking-widest mb-1 font-bold">Buy</p>
                <p className="text-sm text-buy truncate max-w-[200px] font-bold">
                  {op.proxySchemeRecommendation || 'Search for similar category fund'}
                </p>
              </div>
            </div>
            <div className="flex justify-between pt-3 border-t border-white/5">
              <div>
                <p className="text-[10px] text-muted uppercase font-bold tracking-widest">Loss to harvest</p>
                <CurrencyValue isPrivate={isPrivate} value={Math.abs(op.estimatedCapitalLoss)} className="text-exit text-sm font-bold tabular-nums" />
              </div>
              <div className="text-right">
                <p className="text-[10px] text-muted uppercase font-bold tracking-widest">Est. tax saving</p>
                <CurrencyValue isPrivate={isPrivate} value={Math.abs(op.estimatedCapitalLoss) * 0.20} className="text-buy text-sm font-bold tabular-nums" />
              </div>
            </div>
          </div>
        ))}
      </div>
    </section>
  );

  return (
    <div className="space-y-10 pb-32">
      <header>
        <h2 className="text-muted text-[10px] font-medium uppercase tracking-[0.2em] mb-1">Fiscal optimization</h2>
        <p className="text-xl font-medium text-primary tracking-tight">Tax position · FY 2025-26</p>
      </header>

      {/* SECTION A: LTCG PROGRESS */}
      <section className="bg-surface border border-white/5 p-8 rounded-xl space-y-6 shadow-lg relative overflow-hidden">
        <div className="absolute left-0 top-0 bottom-0 w-1 bg-buy/20" />
        <div className="flex justify-between items-end">
          <div>
            <h3 className="text-primary text-sm font-medium mb-1">Annual LTCG Headroom</h3>
            <p className="text-muted text-[11px]">₹1.25L tax-free limit for equity gains.</p>
          </div>
          <div className="text-right">
            <CurrencyValue isPrivate={isPrivate} value={realizedLTCG} className="text-primary font-bold tabular-nums text-lg" />
            <span className="text-muted text-xs tabular-nums font-medium"> / {formatCurrency(ltcgLimit)}</span>
          </div>
        </div>
        
        <div className="relative pt-2">
          <Progress.Root 
            className="relative overflow-hidden bg-white/5 rounded-full w-full h-2" 
            value={ltcgPercent}
          >
            <Progress.Indicator 
              className={`w-full h-full transition-transform duration-500 ease-[cubic-bezier(0.65,0,0.35,1)] shadow-[0_0_10px_rgba(52,211,153,0.3)] ${
                ltcgPercent > 80 ? 'bg-warning' : 'bg-buy'
              }`}
              style={{ transform: `translateX(-${100 - ltcgPercent}%)` }}
            />
          </Progress.Root>
        </div>
        
        <div className="text-[11px] text-secondary leading-relaxed flex items-center gap-2 bg-white/[0.02] p-3 rounded-lg border border-white/5">
          <Info size={14} className="text-accent shrink-0" />
          {realizedLTCG < ltcgLimit 
            ? <span>You can harvest <CurrencyValue isPrivate={isPrivate} value={ltcgLimit - realizedLTCG} className="font-bold text-primary" /> more in LTCG gains tax-free this year.</span>
            : <span className="text-exit font-medium">You have exceeded the tax-free limit. Further LTCG will be taxed at 12.5%.</span>
          }
        </div>
      </section>

      {stcgFunds.length > 0 || tlhOps.length > 0 ? (
        // Both have content: side by side or single full width
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
          {stcgFunds.length > 0 && stcgSection}
          {tlhOps.length > 0 && tlhSection}
        </div>
      ) : (
        <div className="bg-surface border border-white/5 rounded-xl p-12 text-center space-y-4 shadow-xl">
          <Receipt size={48} className="text-muted mx-auto opacity-10" />
          <div>
            <p className="text-primary text-sm font-medium">No tax actions needed today</p>
            <p className="text-muted text-[11px] uppercase tracking-widest mt-2 leading-relaxed max-w-sm mx-auto">
              System monitors FIFO lots for -5% drops and STCG exposure daily to optimize your alpha.
            </p>
          </div>
        </div>
      )}

      {/* SECTION D: YEAR SUMMARY */}
      <section className="bg-white/[0.02] border border-white/5 p-8 rounded-xl shadow-inner">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-8">
          <MetricWithTooltip label="Realised LTCG" value={<CurrencyValue isPrivate={isPrivate} value={portfolioData.totalLTCG} />} tooltip="Total long-term capital gains already booked this financial year." />
          <MetricWithTooltip label="Realised STCG" value={<CurrencyValue isPrivate={isPrivate} value={portfolioData.totalSTCG} />} tooltip="Total short-term capital gains already booked this financial year." />
          <MetricWithTooltip label="Est. Tax Paid" value={<CurrencyValue isPrivate={isPrivate} value={(portfolioData.totalSTCG || 0) * 0.20 + Math.max(0, (portfolioData.totalLTCG || 0) - 125000) * 0.125} />} tooltip="Rough estimate of taxes owed on realized gains." />
          <MetricWithTooltip label="Headroom" value={<CurrencyValue isPrivate={isPrivate} value={Math.max(0, ltcgLimit - realizedLTCG)} />} valueClass="text-buy font-bold" tooltip="Remaining tax-free LTCG limit for this year." />
        </div>
      </section>
    </div>
  );
}
