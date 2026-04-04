import { ShieldAlert, TrendingUp, CheckCircle2, Info, AlertTriangle } from 'lucide-react';
import { Badge } from "@/components/ui/badge";
import { formatCurrency } from '../../utils/formatters';


export default function SignalCard({ signal }: { signal: any }) {
  // Dark mode specific colors for actions
  const getActionStyles = (action: string) => {
    switch(action) {
      case 'BUY': return 'bg-blue-500/10 text-blue-400 border-blue-500/20';
      case 'EXIT': return 'bg-red-500/10 text-red-400 border-red-500/20';
      case 'TRIM': return 'bg-orange-500/10 text-orange-400 border-orange-500/20';
      default: return 'bg-zinc-800 text-zinc-400 border-zinc-700';
    }
  };

  const getStatusBadge = (status: string) => {
    switch(status) {
      case 'ACCUMULATOR': return <Badge variant="outline" className="bg-emerald-500/10 text-emerald-400 border-emerald-500/20">Accumulator</Badge>;
      case 'DROPPED': return <Badge variant="outline" className="bg-stone-500/10 text-stone-400 border-stone-500/20">Dropped</Badge>;
      case 'ACTIVE': return <Badge variant="outline" className="bg-indigo-500/10 text-indigo-400 border-indigo-500/20">Active Core</Badge>;
      default: return <Badge variant="outline">{status}</Badge>;
    }
  };

  return (
    <div className="bg-zinc-900 rounded-xl border border-zinc-800 p-5 hover:border-zinc-700 transition-colors">
      
      {/* Header */}
      <div className="flex justify-between items-start mb-4 gap-4">
        <h3 className="font-medium text-zinc-200 leading-tight">{signal.schemeName}</h3>
        <div className="shrink-0">{getStatusBadge(signal.fundStatus)}</div>
      </div>

      {/* Stats Row */}
<div className="grid grid-cols-2 gap-2 mb-4 p-3 bg-zinc-950/50 rounded-lg border border-zinc-800/50">
  <div className="border-r border-zinc-800">
    <p className="text-[9px] text-zinc-500 uppercase tracking-widest font-black">Conviction Stats</p>
   <div className="grid grid-cols-2 gap-4 mb-4 p-3 bg-zinc-950/50 rounded-lg border border-zinc-800/50">
  <div>
    <p className="text-[9px] text-zinc-500 uppercase font-black">Quality (Sortino)</p>
    <p className="text-sm font-bold text-blue-400">{signal.sortinoRatio?.toFixed(2) || '0.00'}</p>
  </div>
  <div>
    <p className="text-[9px] text-zinc-500 uppercase font-black">Risk (Max Pain)</p>
    <p className="text-sm font-bold text-rose-400">-{signal.maxDrawdown?.toFixed(1) || '0.0'}%</p>
  </div>
</div>
  </div>
  <div className="pl-2">
    <p className="text-[9px] text-zinc-500 uppercase tracking-widest font-black">Allocation</p>
    <div className="flex justify-between mt-1">
      <span className="text-[10px] text-zinc-400">Target:</span>
      <span className="text-[10px] font-bold text-zinc-200">{signal.plannedPercentage}%</span>
    </div>
    <div className="flex justify-between">
      <span className="text-[10px] text-zinc-400">Actual:</span>
      <span className={`text-[10px] font-bold ${signal.actualPercentage > signal.plannedPercentage ? 'text-orange-400' : 'text-blue-400'}`}>
        {signal.actualPercentage}%
      </span>
    </div>
  </div>
</div>

{/* Action Banner */}
<div className={`flex items-center justify-between p-3 rounded-lg border mb-4 ${getActionStyles(signal.action)}`}>
  <div className="flex items-center gap-2 font-black tracking-widest text-[11px] uppercase">
    {signal.action === 'BUY' && <TrendingUp size={16} />}
    {signal.action === 'EXIT' && <ShieldAlert size={16} />}
    {signal.action === 'HOLD' && <CheckCircle2 size={16} />}
    {signal.action}
  </div>
  {signal.action !== 'HOLD' && signal.amount && (
    <div className="font-mono text-sm flex items-center font-bold">
      {/* 🚀 FIXED: Using the central formatCurrency utility */}
      {formatCurrency(parseFloat(signal.amount))}
    </div>
  )}
</div>

      {/* Quant Engine Justifications */}
      <div className="space-y-2">
        {signal.justifications?.map((note: string, i: number) => {
          const isTaxWarning = note.includes("Tax Warning") || note.includes("Systemic Risk Halt") || note.includes("Tax Locked");
          const isTaxBenefit = note.includes("Tax Benefit") || note.includes("Tax Cleared");
          
          return (
            <div key={i} className={`flex items-start gap-2 text-xs p-2 rounded ${
              isTaxWarning ? 'bg-red-500/5 text-red-400' : 
              isTaxBenefit ? 'bg-emerald-500/5 text-emerald-400' : 
              'text-zinc-400'
            }`}>
              {isTaxWarning ? <AlertTriangle size={14} className="mt-0.5 shrink-0" /> : <Info size={14} className="mt-0.5 shrink-0 opacity-50" />}
              <p className="leading-relaxed">{note}</p>
            </div>
          )
        })}
      </div>
    </div>
  );
}