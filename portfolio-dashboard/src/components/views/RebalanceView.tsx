import { useMemo, useState } from 'react';
import { 
  ResponsiveContainer, XAxis, YAxis, Tooltip, 
  BarChart, Bar, Cell, CartesianGrid, ReferenceLine
} from "recharts";
import CurrencyValue from '../ui/CurrencyValue';
import { Target, Clock, ArrowRight, TrendingUp, AlertCircle, ShieldAlert, Sparkles, AlertTriangle, Download } from 'lucide-react';
import type { RebalancingTrade } from '../../types/signals';
import { useDashboardContext } from '../../context/DashboardContext';
import { fetchSmartSip, fetchRebalanceActions } from '../../services/api';

interface TradeRow {
  action: 'BUY' | 'SELL';
  isin: string;
  schemeName: string;
  orderType: 'AMOUNT' | 'UNITS';
  quantity: number;
}

const escapeCSV = (val: string | number | undefined | null) => {
  if (val === undefined || val === null) return '';
  const str = String(val).trim();
  if (str.includes(',') || str.includes('"') || str.includes('\n') || str.includes('\r')) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
};

const exportToBrokerCSV = (trades: TradeRow[]): string => {
  const headers = ['Action (BUY/SELL)', 'ISIN', 'Scheme Name', 'Order Type (AMOUNT/UNITS)', 'Quantity'];
  const rows = trades.map(t => [
    t.action,
    t.isin || '',
    t.schemeName || '',
    t.orderType,
    t.quantity
  ]);
  
  return [
    headers.join(','),
    ...rows.map(row => row.map(escapeCSV).join(','))
  ].join('\n');
};

const downloadCSV = (csvContent: string, filename: string) => {
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.setAttribute('href', url);
  link.setAttribute('download', filename);
  link.style.visibility = 'hidden';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
};

