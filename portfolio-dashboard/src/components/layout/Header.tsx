import { Zap } from 'lucide-react';
import { formatCurrency } from '../../utils/formatters';

export default function Header({ investorPan, currentValue, xirr }: { investorPan: string, currentValue: number, xirr: string }) {
  return (
    <div className="flex flex-col lg:flex-row justify-between items-start lg:items-center gap-6 bg-zinc-900/50 p-8 rounded-3xl border border-zinc-800">
      <div className="space-y-1">
        <div className="flex items-center gap-3">
          <Zap className="text-blue-500" size={24} fill="#3b82f6" />
          <h1 className="text-2xl font-black text-white italic tracking-tighter uppercase">Quant Command</h1>
        </div>
        <p className="text-[10px] text-zinc-500 font-bold tracking-[0.3em] uppercase">Intelligence Matrix // {investorPan}</p>
      </div>
      <div className="flex gap-8">
        <div>
          <p className="text-[9px] uppercase text-zinc-500 font-black mb-1">Total Valuation</p>
          <p className="text-3xl font-black text-white tracking-tighter">{formatCurrency(currentValue)}</p>
        </div>
        <div>
          <p className="text-[9px] uppercase text-zinc-500 font-black mb-1">Portfolio XIRR</p>
          <p className={`text-3xl font-black tracking-tighter ${parseFloat(xirr) < 0 ? 'text-rose-500' : 'text-emerald-500'}`}>
            {xirr}
          </p>
        </div>
      </div>
    </div>
  );
}