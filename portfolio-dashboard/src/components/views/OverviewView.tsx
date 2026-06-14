import { useMemo } from 'react';
import CurrencyValue from '../ui/CurrencyValue';
import { Progress } from '../ui/progress';
import { formatCurrencyShort } from '../../utils/formatters';
import FundsTable from '../ui/FundsTable';
import { useDashboardContext } from '../../context/DashboardContext';

export default function OverviewView({ 
  portfolioData, 
  sipAmount: _sipAmount, 
  setSipAmount: _setSipAmount, 
  lumpsum: _lumpsum, 
  setLumpsum: _setLumpsum,
  onFundClick: _onFundClick,
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
  if (!portfolioData) return null;

  const { setIsStrategyOpen } = useDashboardContext();

  const allSignals = useMemo(() => {
    return portfolioData.tacticalPayload?.allSignals || [];
  }, [portfolioData.tacticalPayload]);

  // Tax Exemption / Headroom Calculations
  const LTCG_LIMIT = 125000;
  const realizedLtcg = portfolioData.fyLtcgAlreadyRealized || 0.0;
  const headroomRemaining = Math.max(0, LTCG_LIMIT - realizedLtcg);
  const ltcgUsedPct = Math.min(100, (realizedLtcg / LTCG_LIMIT) * 100);

  // Profit/Return Calculations
  const unrealizedGain = portfolioData.currentValueAmount - portfolioData.totalInvestedAmount;
  const unrealizedGainPct = (unrealizedGain / (portfolioData.totalInvestedAmount || 1)) * 100;

  return (
    <div className="space-y-6 pb-12 text-slate-100">
      
      {/* 1. TAX HEADROOM PROGRESS BAR (TOP-LEVEL) */}
      <section className="bg-slate-900/60 border border-white/5 p-4 rounded-lg space-y-3">
        <div className="flex justify-between items-center">
          <div className="space-y-0.5">
            <h3 className="text-[10px] font-black uppercase tracking-wider text-slate-400">FY LTCG Exemption Headroom</h3>
            <p className="text-xs text-slate-400 font-medium">Tracking ₹1.25L annual tax-free gain capacity</p>
          </div>
          <div className="text-right">
            <p className="text-xs font-black text-green-400">
              <CurrencyValue isPrivate={isPrivate} value={headroomRemaining} /> Remaining
            </p>
          </div>
        </div>

        <div className="space-y-1.5">
          <Progress 
            value={ltcgUsedPct} 
            className="h-2 bg-white/5 border border-white/10" 
            indicatorClassName="bg-green-500 transition-none" 
          />
          <div className="flex justify-between items-center text-[9px] font-black uppercase tracking-wider text-slate-500">
            <span>₹{realizedLtcg.toLocaleString('en-IN')} Realized</span>
            <span>₹1.25L Limit</span>
          </div>
        </div>
      </section>

      {/* 2. HIGH-DENSITY PORTFOLIO SUMMARY CARD STRIP */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {/* Current Value */}
        <div className="bg-slate-900/60 border border-white/5 p-4 rounded-lg flex flex-col justify-between">
          <span className="text-[9px] font-black uppercase tracking-wider text-slate-400">Current Value</span>
          <span className="text-lg font-black text-slate-100 tracking-tight mt-1">
            <CurrencyValue isPrivate={isPrivate} value={portfolioData.currentValueAmount} />
          </span>
        </div>

        {/* Invested Value */}
        <div className="bg-slate-900/60 border border-white/5 p-4 rounded-lg flex flex-col justify-between">
          <span className="text-[9px] font-black uppercase tracking-wider text-slate-400">Total Invested</span>
          <span className="text-lg font-black text-slate-100 tracking-tight mt-1">
            <CurrencyValue isPrivate={isPrivate} value={portfolioData.totalInvestedAmount} />
          </span>
        </div>

        {/* Unrealized Returns */}
        <div className="bg-slate-900/60 border border-white/5 p-4 rounded-lg flex flex-col justify-between">
          <span className="text-[9px] font-black uppercase tracking-wider text-slate-400">Unrealized Gain</span>
          <div className="mt-1 flex items-baseline gap-1.5">
            <span className={`text-lg font-black tracking-tight ${unrealizedGain >= 0 ? 'text-green-400' : 'text-red-400'}`}>
              {unrealizedGain >= 0 ? '+' : ''}{isPrivate ? '••••' : formatCurrencyShort(unrealizedGain)}
            </span>
            <span className={`text-[10px] font-bold ${unrealizedGain >= 0 ? 'text-green-400' : 'text-red-400'}`}>
              ({unrealizedGainPct.toFixed(1)}%)
            </span>
          </div>
        </div>

        {/* Overall XIRR */}
        <div className="bg-slate-900/60 border border-white/5 p-4 rounded-lg flex flex-col justify-between">
          <span className="text-[9px] font-black uppercase tracking-wider text-slate-400">Overall XIRR</span>
          <span className={`text-lg font-black mt-1 tracking-tight ${parseFloat(portfolioData.overallXirr) >= 0 ? 'text-green-400' : 'text-red-400'}`}>
            {portfolioData.overallXirr}
          </span>
        </div>
      </div>

      <section className="space-y-2">
        <div className="flex justify-between items-center px-1">
          <h3 className="text-[10px] font-black uppercase tracking-wider text-slate-400">Tactical Allocation & Quantitative Signals</h3>
          <div className="flex items-center gap-3">
            <button
              onClick={() => setIsStrategyOpen?.(true)}
              className="px-2.5 py-1 bg-accent/10 border border-accent/20 rounded-lg text-[9px] font-black uppercase tracking-wider text-accent hover:bg-accent/20 transition-all cursor-pointer"
            >
              Edit Target Weights
            </button>
            <span className="text-[9px] font-black uppercase tracking-wider text-slate-500">
              {allSignals.length} Active Positions & Strategy Targets
            </span>
          </div>
        </div>
        <FundsTable 
          allSignals={allSignals} 
          schemeBreakdown={portfolioData.schemeBreakdown || []} 
        />
      </section>

    </div>
  );
}
