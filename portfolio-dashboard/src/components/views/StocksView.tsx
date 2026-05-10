import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import { AlertTriangle, BarChart3, Info, Activity, Loader2 } from 'lucide-react';
import { formatCurrency } from '../../utils/formatters';
import { fetchStockPortfolio, syncStockPrices } from '../../services/api';

interface StockHolding {
  ticker: string;
  isin: string;
  companyName: string;
  sector: string | null;
  quantity: number;
  avgCostPerShare: number;
  currentPrice: number;
  currentValue: number;
  investedAmount: number;
  unrealisedPnl: number;
  unrealisedPnlPct: number;
  unrealisedLtcg: number;
  unrealisedStcg: number;
  ltcgTaxEstimate: number;
  stcgTaxEstimate: number;
  daysToNextLtcg?: number;
  xirr?: number;
  dayChangePct?: number;
  action?: 'BUY' | 'SELL' | 'HOLD' | 'WATCH';
}

export default function StocksView({ pan, isPrivate }: { pan: string; isPrivate: boolean }) {
  const mask = (v: any) => isPrivate ? '••••' : v;
  const [syncing, setSyncing] = useState(false);

  const { data: holdings = [] as StockHolding[], isLoading, error, refetch } = useQuery<StockHolding[]>({
    queryKey: ['stocks', pan],
    queryFn: () => fetchStockPortfolio(pan),
    staleTime: 60_000,
  });

  const handleSync = async () => {
    setSyncing(true);
    try {
      await syncStockPrices(pan);
      await refetch();
    } catch (err) {
      console.error("Sync failed", err);
    } finally {
      setSyncing(false);
    }
  };

  const summary = useMemo(() => ({
    totalValue:    holdings.reduce((s: number, h: StockHolding) => s + h.currentValue, 0),
    totalInvested: holdings.reduce((s: number, h: StockHolding) => s + h.investedAmount, 0),
    totalPnl:      holdings.reduce((s: number, h: StockHolding) => s + h.unrealisedPnl, 0),
    totalTax:      holdings.reduce((s: number, h: StockHolding) => s + h.ltcgTaxEstimate + h.stcgTaxEstimate, 0),
  }), [holdings]);

  if (isLoading) return (
    <div className="h-96 flex items-center justify-center">
      <div className="w-10 h-10 border-4 border-accent border-t-transparent rounded-full animate-spin" />
    </div>
  );

  if (error) return (
    <div className="h-96 flex flex-col items-center justify-center gap-4 text-exit">
      <AlertTriangle size={48} />
      <p className="text-sm font-bold uppercase tracking-widest">Failed to load stocks</p>
    </div>
  );

  return (
    <div className="space-y-10 pb-32">
      {/* Summary Section */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
        {[
          { label: 'Market Value', value: formatCurrency(summary.totalValue), sub: 'Current Portfolio', glow: 'glow-accent' },
          { label: 'Invested', value: formatCurrency(summary.totalInvested), sub: 'Cost Basis' },
          { 
            label: 'Total P&L', 
            value: formatCurrency(summary.totalPnl), 
            sub: `${((summary.totalPnl / (summary.totalInvested || 1)) * 100).toFixed(2)}%`,
            color: summary.totalPnl >= 0 ? 'text-buy' : 'text-exit'
          },
          { label: 'Est. Tax', value: formatCurrency(summary.totalTax), sub: 'LTCG + STCG', color: 'text-warning' }
        ].map((s, i) => (
          <motion.div
            key={s.label}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.1 }}
            className="group relative bg-surface border border-border p-6 rounded-2xl overflow-hidden hover:border-accent/30 transition-all duration-300"
          >
            <div className="relative z-10">
              <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-muted mb-2">{s.label}</p>
              <h3 className={`text-2xl font-black tracking-tight ${s.color || 'text-primary'}`}>
                {mask(s.value)}
              </h3>
              <p className="text-[10px] font-medium text-muted/60 mt-1 uppercase tracking-wider">{mask(s.sub)}</p>
            </div>
            <div className={`absolute -right-4 -bottom-4 w-24 h-24 rounded-full blur-3xl opacity-0 group-hover:opacity-20 transition-opacity duration-500 ${s.color?.includes('buy') ? 'bg-buy' : s.color?.includes('exit') ? 'bg-exit' : 'bg-accent'}`} />
          </motion.div>
        ))}
      </div>

      {/* Holdings Table */}
      <div className="bg-surface border border-border rounded-3xl overflow-hidden shadow-2xl">
        <div className="px-8 py-6 border-b border-border bg-white/[0.02] flex justify-between items-center">
          <div className="flex items-center gap-3">
            <BarChart3 size={18} className="text-accent" />
            <h2 className="text-xs font-black uppercase tracking-[0.3em]">Direct Equity Holdings</h2>
          </div>
          <div className="flex items-center gap-4">
            <button 
              onClick={handleSync}
              disabled={syncing}
              className="flex items-center gap-2 px-3 py-1.5 bg-accent/10 hover:bg-accent/20 border border-accent/20 rounded-lg text-[9px] font-black text-accent uppercase tracking-widest transition-all active:scale-95 disabled:opacity-50"
            >
              {syncing ? <Loader2 size={12} className="animate-spin" /> : <Activity size={12} />}
              {syncing ? 'Syncing...' : 'Sync Prices'}
            </button>
            <div className="flex items-center gap-2 px-3 py-1.5 bg-white/[0.05] rounded-full text-[9px] font-bold text-muted">
              <Info size={12} />
              <span>Prices updated via INDstocks</span>
            </div>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-white/[0.01]">
                <th className="px-8 py-4 text-[9px] font-black uppercase tracking-[0.2em] text-muted border-b border-border">Script</th>
                <th className="px-8 py-4 text-[9px] font-black uppercase tracking-[0.2em] text-muted border-b border-border text-right">Quantity</th>
                <th className="px-8 py-4 text-[9px] font-black uppercase tracking-[0.2em] text-muted border-b border-border text-right">Avg Price</th>
                <th className="px-8 py-4 text-[9px] font-black uppercase tracking-[0.2em] text-muted border-b border-border text-right">LTP</th>
                <th className="px-8 py-4 text-[9px] font-black uppercase tracking-[0.2em] text-muted border-b border-border text-right">Market Value</th>
                <th className="px-8 py-4 text-[9px] font-black uppercase tracking-[0.2em] text-muted border-b border-border text-right">P&L (%)</th>
                <th className="px-8 py-4 text-[9px] font-black uppercase tracking-[0.2em] text-muted border-b border-border">Tax Category</th>
              </tr>
            </thead>
            <tbody>
              {holdings.map((h: StockHolding, i: number) => (
                <motion.tr 
                  key={h.ticker}
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  transition={{ delay: i * 0.05 }}
                  className="group hover:bg-white/[0.03] transition-colors"
                >
                  <td className="px-8 py-5 border-b border-border/50">
                    <div className="flex flex-col">
                      <span className="text-sm font-black tracking-tight group-hover:text-accent transition-colors">{h.ticker}</span>
                      <span className="text-[10px] text-muted/60 font-medium truncate max-w-[200px]">{h.companyName}</span>
                    </div>
                  </td>
                  <td className="px-8 py-5 border-b border-border/50 text-right font-mono text-xs">{mask(h.quantity)}</td>
                  <td className="px-8 py-5 border-b border-border/50 text-right font-mono text-xs">{mask(formatCurrency(h.avgCostPerShare))}</td>
                  <td className="px-8 py-5 border-b border-border/50 text-right font-mono text-xs font-bold">{mask(formatCurrency(h.currentPrice))}</td>
                  <td className="px-8 py-5 border-b border-border/50 text-right font-mono text-xs font-black">{mask(formatCurrency(h.currentValue))}</td>
                  <td className={`px-8 py-5 border-b border-border/50 text-right font-mono text-xs font-bold ${h.unrealisedPnl >= 0 ? 'text-buy' : 'text-exit'}`}>
                    {mask(formatCurrency(h.unrealisedPnl))} ({h.unrealisedPnlPct.toFixed(2)}%)
                  </td>
                  <td className="px-8 py-5 border-b border-border/50">
                    <div className="flex gap-2">
                      {h.unrealisedLtcg > 0 && (
                        <span className="px-2 py-1 rounded bg-buy/10 text-buy text-[8px] font-black tracking-widest uppercase">LTCG</span>
                      )}
                      {h.unrealisedStcg > 0 && (
                        <span className="px-2 py-1 rounded bg-warning/10 text-warning text-[8px] font-black tracking-widest uppercase">STCG</span>
                      )}
                    </div>
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
