import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { useMemo, useState } from 'react';
import { ResponsivePie } from '@nivo/pie';
import { 
  Target, 
  Scissors, 
  ShieldAlert, 
  TrendingUp, 
  Activity,
  Layers,
  Wallet,
  ChevronDown,
  ChevronUp,
  Newspaper
} from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import CurrencyValue from '../ui/CurrencyValue';
import { Progress } from '../ui/progress';
import { formatCurrency, formatCurrencyShort } from '../../utils/formatters';
import { fetchAlphaFeed } from '../../services/api';

export default function OverviewView({ 
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
  const [isSentimentExpanded, setIsSentimentExpanded] = useState(false);

  // Fetch Alpha Sentiment Feed
  const { data: alphaFeed } = useQuery({
    queryKey: ['alphaFeed'],
    queryFn: fetchAlphaFeed,
    staleTime: 1000 * 60 * 10, // 10 minutes
  });

  if (!portfolioData) return null;

  const schemeBreakdown = portfolioData.schemeBreakdown || [];
  const activeFunds = useMemo(() => schemeBreakdown.filter((s: any) => s.currentValue > 0), [schemeBreakdown]);

  const allTimePerformers = useMemo(() => {
    return [...activeFunds]
      .filter((s: any) => s.xirr && s.xirr !== '0%' && s.xirr !== '0.0%')
      .map((s: any) => {
        const val = parseFloat(s.xirr.replace('%', ''));
        return { ...s, parsedXirr: isNaN(val) ? 0 : val };
      })
      .sort((a, b) => b.parsedXirr - a.parsedXirr);
  }, [activeFunds]);

  const monthlyPerformers = useMemo(() => {
    return [...activeFunds]
      .filter((s: any) => s.oneMonthReturn !== undefined && s.oneMonthReturn !== null)
      .sort((a, b) => (b.oneMonthReturn || 0) - (a.oneMonthReturn || 0));
  }, [activeFunds]);

  const payload = portfolioData.tacticalPayload || { 
    sipPlan: [], 
    opportunisticSignals: [], 
    activeSellSignals: [],
    exitQueue: [],
    harvestOpportunities: [],
    totalExitValue: 0,
    totalHarvestValue: 0,
    droppedFundsCount: 0
  };

  const exitCount = payload.exitQueue?.length || 0;
  const sellCount = payload.activeSellSignals?.length || 0;
  const harvestCount = payload.harvestOpportunities?.length || 0;
  const rebalancingTrades = payload.rebalancingTrades || [];

  const bearCount = activeFunds.filter((s: any) => s.hmmState === 'VOLATILE_BEAR').length;
  const bullCount = activeFunds.filter((s: any) => s.hmmState === 'CALM_BULL').length;
  const neutralCount = activeFunds.filter((s: any) => s.hmmState === 'STRESSED_NEUTRAL').length;

  const mask = (val: number | string) => isPrivate ? "••••" : String(val);

  // 1. Category Allocation Pie Data
  const categoryData = useMemo(() => {
    const cats: Record<string, number> = {};
    activeFunds.forEach((s: any) => {
      const cat = s.category || 'Equity';
      cats[cat] = (cats[cat] || 0) + s.currentValue;
    });
    return Object.entries(cats)
      .map(([name, value]) => ({ id: name, label: name, value }))
      .sort((a, b) => b.value - a.value);
  }, [activeFunds]);

  // 2. AMC Concentration Data
  const amcConcentration = useMemo(() => {
    const amcs: Record<string, number> = {};
    let total = 0;
    activeFunds.forEach((s: any) => {
      const amc = s.amc || 'Other AMC';
      amcs[amc] = (amcs[amc] || 0) + s.currentValue;
      total += s.currentValue;
    });
    if (total === 0) total = 1;
    return Object.entries(amcs)
      .map(([name, value]) => ({
        name,
        value,
        pct: (value / total) * 100
      }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 5);
  }, [activeFunds]);

  // Standing SIPs
  const standingSips = useMemo(() => 
    (payload.sipPlan || []).filter((s: any) => s.amount > 0 && s.mode === "SIP_STANDING"),
    [payload.sipPlan]
  );

  const container: Variants = {
    hidden: { opacity: 0 },
    show: {
      opacity: 1,
      transition: { staggerChildren: 0.08 }
    }
  };

  const bentoItem: Variants = {
    hidden: { opacity: 0, y: 20 },
    show: { 
      opacity: 1, 
      y: 0, 
      transition: { type: "spring", damping: 25, stiffness: 120 } 
    }
  };

  // Profit return values
  const unrealizedGain = portfolioData.currentValueAmount - portfolioData.totalInvestedAmount;
  const unrealizedGainPct = (unrealizedGain / (portfolioData.totalInvestedAmount || 1)) * 100;

  return (
    <motion.div 
      variants={container}
      initial="hidden"
      animate="show"
      className="space-y-8 pb-24"
    >
      {/* HEADER */}
      <header className="px-2">
        <h2 className="text-muted text-[10px] font-black uppercase tracking-[0.3em] mb-1 opacity-60">System Dashboard</h2>
        <p className="text-2xl font-black text-primary tracking-tighter">Portfolio Pulse Overview</p>
      </header>

      {/* 1. PORTFOLIO HEALTH STRIP */}
      <motion.div variants={bentoItem} className="grid grid-cols-1 md:grid-cols-4 gap-4">
        {/* Value Card */}
        <div className="glass-card-premium p-6 flex flex-col justify-between min-h-[110px] relative overflow-hidden group shadow-lg">
          <div className="absolute -right-6 -bottom-6 opacity-5 group-hover:opacity-10 transition-opacity">
            <Wallet size={80} />
          </div>
          <p className="text-[9px] font-black uppercase tracking-widest text-muted/60">Current Value</p>
          <p className="text-2xl font-black text-primary tracking-tight mt-2">
            <CurrencyValue isPrivate={isPrivate} value={portfolioData.currentValueAmount} />
          </p>
        </div>

        {/* Return Card */}
        <div className="glass-card-premium p-6 flex flex-col justify-between min-h-[110px] relative overflow-hidden group shadow-lg">
          <div className="absolute -right-6 -bottom-6 opacity-5 group-hover:opacity-10 transition-opacity">
            <TrendingUp size={80} />
          </div>
          <p className="text-[9px] font-black uppercase tracking-widest text-muted/60">Unrealized Gain / Loss</p>
          <div className="mt-2 flex items-baseline gap-2">
            <span className={`text-xl font-black tracking-tight ${unrealizedGain >= 0 ? 'text-buy' : 'text-exit'}`}>
              {unrealizedGain >= 0 ? '+' : ''}{isPrivate ? '••••' : formatCurrencyShort(unrealizedGain)}
            </span>
            <span className={`text-xs font-bold ${unrealizedGain >= 0 ? 'text-buy' : 'text-exit'}`}>
              ({unrealizedGainPct.toFixed(1)}%)
            </span>
          </div>
        </div>

        {/* XIRR Card */}
        <div className="glass-card-premium p-6 flex flex-col justify-between min-h-[110px] relative overflow-hidden group shadow-lg">
          <div className="absolute -right-6 -bottom-6 opacity-5 group-hover:opacity-10 transition-opacity">
            <Activity size={80} />
          </div>
          <p className="text-[9px] font-black uppercase tracking-widest text-muted/60">Overall XIRR</p>
          <p className={`text-2xl font-black mt-2 tracking-tight ${parseFloat(portfolioData.overallXirr) >= 0 ? 'text-buy' : 'text-exit'}`}>
            {portfolioData.overallXirr}
          </p>
        </div>

        {/* Tax Exemption Card */}
        <div className="glass-card-premium p-6 flex flex-col justify-between min-h-[110px] relative overflow-hidden group shadow-lg">
          <div className="absolute -right-6 -bottom-6 opacity-5 group-hover:opacity-10 transition-opacity">
            <Scissors size={80} />
          </div>
          <div className="flex justify-between items-center">
            <p className="text-[9px] font-black uppercase tracking-widest text-muted/60">LTCG Headroom</p>
            <span className="text-[8px] font-black text-harvest/60">₹1.25L Cap</span>
          </div>
          <div className="space-y-1.5 mt-2">
            <Progress 
              value={Math.min(100, (portfolioData.fyLtcgAlreadyRealized / 125000) * 100)} 
              className="h-1.5 bg-white/5 border border-white/5" 
              indicatorClassName="bg-gradient-to-r from-harvest to-accent shadow-[0_0_8px_rgba(180,190,254,0.3)]" 
            />
            <div className="flex justify-between items-baseline text-[10px]">
              <span className="text-harvest font-black">
                {isPrivate ? '••••' : formatCurrency(125000 - portfolioData.fyLtcgAlreadyRealized)} left
              </span>
              <span className="text-[8px] font-bold text-muted/30">FY 25-26</span>
            </div>
          </div>
        </div>
      </motion.div>

      {/* 2. FUND STATUS GRID */}
      <motion.div variants={bentoItem} className="space-y-4">
        <div className="flex items-center gap-2.5 px-2">
          <Layers size={16} className="text-accent" />
          <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Active Holdings Breakdowns</h3>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
          {activeFunds.length === 0 ? (
            <div className="col-span-full py-16 text-center border border-dashed border-white/10 rounded-[2.5rem] opacity-40">
              <Activity size={32} className="mx-auto mb-4" />
              <p className="text-[10px] font-black uppercase tracking-widest">No active mutual funds in portfolio</p>
            </div>
          ) : (
            activeFunds.map((s: any) => {
              const personalXirr = parseFloat(s.xirr || '0');
              const benchmarkXirr = s.benchmarkXirr || 0;
              const alpha = personalXirr - benchmarkXirr;
              const isWinning = alpha >= 0;
              const deltaStr = s.benchmarkXirr != null ? `${isWinning ? '+' : ''}${alpha.toFixed(1)}% vs ${s.benchmarkIndex || 'Bench'}` : 'No Bench';

              const activeDots = Math.max(1, Math.min(5, Math.round(s.convictionScore / 20)));

              // HMM Badge
              const isBull = s.hmmState === 'CALM_BULL';
              const isBear = s.hmmState === 'VOLATILE_BEAR';

              // Z-Score Percentile
              const zPct = Math.min(100, Math.max(0, (s.navPercentile1yr || 0) * 100));

              return (
                <motion.div
                  key={s.schemeName}
                  whileHover={{ scale: 1.02, y: -2 }}
                  onClick={() => onFundClick(s.schemeName)}
                  className="bg-surface/40 hover:bg-[#1f1f2e]/60 border border-white/5 hover:border-accent/20 rounded-3xl p-6 transition-all duration-300 shadow-lg cursor-pointer flex flex-col justify-between space-y-4 min-h-[220px] relative overflow-hidden group"
                >
                  {/* Subtle top decoration card highlight */}
                  <div className="absolute top-0 inset-x-0 h-[2px] bg-gradient-to-r from-transparent via-accent/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity" />
                  
                  {/* Fund Name and Category */}
                  <div className="space-y-1">
                    <div className="flex justify-between items-start">
                      <span className="text-[8px] font-black uppercase tracking-[0.15em] text-muted opacity-50 truncate max-w-[120px]">
                        {s.category}
                      </span>
                      {/* Action Badge */}
                      <span className={`px-2 py-0.5 rounded text-[8px] font-black uppercase tracking-widest border ${
                        s.action === 'BUY' ? 'text-buy bg-buy/10 border-buy/20' : 
                        s.action === 'EXIT' || s.action === 'SELL' ? 'text-exit bg-exit/10 border-exit/20' : 
                        s.action === 'WATCH' ? 'text-warning bg-warning/10 border-warning/20' :
                        'text-[#89b4fa] bg-[#89b4fa]/10 border-[#89b4fa]/20'
                      }`}>
                        {s.action}
                      </span>
                    </div>
                    <h4 className="text-xs font-black text-primary group-hover:text-white transition-colors tracking-tight leading-snug line-clamp-2">
                      {s.simpleName || s.schemeName}
                    </h4>
                  </div>

                  {/* Pricing and Performance */}
                  {/* Pricing and Performance */}
                  <div className="grid grid-cols-3 gap-2">
                    <div className="space-y-0.5 min-w-0">
                      <p className="text-[8px] font-bold text-muted/50 uppercase tracking-widest truncate">Holding Value</p>
                      <p className="text-sm font-black text-primary tracking-tight truncate">
                        <CurrencyValue isPrivate={isPrivate} value={s.currentValue} />
                      </p>
                    </div>
                    <div className="space-y-0.5 text-center min-w-0">
                      <p className="text-[8px] font-bold text-muted/50 uppercase tracking-widest truncate">30D Return</p>
                      <p className={`text-sm font-black tracking-tight truncate ${s.oneMonthReturn != null && s.oneMonthReturn >= 0 ? 'text-buy' : 'text-exit'}`}>
                        {isPrivate ? '••••' : (s.oneMonthReturn != null ? `${s.oneMonthReturn >= 0 ? '+' : ''}${s.oneMonthReturn.toFixed(2)}%` : '—')}
                      </p>
                    </div>
                    <div className="text-right space-y-0.5 min-w-0">
                      <p className="text-[8px] font-bold text-muted/50 uppercase tracking-widest truncate">Personal XIRR</p>
                      <p className={`text-sm font-black tracking-tight truncate ${personalXirr >= 0 ? 'text-buy' : 'text-exit'}`}>
                        {s.xirr || '0%'}
                      </p>
                      <p className={`text-[8px] font-black uppercase tracking-tighter truncate ${isWinning ? 'text-buy' : 'text-exit'}`}>
                        {deltaStr}
                      </p>
                    </div>
                  </div>

                  {/* Quantitative features: Conviction & Regime */}
                  <div className="grid grid-cols-2 gap-3 pt-3 border-t border-white/5">
                    {/* Conviction Dots */}
                    <div className="space-y-1">
                      <p className="text-[8px] font-bold text-muted/50 uppercase tracking-widest">Conviction</p>
                      <div className="flex items-center gap-1.5 h-3">
                        <div className="flex gap-1 items-center">
                          {[1, 2, 3, 4, 5].map(dot => {
                            const filled = dot <= activeDots;
                            let dotBg = 'bg-white/10';
                            if (filled) {
                              dotBg = s.convictionScore >= 65 ? 'bg-buy shadow-[0_0_5px_rgba(166,227,161,0.5)]' : s.convictionScore >= 45 ? 'bg-warning shadow-[0_0_5px_rgba(251,146,60,0.5)]' : 'bg-exit shadow-[0_0_5px_rgba(243,139,168,0.5)]';
                            }
                            return <span key={dot} className={`w-1.5 h-1.5 rounded-full ${dotBg}`} />;
                          })}
                        </div>
                        <span className="text-[9px] font-black text-secondary">({s.convictionScore})</span>
                      </div>
                    </div>

                    {/* HMM Regime Badge */}
                    <div className="space-y-1">
                      <p className="text-[8px] font-bold text-muted/50 uppercase tracking-widest">Regime</p>
                      <div className="flex items-center gap-1.5">
                        <span className={`w-1.5 h-1.5 rounded-full ${isBull ? 'bg-buy' : isBear ? 'bg-exit' : 'bg-warning'} animate-pulse`} />
                        <span className="text-[9px] font-black uppercase text-secondary tracking-widest">
                          {isBull ? 'Bull' : isBear ? 'Bear' : 'Neutral'}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* Z-Score Percentile Indicator */}
                  <div className="space-y-1 pt-1">
                    <div className="flex justify-between text-[7px] font-bold text-muted/40 uppercase tracking-widest">
                      <span>Cheap</span>
                      <span>Expensive</span>
                    </div>
                    <div className="h-1 w-full bg-white/5 rounded-full relative overflow-visible border border-white/5">
                      <div className="absolute inset-0 bg-gradient-to-r from-buy/20 via-warning/5 to-exit/20 rounded-full" />
                      <div 
                        className="absolute top-1/2 -translate-y-1/2 w-1.5 h-1.5 rounded-full bg-accent border border-[#11111b] shadow-[0_0_5px_rgba(129,140,248,0.8)]" 
                        style={{ left: `${zPct}%` }}
                      />
                    </div>
                  </div>
                </motion.div>
              );
            })
          )}
        </div>
      </motion.div>

      {/* PERFORMANCE LEADERBOARD */}
      <motion.div variants={bentoItem} className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* All-Time Leaderboard */}
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-[2.5rem] shadow-2xl flex flex-col justify-between hover:border-accent/10 transition-all">
          <div className="space-y-1 mb-6">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em] flex items-center gap-2">
              <TrendingUp size={14} className="text-buy" /> All-Time Performance Leaders
            </h3>
            <p className="text-[9px] font-bold text-muted/40 uppercase tracking-widest">Ranked by Annualized XIRR</p>
          </div>

          <div className={`grid grid-cols-1 ${allTimePerformers.length >= 4 ? 'md:grid-cols-2' : ''} gap-6 flex-1`}>
            {/* Top All-Time */}
            <div className="space-y-3">
              <p className="text-[9px] font-black uppercase tracking-widest text-buy border-b border-buy/10 pb-2">Top Performers</p>
              {allTimePerformers.length === 0 ? (
                <p className="text-[10px] text-muted/40 uppercase font-black py-4">No data available</p>
              ) : (
                allTimePerformers.slice(0, 3).map((s: any, idx: number) => (
                  <div 
                    key={s.schemeName}
                    onClick={() => onFundClick(s.schemeName)}
                    className="flex justify-between items-center p-3 bg-buy/5 hover:bg-buy/10 rounded-2xl border border-buy/10 cursor-pointer transition-all"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="text-[10px] font-black text-primary truncate leading-tight">
                        {idx + 1}. {s.simpleName || s.schemeName}
                      </p>
                      <p className="text-[8px] font-bold text-muted/50 uppercase tracking-widest mt-1">
                        {s.category}
                      </p>
                    </div>
                    <span className="text-xs font-black text-buy ml-2 shrink-0">
                      {s.xirr}
                    </span>
                  </div>
                ))
              )}
            </div>

            {/* Worst All-Time (Only show if total funds >= 4 to avoid overlap) */}
            {allTimePerformers.length >= 4 && (
              <div className="space-y-3">
                <p className="text-[9px] font-black uppercase tracking-widest text-exit border-b border-exit/10 pb-2">Underperformers</p>
                {[...allTimePerformers].reverse().slice(0, 3).map((s: any, idx: number) => (
                  <div 
                    key={s.schemeName}
                    onClick={() => onFundClick(s.schemeName)}
                    className="flex justify-between items-center p-3 bg-exit/5 hover:bg-exit/10 rounded-2xl border border-exit/10 cursor-pointer transition-all"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="text-[10px] font-black text-primary truncate leading-tight">
                        {idx + 1}. {s.simpleName || s.schemeName}
                      </p>
                      <p className="text-[8px] font-bold text-muted/50 uppercase tracking-widest mt-1">
                        {s.category}
                      </p>
                    </div>
                    <span className="text-xs font-black text-exit ml-2 shrink-0">
                      {s.xirr}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>

        {/* Monthly Leaderboard */}
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-[2.5rem] shadow-2xl flex flex-col justify-between hover:border-accent/10 transition-all">
          <div className="space-y-1 mb-6">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em] flex items-center gap-2">
              <Activity size={14} className="text-accent" /> Monthly Performance Leaders
            </h3>
            <p className="text-[9px] font-bold text-muted/40 uppercase tracking-widest">Ranked by 30-Day Absolute Return</p>
          </div>

          <div className={`grid grid-cols-1 ${monthlyPerformers.length >= 4 ? 'md:grid-cols-2' : ''} gap-6 flex-1`}>
            {/* Top Monthly */}
            <div className="space-y-3">
              <p className="text-[9px] font-black uppercase tracking-widest text-buy border-b border-buy/10 pb-2">Top Performers</p>
              {monthlyPerformers.length === 0 ? (
                <div className="py-6 text-center border border-dashed border-white/5 rounded-2xl opacity-40 h-full flex items-center justify-center">
                  <p className="text-[8px] font-black uppercase tracking-widest">No 30-day price history</p>
                </div>
              ) : (
                monthlyPerformers.slice(0, 3).map((s: any, idx: number) => (
                  <div 
                    key={s.schemeName}
                    onClick={() => onFundClick(s.schemeName)}
                    className="flex justify-between items-center p-3 bg-buy/5 hover:bg-buy/10 rounded-2xl border border-buy/10 cursor-pointer transition-all"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="text-[10px] font-black text-primary truncate leading-tight">
                        {idx + 1}. {s.simpleName || s.schemeName}
                      </p>
                      <p className="text-[8px] font-bold text-muted/50 uppercase tracking-widest mt-1">
                        {s.category}
                      </p>
                    </div>
                    <span className="text-xs font-black text-buy ml-2 shrink-0">
                      {s.oneMonthReturn !== undefined && s.oneMonthReturn !== null ? `+${s.oneMonthReturn.toFixed(1)}%` : '0%'}
                    </span>
                  </div>
                ))
              )}
            </div>

            {/* Worst Monthly (Only show if total funds >= 4 to avoid overlap) */}
            {monthlyPerformers.length >= 4 && (
              <div className="space-y-3">
                <p className="text-[9px] font-black uppercase tracking-widest text-exit border-b border-exit/10 pb-2">Underperformers</p>
                {[...monthlyPerformers].reverse().slice(0, 3).map((s: any, idx: number) => (
                  <div 
                    key={s.schemeName}
                    onClick={() => onFundClick(s.schemeName)}
                    className="flex justify-between items-center p-3 bg-exit/5 hover:bg-exit/10 rounded-2xl border border-exit/10 cursor-pointer transition-all"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="text-[10px] font-black text-primary truncate leading-tight">
                        {idx + 1}. {s.simpleName || s.schemeName}
                      </p>
                      <p className="text-[8px] font-bold text-muted/50 uppercase tracking-widest mt-1">
                        {s.category}
                      </p>
                    </div>
                    <span className="text-xs font-black text-exit ml-2 shrink-0">
                      {s.oneMonthReturn !== undefined && s.oneMonthReturn !== null ? `${s.oneMonthReturn.toFixed(1)}%` : '0%'}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>
      </motion.div>

      {/* 3. PORTFOLIO COMPOSITION */}
      <motion.div variants={bentoItem} className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Category Allocation Pie Card */}
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-[2.5rem] shadow-2xl h-[360px] flex flex-col hover:border-accent/10 transition-all">
          <div className="flex items-center gap-3 mb-4">
            <TrendingUp size={16} className="text-accent" />
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Category Diversification</h3>
          </div>
          <div className="flex-1 min-h-0 relative">
            {categoryData.length === 0 ? (
              <div className="absolute inset-0 flex items-center justify-center text-muted/40 text-[10px] uppercase font-black">
                No composition data
              </div>
            ) : (
              <ResponsivePie
                data={categoryData}
                margin={{ top: 20, right: 40, bottom: 20, left: 40 }}
                innerRadius={0.65}
                padAngle={2}
                cornerRadius={10}
                activeOuterRadiusOffset={8}
                borderWidth={1}
                borderColor={{ from: 'color', modifiers: [['darker', 0.2]] }}
                enableArcLinkLabels={false}
                arcLabelsSkipAngle={10}
                arcLabelsTextColor="#ffffff"
                colors={{ scheme: 'category10' }}
                theme={{
                  tooltip: { container: { background: '#181825', border: '1px solid rgba(255,255,255,0.1)', color: '#cdd6f4', fontSize: 11, borderRadius: 12, boxShadow: '0 10px 15px -3px rgba(0,0,0,0.5)' } },
                  labels: { text: { fontSize: 10, fontWeight: 700 } }
                }}
                valueFormat={(value) => mask(formatCurrency(value))}
              />
            )}
          </div>
        </section>

        {/* AMC Concentration Card */}
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-[2.5rem] shadow-2xl h-[360px] flex flex-col justify-between hover:border-accent/10 transition-all">
          <div className="space-y-1">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">AMC Concentration</h3>
            <p className="text-[9px] font-bold text-muted/40 uppercase tracking-widest">Top 5 AMC exposure limits</p>
          </div>
          
          <div className="space-y-4 flex-1 flex flex-col justify-center">
            {amcConcentration.length === 0 ? (
              <p className="text-center text-muted/40 text-[10px] uppercase font-black">No AMC data</p>
            ) : (
              amcConcentration.map((item) => (
                <div key={item.name} className="space-y-1">
                  <div className="flex justify-between text-[10px] font-black text-secondary">
                    <span className="truncate max-w-[200px]">{item.name}</span>
                    <span>{item.pct.toFixed(1)}%</span>
                  </div>
                  <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden border border-white/5">
                    <div 
                      className="h-full bg-accent opacity-80 rounded-full" 
                      style={{ width: `${item.pct}%` }} 
                    />
                  </div>
                </div>
              ))
            )}
          </div>
        </section>
      </motion.div>

      {/* 4. ACTION QUEUE */}
      <motion.div variants={bentoItem} className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* SIP Budget & Deployment */}
        <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-[2.5rem] shadow-2xl space-y-6 flex flex-col justify-between">
          <div className="space-y-2">
            <h3 className="text-buy text-[10px] font-black uppercase tracking-[0.3em]">Active Monthly Deployment</h3>
            <p className="text-lg font-black text-primary tracking-tight flex items-center gap-2">
              <Target size={18} className="text-accent" /> SIP Standing Instructions
            </p>
          </div>

          <div className="bg-black/20 p-4 rounded-2xl border border-white/5 flex items-center justify-between">
            <div className="space-y-1">
              <label className="text-[9px] font-black uppercase tracking-widest text-muted">Tactical Lumpsum</label>
              <input 
                type="number" 
                value={lumpsum || ''} 
                onChange={(e) => setLumpsum(parseInt(e.target.value) || 0)}
                placeholder="₹0"
                className="bg-black/40 border border-white/10 rounded-xl px-4 py-2 text-xs text-primary tabular-nums font-black focus:outline-none focus:border-accent/50 transition-all w-28 shadow-inner"
              />
            </div>
            <div className="h-10 w-px bg-white/5" />
            <div className="space-y-1">
              <div className="flex justify-between text-[9px] font-black uppercase tracking-widest text-muted">
                <span>Budget</span>
                <span className="text-accent font-black tracking-tighter ml-2"><CurrencyValue isPrivate={isPrivate} value={sipAmount} /></span>
              </div>
              <input 
                type="range" min="0" max="500000" step="10000" 
                value={sipAmount} 
                onChange={(e) => setSipAmount(parseInt(e.target.value) || 0)}
                className="w-36 h-1 bg-white/10 rounded-full appearance-none cursor-pointer accent-accent transition-all"
              />
            </div>
          </div>

          <div className="space-y-3">
            {standingSips.length === 0 ? (
              <div className="p-6 text-center border border-dashed border-white/5 rounded-2xl opacity-40">
                <span className="text-[9px] font-black uppercase tracking-widest">No active SIP standing targets</span>
              </div>
            ) : (
              standingSips.slice(0, 3).map((s: any) => (
                <div 
                  key={s.isin} 
                  onClick={() => onFundClick(s.schemeName)}
                  className="flex justify-between items-center p-3 bg-black/20 rounded-xl border border-white/5 hover:border-accent/20 cursor-pointer transition-all"
                >
                  <div className="min-w-0">
                    <p className="text-[10px] font-black text-secondary truncate">{s.simpleName || s.schemeName}</p>
                    <p className="text-[8px] font-bold text-muted uppercase tracking-widest mt-0.5">Target: {s.sipPct}% Allocation</p>
                  </div>
                  <span className="text-xs font-black text-buy tabular-nums">
                    {isPrivate ? '••••' : `₹${(s.amount / 1000).toFixed(1)}k`}
                  </span>
                </div>
              ))
            )}
            {standingSips.length > 3 && (
              <p className="text-[8px] font-bold text-muted/40 uppercase tracking-widest text-center">
                + {standingSips.length - 3} more standing instructions
              </p>
            )}
          </div>
        </div>

        {/* Optimizations & Actions Queue */}
        <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-[2.5rem] shadow-2xl space-y-6 flex flex-col justify-between">
          <div className="space-y-2">
            <h3 className="text-warning text-[10px] font-black uppercase tracking-[0.3em]">Optimizations Queue</h3>
            <p className="text-lg font-black text-primary tracking-tight">Pending System Signals</p>
          </div>

          {/* Regime consensus indicators */}
          <div className="bg-black/20 border border-white/5 rounded-2xl p-4 space-y-2">
            <p className="text-[8px] font-black uppercase tracking-[0.2em] text-muted">Portfolio Regime Consensus</p>
            <div className="flex w-full h-1.5 rounded-full overflow-hidden">
              <div style={{ width: `${(bullCount / Math.max(1, activeFunds.length)) * 100}%` }} className="bg-buy" />
              <div style={{ width: `${(neutralCount / Math.max(1, activeFunds.length)) * 100}%` }} className="bg-warning/50" />
              <div style={{ width: `${(bearCount / Math.max(1, activeFunds.length)) * 100}%` }} className="bg-exit" />
            </div>
            <div className="flex justify-between text-[8px] font-black uppercase tracking-widest text-muted/60">
              <span>{bullCount} Bull</span>
              <span>{neutralCount} Neutral</span>
              <span>{bearCount} Bear</span>
            </div>
          </div>

          <div className="space-y-3">
            {/* Exit Signals */}
            {exitCount + sellCount > 0 && (
              <div className="flex items-center justify-between p-3.5 bg-exit/5 border border-exit/20 rounded-2xl">
                <div className="flex items-center gap-3">
                  <ShieldAlert size={16} className="text-exit" />
                  <span className="text-[10px] font-black uppercase tracking-widest text-exit">Dropped / Trim Candidates</span>
                </div>
                <span className="px-2 py-0.5 bg-exit/10 border border-exit/20 rounded text-[8px] font-black text-exit">
                  {exitCount + sellCount} SIGNALS
                </span>
              </div>
            )}

            {/* Tax Harvest Signals */}
            {harvestCount > 0 && (
              <div className="flex items-center justify-between p-3.5 bg-harvest/5 border border-harvest/20 rounded-2xl">
                <div className="flex items-center gap-3">
                  <Scissors size={16} className="text-harvest" />
                  <span className="text-[10px] font-black uppercase tracking-widest text-harvest">Tax Harvest Opportunities</span>
                </div>
                <span className="px-2 py-0.5 bg-harvest/10 border border-harvest/20 rounded text-[8px] font-black text-harvest">
                  {harvestCount} SIGNALS
                </span>
              </div>
            )}

            {/* Rebalance Paired Trades */}
            {rebalancingTrades.length > 0 && (
              <div className="flex items-center justify-between p-3.5 bg-accent/5 border border-accent/20 rounded-2xl">
                <div className="flex items-center gap-3">
                  <Layers size={16} className="text-accent" />
                  <span className="text-[10px] font-black uppercase tracking-widest text-accent">Strategic Rebalance Moves</span>
                </div>
                <span className="px-2 py-0.5 bg-accent/10 border border-accent/20 rounded text-[8px] font-black text-accent">
                  {rebalancingTrades.length} TRADES
                </span>
              </div>
            )}

            {exitCount + sellCount === 0 && harvestCount === 0 && rebalancingTrades.length === 0 && (
              <div className="p-8 bg-surface/20 border border-dashed border-white/5 rounded-2xl text-center opacity-40">
                <p className="text-[9px] font-black uppercase tracking-widest">Orbit is clean. No active optimization tasks.</p>
              </div>
            )}
          </div>
        </div>
      </motion.div>

      {/* 5. COLLAPSIBLE SENTIMENT & ALPHA FEED */}
      <motion.div variants={bentoItem} className="border border-white/5 rounded-[2.5rem] bg-surface/20 shadow-xl overflow-hidden">
        <button
          onClick={() => setIsSentimentExpanded(!isSentimentExpanded)}
          className="w-full px-8 py-5 flex items-center justify-between hover:bg-white/[0.02] transition-colors"
        >
          <div className="flex items-center gap-3.5">
            <Newspaper size={18} className="text-accent" />
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Market Intelligence & Sentiment Feed</h3>
          </div>
          {isSentimentExpanded ? <ChevronUp size={16} className="text-muted" /> : <ChevronDown size={16} className="text-muted" />}
        </button>

        <AnimatePresence>
          {isSentimentExpanded && (
            <motion.div
              initial={{ height: 0 }}
              animate={{ height: 'auto' }}
              exit={{ height: 0 }}
              className="overflow-hidden border-t border-white/5"
            >
              <div className="p-8 space-y-4">
                {alphaFeed && alphaFeed.length > 0 ? (
                  alphaFeed.map((feedItem: any, index: number) => (
                    <div 
                      key={index} 
                      className="p-4 bg-black/20 rounded-2xl border border-white/5 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 hover:border-white/10 transition-all"
                    >
                      <div className="space-y-1 flex-1">
                        <p className="text-xs font-black text-primary leading-snug tracking-tight">
                          {feedItem.title}
                        </p>
                        <p className="text-[8px] font-bold text-muted/40 uppercase tracking-widest">
                          {new Date(feedItem.timestamp).toLocaleString()}
                        </p>
                      </div>
                      <div className="flex items-center gap-3.5">
                        <span className={`px-2.5 py-0.5 rounded-full text-[8px] font-black uppercase tracking-widest ${
                          feedItem.sentiment === 'positive' ? 'text-buy bg-buy/10 border border-buy/20' : 
                          feedItem.sentiment === 'negative' ? 'text-exit bg-exit/10 border border-exit/20' :
                          'text-muted bg-white/5 border border-white/10'
                        }`}>
                          {feedItem.sentiment}
                        </span>
                        <span className="text-[9px] font-bold text-muted/60 uppercase tracking-widest">
                          Conf: {Math.round(feedItem.confidence * 100)}%
                        </span>
                      </div>
                    </div>
                  ))
                ) : (
                  <p className="text-center text-muted/40 text-[10px] uppercase font-black py-4">No feeds available</p>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </motion.div>
  );
}