export default function RebalanceView({ 
  portfolioData, 
  sipAmount, 
  setSipAmount,
  isPrivate 
}: { 
  portfolioData: any; 
  sipAmount: number; 
  setSipAmount: (val: number) => void;
  isPrivate: boolean;
}) {
  const { setIsStrategyOpen, pan } = useDashboardContext();

  const [exportingRebalance, setExportingRebalance] = useState<boolean>(false);
  const [rebalanceExportError, setRebalanceExportError] = useState<string | null>(null);

  interface RebalanceAction {
    schemeName: string;
    amfiCode: string;
    isin: string;
    signal: string;
    unitsToTransact: number;
    amount: number;
    justification: string;
    zScore: number;
    hurstExponent: number;
  }

  const handleDownloadRebalanceCSV = async () => {
    setExportingRebalance(true);
    setRebalanceExportError(null);
    try {
      const actions: RebalanceAction[] = await fetchRebalanceActions(pan);
      const tradeActions = actions.filter(
        a => a.signal === 'BUY' || a.signal === 'SELL' || a.signal === 'EXIT'
      );
      
      if (tradeActions.length === 0) {
        setRebalanceExportError("No execution trades found to export.");
        return;
      }
      
      const trades: TradeRow[] = tradeActions.map(a => {
        const action: 'BUY' | 'SELL' = (a.signal === 'SELL' || a.signal === 'EXIT') ? 'SELL' : 'BUY';
        const orderType: 'AMOUNT' | 'UNITS' = action === 'SELL' ? 'UNITS' : 'AMOUNT';
        const quantity = action === 'SELL' ? a.unitsToTransact : a.amount;
        return {
          action,
          isin: a.isin || '',
          schemeName: a.schemeName,
          orderType,
          quantity
        };
      });
      
      const csvContent = exportToBrokerCSV(trades);
      const dateStr = new Date().toISOString().split('T')[0].replace(/-/g, '_');
      downloadCSV(csvContent, `portfolio_execution_${dateStr}.csv`);
    } catch (err: any) {
      console.error(err);
      setRebalanceExportError(err.message || "Failed to export rebalance actions.");
    } finally {
      setExportingRebalance(false);
    }
  };

  const handleDownloadSmartSipCSV = () => {
    if (smartSipAllocations.length === 0) return;
    
    const activeAllocations = smartSipAllocations.filter(a => (a.allocatedAmount || 0) > 0);
    if (activeAllocations.length === 0) {
      alert("No active SIP allocations to export.");
      return;
    }
    
    const trades: TradeRow[] = activeAllocations.map(a => ({
      action: 'BUY',
      isin: a.isin || '',
      schemeName: a.schemeName,
      orderType: 'AMOUNT',
      quantity: a.allocatedAmount
    }));
    
    const csvContent = exportToBrokerCSV(trades);
    const dateStr = new Date().toISOString().split('T')[0].replace(/-/g, '_');
    downloadCSV(csvContent, `portfolio_execution_${dateStr}.csv`);
  };

  // Smart SIP Planner State
  const [sipBudget, setSipBudget] = useState<string>('50000');
  const [smartSipAllocations, setSmartSipAllocations] = useState<any[]>([]);
  const [smartSipLoading, setSmartSipLoading] = useState<boolean>(false);
  const [smartSipError, setSmartSipError] = useState<string | null>(null);

  const handleCalculateSmartSip = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!sipBudget || parseFloat(sipBudget) <= 0) {
      setSmartSipError("Please enter a valid monthly SIP budget.");
      return;
    }
    setSmartSipLoading(true);
    setSmartSipError(null);
    try {
      const data = await fetchSmartSip(pan, parseFloat(sipBudget));
      setSmartSipAllocations(data);
    } catch (err: any) {
      console.error(err);
      setSmartSipError(err.message || "Failed to calculate Smart SIP allocations.");
    } finally {
      setSmartSipLoading(false);
    }
  };

  const data = useMemo(() => (portfolioData.schemeBreakdown || [])
    .filter((s: any) => (s.currentValue || 0) > 0) // Only active positions
    .map((s: any) => {
      const drift = (s.allocationPercentage || 0) - (s.plannedPercentage || 0);
      return {
        ...s,
        name: s.simpleName || s.schemeName.substring(0, 22),
        drift: parseFloat(drift.toFixed(2)),
        color: drift > 1 ? "#f38ba8" : drift < -1 ? "#a6e3a1" : "#6c7086"
      };
    })
    .sort((a: any, b: any) => b.drift - a.drift), [portfolioData.schemeBreakdown]);

  const totalDrift = data.reduce((acc: number, s: any) => acc + Math.abs(s.drift), 0);

  // Step 3.6: Use server-computed SIP plan
  const sipPlan = portfolioData.tacticalPayload?.sipPlan || [];
  const rebalancingTrades: RebalancingTrade[] = portfolioData.tacticalPayload?.rebalancingTrades || [];
  const portfolioValue = portfolioData.currentValueAmount || 1;

  // LTCG Budget Logic
  const LTCG_LIMIT = 125000;
  const realizedLTCG = parseFloat(portfolioData.totalLTCG || 0);
  const ltcgUsedPct = Math.min(100, (realizedLTCG / LTCG_LIMIT) * 100);
  const remainingLtcg = Math.max(0, LTCG_LIMIT - realizedLTCG);

  // Step 3.4: Rebalance Timeline Data
  const timelines = useMemo(() => sipPlan
    .filter((s: any) => s.amount > 0)
    .map((s: any) => {
      const fund = data.find((f: any) => f.amfiCode === s.amfiCode || f.amfiCode === String(s.amfiCode));
      const drift = Math.abs(fund?.drift || 0);
      const gapValue = (drift / 100) * portfolioValue;
      const months = Math.ceil(gapValue / s.amount);
      return {
        name: s.simpleName || s.schemeName?.substring(0, 22),
        months: months > 36 ? 36 : months, // Cap at 3 years for visual
        actualMonths: months,
        color: months <= 6 ? '#a6e3a1' : months <= 12 ? '#f9e2af' : '#f38ba8'
      };
    })
    .sort((a: any, b: any) => a.months - b.months), [sipPlan, data, portfolioValue]);

  return (
    <div className="space-y-10 pb-32">
      <header className="flex justify-between items-end">
        <div>
          <h2 className="text-muted text-[10px] font-black uppercase tracking-[0.2em] mb-1">Target Alignment</h2>
          <p className="text-xl font-black text-primary tracking-tight">Allocation Drift · Strategic Balance</p>
        </div>
        <button
          onClick={() => setIsStrategyOpen?.(true)}
          className="px-3 py-1.5 bg-accent/10 border border-accent/20 rounded-xl text-[10px] font-black uppercase tracking-wider text-accent hover:bg-accent/20 transition-all cursor-pointer flex items-center gap-1.5"
        >
          <Target size={12} />
          Edit Target Weights
        </button>
      </header>

      {/* REBALANCING MOVES */}
      <section className="space-y-6">
        <div className="flex items-center justify-between px-2">
          <div className="space-y-1">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em] flex items-center gap-2">
              <TrendingUp size={12} className="text-accent" /> Rebalancing Moves This Month
            </h3>
            <p className="text-xs text-muted font-bold uppercase tracking-widest opacity-60">Paired Sell → Buy transactions for funded rebalancing</p>
          </div>
          <button
            onClick={handleDownloadRebalanceCSV}
            disabled={exportingRebalance}
            className="px-3 py-1.5 bg-accent/10 border border-accent/20 rounded-xl text-[10px] font-black uppercase tracking-wider text-accent hover:bg-accent/20 transition-all cursor-pointer flex items-center gap-1.5 disabled:opacity-50"
          >
            <Download size={12} />
            {exportingRebalance ? 'Exporting...' : 'Download Execution Sheet'}
          </button>
        </div>

        {rebalanceExportError && (
          <div className="mx-2 bg-exit/10 border border-exit/20 p-3 rounded-xl flex items-center gap-3">
            <AlertTriangle size={14} className="text-exit" />
            <p className="text-[10px] font-black text-exit uppercase tracking-widest leading-relaxed">
              {rebalanceExportError}
            </p>
          </div>
        )}

        <div className="grid grid-cols-1 gap-4">
          {rebalancingTrades.map((trade, idx) => (
            <div 
              key={idx}
              className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-[2rem] shadow-xl"
            >
              <div className="flex flex-col md:flex-row items-center gap-8">
                {/* SELL SIDE */}
                <div className="flex-1 space-y-2 w-full">
                  <div className="flex justify-between items-start">
                    <p className="text-[10px] font-black text-exit uppercase tracking-widest">Trim Position</p>
                    <p className="text-[10px] font-black text-muted uppercase tracking-widest">{trade.sellReason}</p>
                  </div>
                  <p className="text-sm font-black text-primary truncate">{trade.sellFundName}</p>
                  <div className="flex justify-between items-end">
                    <p className="text-lg font-black text-exit tabular-nums">
                      -<CurrencyValue isPrivate={isPrivate} value={trade.sellAmount} />
                    </p>
                    <div className="text-right">
                      <p className="text-[8px] font-black text-muted uppercase tracking-widest">Est. Tax</p>
                      <p className="text-[10px] font-black text-exit tabular-nums"><CurrencyValue isPrivate={isPrivate} value={trade.estimatedSellTax} /></p>
                    </div>
                  </div>
                </div>

                <div className="text-muted opacity-20 hidden md:block">
                  <ArrowRight size={24} />
                </div>

                {/* BUY SIDE */}
                <div className="flex-1 space-y-2 w-full">
                  <div className="flex justify-between items-start">
                    <p className="text-[10px] font-black text-buy uppercase tracking-widest">Deploy Proceeds</p>
                    <p className="text-[10px] font-black text-muted uppercase tracking-widest">{trade.buyReason}</p>
                  </div>
                  <p className="text-sm font-black text-primary truncate">{trade.buyFundName}</p>
                  <div className="flex justify-between items-end">
                    <p className="text-lg font-black text-buy tabular-nums">
                      +<CurrencyValue isPrivate={isPrivate} value={trade.buyAmount} />
                    </p>
                    <div className="bg-buy/10 px-2 py-0.5 rounded border border-buy/20">
                      <p className="text-[8px] font-black text-buy uppercase tracking-widest">Conviction +{trade.convictionDelta.toFixed(0)}</p>
                    </div>
                  </div>
                </div>
              </div>
              <div className="mt-6 pt-4 border-t border-white/5 text-[10px] font-bold text-muted uppercase tracking-widest italic flex justify-between items-center">
                <span>{trade.tradeRationale}</span>
                <span className="text-secondary font-black">Net Proceeds: <CurrencyValue isPrivate={isPrivate} value={trade.netProceeds} /></span>
              </div>
            </div>
          ))}
          {rebalancingTrades.length === 0 && (
            <div className="py-12 text-center border border-dashed border-white/10 rounded-[2.5rem] opacity-40">
              <p className="text-[10px] font-black uppercase tracking-widest">Portfolio is balanced. No rebalancing moves required this month.</p>
            </div>
          )}
        </div>
      </section>

      {/* TAX BUDGET TRACKER */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl shadow-xl space-y-6">
        <div className="flex items-center justify-between">
          <div className="space-y-1">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">FY LTCG Exemption Budget</h3>
            <p className="text-xs text-muted font-bold uppercase tracking-widest opacity-60">Tracking your ₹1.25L tax-free gain capacity</p>
          </div>
          <div className="text-right">
            <p className="text-[10px] font-black text-buy uppercase tracking-widest">
              <CurrencyValue isPrivate={isPrivate} value={remainingLtcg} /> Remaining
            </p>
          </div>
        </div>

        <div className="space-y-4">
          <div className="h-4 w-full bg-white/5 rounded-full overflow-hidden border border-white/10 p-0.5">
            <div 
              style={{width: `${ltcgUsedPct}%` }}
              className={`h-full rounded-full ${ltcgUsedPct > 90 ? 'bg-exit' : ltcgUsedPct > 60 ? 'bg-warning' : 'bg-buy'}`}
            />
          </div>
          <div className="flex justify-between items-center px-1">
            <p className="text-[9px] font-black text-muted uppercase tracking-widest">₹{realizedLTCG.toLocaleString('en-IN')} Realized</p>
            <p className="text-[9px] font-black text-muted uppercase tracking-widest">₹1.25L Limit</p>
          </div>
        </div>

        {remainingLtcg < 10000 && (
          <div className="bg-exit/10 border border-exit/20 p-4 rounded-2xl flex items-center gap-4">
            <AlertCircle size={20} className="text-exit" />
            <p className="text-[10px] font-black text-exit uppercase tracking-widest leading-relaxed">
              LTCG budget nearly exhausted. Defer non-urgent exits to next financial year (starting April 1).
            </p>
          </div>
        )}
      </section>

      {/* DROPPED FUND EXIT TIMELINE */}
      <section className="space-y-6">
        <div className="flex items-center justify-between px-2">
          <div className="space-y-1">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em] flex items-center gap-2">
              <ShieldAlert size={12} className="text-exit" /> Dropped Fund Exit Plan
            </h3>
            <p className="text-xs text-muted font-bold uppercase tracking-widest opacity-60">Strategic timeline to exit non-strategy holdings</p>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {(portfolioData.tacticalPayload?.droppedFundSummaries || []).map((s: any) => (
            <div 
              key={s.amfiCode}
              className={`bg-surface/40 backdrop-blur-xl border p-6 rounded-[2rem] shadow-xl space-y-4 ${
                s.recommendedAction === 'WAIT_FOR_LTCG' ? 'border-warning/20' : 
                s.recommendedAction === 'EXIT_NOW_TAX_FREE' ? 'border-buy/20' : 'border-white/5'
              }`}
            >
              <div className="flex justify-between items-start">
                <div className="min-w-0 flex-1">
                  <h4 className="text-sm font-black text-primary truncate tracking-tight">{s.schemeName}</h4>
                  <p className="text-[9px] font-black text-muted uppercase tracking-widest mt-1">Current Value: <CurrencyValue isPrivate={isPrivate} value={s.currentValue} /></p>
                </div>
                <div className={`px-3 py-1 rounded-full border text-[8px] font-black uppercase tracking-widest ${
                  s.recommendedAction === 'EXIT_NOW_TAX_FREE' ? 'bg-buy/10 text-buy border-buy/20' :
                  s.recommendedAction === 'WAIT_FOR_LTCG' ? 'bg-warning/10 text-warning border-warning/20' :
                  s.recommendedAction === 'HOLD_WAVE_RIDER' ? 'bg-secondary/10 text-secondary border-secondary/20' :
                  'bg-exit/10 text-exit border-exit/20'
                }`}>
                  {s.recommendedAction.replace(/_/g, ' ')}
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4 py-2 border-y border-white/5">
                <div>
                  <p className="text-[8px] font-black text-muted uppercase tracking-widest">Unrealized LTCG</p>
                  <p className="text-xs font-black text-primary tabular-nums"><CurrencyValue isPrivate={isPrivate} value={s.ltcgGains} /></p>
                </div>
                <div className="text-right">
                  <p className="text-[8px] font-black text-muted uppercase tracking-widest">Unrealized STCG</p>
                  <p className="text-xs font-black text-primary tabular-nums"><CurrencyValue isPrivate={isPrivate} value={s.stcgGains} /></p>
                </div>
              </div>

              <div className="space-y-3">
                <div className="flex justify-between items-center text-[9px] font-black uppercase tracking-widest">
                  <span className="text-muted">Exit Path Decision</span>
                  <span className="text-secondary">Optimal: {new Date(s.exitDateSuggestion).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}</span>
                </div>
                
                <div className="flex gap-2">
                  <div className={`flex-1 p-2 rounded-xl border text-center ${s.recommendedAction.includes('EXIT_NOW') ? 'bg-white/5 border-white/10' : 'border-white/5 opacity-40'}`}>
                    <p className="text-[7px] font-black text-muted uppercase tracking-widest mb-0.5">Exit Now</p>
                    <p className="text-[10px] font-black text-exit tabular-nums"><CurrencyValue isPrivate={isPrivate} value={s.taxIfExitNow} /></p>
                  </div>
                  <div className={`flex-1 p-2 rounded-xl border text-center ${s.recommendedAction === 'WAIT_FOR_LTCG' ? 'bg-warning/5 border-warning/20' : 'border-white/5 opacity-40'}`}>
                    <p className="text-[7px] font-black text-muted uppercase tracking-widest mb-0.5">Wait {s.daysToNextLtcg}d</p>
                    <p className="text-[10px] font-black text-buy tabular-nums"><CurrencyValue isPrivate={isPrivate} value={s.taxIfWaitForLtcg} /></p>
                  </div>
                </div>
                
                {s.taxSavingByWaiting > 0 && (
                  <p className="text-[8px] font-black text-buy uppercase tracking-widest text-center italic">
                    Waiting saves <CurrencyValue isPrivate={isPrivate} value={s.taxSavingByWaiting} /> after drift cost
                  </p>
                )}
              </div>
            </div>
          ))}
          {(portfolioData.tacticalPayload?.droppedFundSummaries || []).length === 0 && (
            <div className="col-span-full py-10 text-center border border-dashed border-white/10 rounded-[2rem] opacity-40">
              <p className="text-[10px] font-black uppercase tracking-widest">No dropped funds require exit planning</p>
            </div>
          )}
        </div>
      </section>

      {/* DRIFT CHART */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl shadow-xl">
        <div className="h-80 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 20, right: 30, left: 20, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.02)" vertical={false} />
              <XAxis 
                dataKey="name" 
                axisLine={false} 
                tickLine={false} 
                tick={{ fill: 'rgba(241,245,249,0.3)', fontSize: 10, fontWeight: 700 }}
              />
              <YAxis 
                axisLine={false} 
                tickLine={false} 
                tick={{ fill: 'rgba(241,245,249,0.3)', fontSize: 10, fontWeight: 700 }}
                unit="%"
              />
              {!isPrivate && (
                <Tooltip 
                  cursor={{ fill: 'rgba(255,255,255,0.02)' }}
                  contentStyle={{ backgroundColor: 'rgba(15,15,24,0.9)', backdropFilter: 'blur(20px)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '12px' }}
                />
              )}
              <ReferenceLine y={0} stroke="rgba(255,255,255,0.1)" />
              <Bar dataKey="drift" radius={[4, 4, 0, 0]} barSize={24}>
                {data.map((entry: any, index: number) => (
                  <Cell key={`cell-${index}`} fill={entry.color} fillOpacity={0.8} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
        <div className="mt-8 pt-8 border-t border-white/5 flex flex-wrap items-center gap-8">
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-md bg-exit shadow-[0_0_12px_rgba(248,113,113,0.4)]" />
            <span className="text-[10px] font-black uppercase tracking-widest text-muted">Overweight</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-md bg-buy shadow-[0_0_12px_rgba(74,222,128,0.4)]" />
            <span className="text-[10px] font-black uppercase tracking-widest text-muted">Underweight</span>
          </div>
          <div className="ml-auto flex items-center gap-4">
            <div className="text-right">
              <p className="text-[9px] font-black text-muted uppercase tracking-widest">Total Portfolio Drift</p>
              <p className="text-lg font-black text-primary">{totalDrift.toFixed(1)}%</p>
            </div>
            <div className={`px-4 py-2 rounded-2xl border font-black text-[10px] uppercase tracking-widest ${
              totalDrift < 5 ? 'text-buy bg-buy/10 border-buy/20' : 'text-warning bg-warning/10 border-warning/20'
            }`}>
              {totalDrift < 5 ? 'Stable' : 'Unbalanced'}
            </div>
          </div>
        </div>
      </section>

      {/* REBALANCE TIMELINE */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl shadow-xl">
        <div className="flex items-center justify-between mb-8">
          <div className="space-y-1">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em] flex items-center gap-2">
              <Clock size={12} className="text-accent" /> Correction Timeline
            </h3>
            <p className="text-xs text-muted font-bold uppercase tracking-widest opacity-60">Estimated months to fix underweight drift via SIP only</p>
          </div>
        </div>

        <div className="space-y-4">
          {timelines.map((t: any) => (
            <div key={t.name} className="space-y-2">
              <div className="flex justify-between items-end px-1">
                <span className="text-[11px] font-bold text-primary">{t.name}</span>
                <span className="text-[10px] font-black uppercase tracking-tighter text-secondary">
                  {t.actualMonths === Infinity ? '∞' : t.actualMonths} Months
                </span>
              </div>
              <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden border border-white/5">
                <div 
                  style={{ width: `${(t.months / 36) * 100}%`, background: t.color, boxShadow: `0 0 10px ${t.color}44` }}
                  className="h-full rounded-full"
                />
              </div>
            </div>
          ))}
          {timelines.length === 0 && (
            <div className="py-10 text-center border border-dashed border-white/5 rounded-2xl">
              <p className="text-muted text-[10px] font-black uppercase tracking-widest">No major underweight positions to track</p>
            </div>
          )}
        </div>
      </section>

      {/* SMART SIP PLANNER */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl shadow-xl space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="space-y-1">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em] flex items-center gap-2">
              <Sparkles size={12} className="text-accent" /> Smart SIP Planner
            </h3>
            <p className="text-xs text-muted font-bold uppercase tracking-widest opacity-60">
              Conviction-weighted, deficit-closing dynamic SIP routing
            </p>
          </div>
          
          <form onSubmit={handleCalculateSmartSip} className="flex items-center gap-3 w-full sm:w-auto">
            <div className="relative flex-1 sm:flex-initial">
              <span className="absolute left-3.5 top-1/2 -translate-y-1/2 text-muted text-xs font-bold">₹</span>
              <input 
                type="number" 
                value={sipBudget}
                onChange={(e) => setSipBudget(e.target.value)}
                className="bg-black/40 border border-white/10 rounded-xl pl-8 pr-4 py-2 text-sm text-primary font-bold focus:border-accent focus:outline-none w-full sm:w-44 tabular-nums"
                placeholder="SIP Budget"
                min="0"
                step="500"
              />
            </div>
            <button
              type="submit"
              disabled={smartSipLoading}
              className="px-4 py-2 bg-accent/10 border border-accent/20 rounded-xl text-[10px] font-black uppercase tracking-wider text-accent hover:bg-accent/20 transition-all cursor-pointer flex items-center gap-1.5 disabled:opacity-50"
            >
              {smartSipLoading ? 'Calculating...' : 'Calculate'}
            </button>
            {smartSipAllocations.length > 0 && (
              <button
                type="button"
                onClick={handleDownloadSmartSipCSV}
                className="px-4 py-2 bg-accent/10 border border-accent/20 rounded-xl text-[10px] font-black uppercase tracking-wider text-accent hover:bg-accent/20 transition-all cursor-pointer flex items-center gap-1.5"
              >
                <Download size={12} />
                Download Execution Sheet
              </button>
            )}
          </form>
        </div>

        {smartSipError && (
          <div className="bg-exit/10 border border-exit/20 p-4 rounded-2xl flex items-center gap-4">
            <AlertTriangle size={20} className="text-exit" />
            <p className="text-[10px] font-black text-exit uppercase tracking-widest leading-relaxed">
              {smartSipError}
            </p>
          </div>
        )}

        {smartSipAllocations.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="border-b border-white/5 text-[9px] font-black text-muted uppercase tracking-widest">
                  <th className="pb-3 pr-4">Fund Name</th>
                  <th className="pb-3 px-4 text-center">Target / Current</th>
                  <th className="pb-3 px-4 text-center">Z-Score</th>
                  <th className="pb-3 px-4 text-center">Weight Deficit</th>
                  <th className="pb-3 px-4 text-center">Multiplier</th>
                  <th className="pb-3 px-4 text-right">Allocated Share</th>
                  <th className="pb-3 pl-4 text-right">SIP Allocation</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-white/5">
                {smartSipAllocations.map((alloc, idx) => {
                  const isCheap = alloc.zScore < -1.0;
                  const isExpensive = alloc.zScore > 1.0;
                  const zScoreColor = isCheap ? 'text-buy' : isExpensive ? 'text-exit' : 'text-slate-400';
                  
                  return (
                    <tr key={idx} className="group hover:bg-white/[0.01] transition-all">
                      <td className="py-4 pr-4 min-w-[200px]">
                        <p className="text-xs font-black text-primary leading-tight tracking-tight">
                          {alloc.schemeName}
                        </p>
                        <p className="text-[9px] font-bold text-muted uppercase tracking-wider mt-0.5">
                          AMFI: {alloc.amfiCode}
                        </p>
                      </td>
                      <td className="py-4 px-4 text-center tabular-nums text-xs font-bold text-primary">
                        {alloc.targetPercentage.toFixed(1)}% / {alloc.currentPercentage.toFixed(1)}%
                      </td>
                      <td className="py-4 px-4 text-center tabular-nums text-xs font-black">
                        <span className={`px-2 py-0.5 rounded ${isCheap ? 'bg-buy/10' : isExpensive ? 'bg-exit/10' : 'bg-white/5'} ${zScoreColor}`}>
                          {alloc.zScore.toFixed(2)}
                        </span>
                      </td>
                      <td className="py-4 px-4 text-center tabular-nums text-xs font-bold text-slate-300">
                        {alloc.weightDeficit > 0 ? `${alloc.weightDeficit.toFixed(1)}%` : '-'}
                      </td>
                      <td className="py-4 px-4 text-center tabular-nums text-xs font-bold">
                        {alloc.weightDeficit > 0 ? (
                          <span className={alloc.adjustedDeficit > alloc.weightDeficit ? 'text-buy font-black' : alloc.adjustedDeficit < alloc.weightDeficit ? 'text-exit font-black' : 'text-slate-500'}>
                            {alloc.adjustedDeficit > alloc.weightDeficit ? '1.5x' : alloc.adjustedDeficit < alloc.weightDeficit ? '0.5x' : '1.0x'}
                          </span>
                        ) : '-'}
                      </td>
                      <td className="py-4 px-4 text-right tabular-nums text-xs font-black text-secondary">
                        {alloc.allocatedPercentage.toFixed(1)}%
                      </td>
                      <td className="py-4 pl-4 text-right tabular-nums text-sm font-black text-buy">
                        <CurrencyValue isPrivate={isPrivate} value={alloc.allocatedAmount} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* SIP SIMULATOR */}
      <section className="bg-accent/5 border border-accent/10 p-10 rounded-[2.5rem] shadow-2xl relative overflow-hidden flex flex-col lg:flex-row gap-12">
        <div className="flex-1 space-y-8">
          <div className="flex justify-between items-center">
            <div className="space-y-1">
              <h3 className="text-primary text-sm font-black uppercase tracking-[0.1em]">Deployment Control</h3>
              <p className="text-[10px] text-muted font-bold uppercase tracking-widest">Adjust monthly fire-power</p>
            </div>
            <CurrencyValue isPrivate={isPrivate} value={sipAmount} className="text-2xl font-black text-primary tabular-nums tracking-tighter glow-accent" />
          </div>
          
          <input 
            type="range" min="0" max="200000" step="5000" 
            value={sipAmount} 
            onChange={(e) => setSipAmount(parseInt(e.target.value) || 0)}
            className="w-full h-2 bg-white/5 rounded-full appearance-none cursor-pointer accent-accent hover:accent-accent-bright transition-all shadow-inner"
          />

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mt-8">
            {sipPlan.filter((s: any) => s.amount > 0).map((s: any) => (
              <div key={s.isin} className="bg-black/20 border border-white/5 p-4 rounded-2xl flex items-center justify-between group hover:border-white/10 transition-all shadow-lg">
                <div className="min-w-0">
                  <p className="text-[11px] font-black text-secondary truncate tracking-tight">{s.simpleName || s.schemeName}</p>
                  <p className="text-[9px] font-black text-muted uppercase tracking-widest mt-0.5">{s.sipPct}% Load</p>
                </div>
                <div className="text-right pl-4">
                  <CurrencyValue isPrivate={isPrivate} value={s.amount} className="text-[13px] font-black text-buy tabular-nums" />
                </div>
              </div>
            ))}
          </div>
        </div>
        
        <div className="lg:w-64 shrink-0 flex flex-col items-center justify-center p-8 bg-surface/60 backdrop-blur-3xl border border-white/10 rounded-[2rem] text-center shadow-2xl">
          <div className="p-4 bg-accent/10 rounded-3xl mb-4 text-accent">
            <Target size={32} />
          </div>
          <p className="text-muted text-[10px] font-black uppercase tracking-[0.2em] mb-2">Portfolio Sync</p>
          <p className="text-3xl font-black text-primary tabular-nums tracking-tighter">{(100 - totalDrift).toFixed(1)}%</p>
          <p className="text-[9px] font-bold text-secondary uppercase tracking-widest mt-1">Accuracy Score</p>
          
          <div className="mt-8 space-y-4 w-full pt-6 border-t border-white/5">
            <div className="flex justify-between text-[10px] font-black uppercase tracking-tighter">
              <span className="text-muted">Status</span>
              <span className={totalDrift < 5 ? 'text-buy' : 'text-warning'}>{totalDrift < 5 ? 'Optimal' : 'Drifting'}</span>
            </div>
            <div className="flex justify-between text-[10px] font-black uppercase tracking-tighter">
              <span className="text-muted">Target ETA</span>
              <span className="text-primary">{timelines[0]?.actualMonths || 0} Mo</span>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
