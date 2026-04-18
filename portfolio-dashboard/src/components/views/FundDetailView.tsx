import { useEffect, useState, useMemo } from 'react';
import { motion, type Variants } from 'framer-motion';
import { X, Zap, Info, ShieldCheck, Activity } from 'lucide-react';
import { convictionColor, buildPlainEnglishReason } from '../../utils/formatters';
import * as Dialog from '@radix-ui/react-dialog';
import * as Tooltip from '@radix-ui/react-tooltip';
import CurrencyValue from '../ui/CurrencyValue';
import { fetchFundHistory } from '../../services/api';
import { ResponsiveLine } from '@nivo/line';
import { 
  Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, ResponsiveContainer 
} from 'recharts';

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
  const [history, setHistory] = useState<any>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (isOpen && fund?.amfiCode) {
      setLoading(true);
      fetchFundHistory(fund.amfiCode, fund.benchmarkIndex || "NIFTY 50")
        .then(setHistory)
        .catch(console.error)
        .finally(() => setLoading(false));
    }
  }, [isOpen, fund]);

  if (!fund) return null;

  const { normalizedHistory, needsBackfill } = useMemo(() => {
    if (!history || !history.fund) return { normalizedHistory: [], needsBackfill: false };
    if (history.fund.length < 2) return { normalizedHistory: [], needsBackfill: true };
    
    // Logic: If user bought recently, we still want to show at least 6 months of context
    const sixMonthsAgo = new Date();
    sixMonthsAgo.setMonth(sixMonthsAgo.getMonth() - 6);
    const contextDate = sixMonthsAgo.toISOString().split('T')[0];

    // Anchor is either entry date OR context date (whichever is earlier)
    const entryDate = fund.lastBuyDate || history.fund[history.fund.length - 1].navDate;
    const filterDate = entryDate < contextDate ? entryDate : contextDate;

    // Filter and Reverse because they come from API in DESC order
    const fData = [...history.fund]
      .filter(d => d.navDate >= filterDate)
      .reverse();
    
    const bData = (history.benchmark && history.benchmark.length > 0) 
      ? [...history.benchmark].filter(d => d.date >= filterDate).reverse() 
      : [];

    if (fData.length < 2) return { normalizedHistory: [], needsBackfill: false };

    const oldestFundNav = fData[0].nav;
    const oldestBenchPrice = bData.length > 0 ? bData[0].closingPrice : 1;

    const series = [
      {
        id: "Fund",
        color: "#cba6f7", // Mauve
        data: fData.map((d: any) => ({
          x: d.navDate,
          y: parseFloat(((d.nav / oldestFundNav) * 100).toFixed(2))
        }))
      }
    ];

    if (bData.length >= 2) {
      series.push({
        id: "Benchmark",
        color: "rgba(166, 227, 161, 0.3)", // Green-ish dim
        data: bData.map((d: any) => ({
          x: d.date,
          y: parseFloat(((d.closingPrice / oldestBenchPrice) * 100).toFixed(2))
        }))
      });
    }

    return { normalizedHistory: series, needsBackfill: false };
  }, [history, fund.lastBuyDate]);

  // Design Improvement 5: Use real sub-scores from the 7-factor model
  const hasRealScores = fund.yieldScore != null && fund.riskScore != null && fund.valueScore != null;
  
  const components = [
    { 
      label: 'Yield', 
      score: hasRealScores ? fund.yieldScore : 50, 
      weight: '18%', 
      full: 'Yield Strength',
      tooltip: 'Your personal CAGR from tax lots relative to portfolio best.' 
    },
    { 
      label: 'Risk', 
      score: hasRealScores ? fund.riskScore : 50, 
      weight: '20%', 
      full: 'Risk Adjusted',
      tooltip: 'Sortino ratio (fixed curve) - captures negative risk meaningfully.' 
    },
    { 
      label: 'Value', 
      score: hasRealScores ? fund.valueScore : 50, 
      weight: '20%', 
      full: 'Value Entry',
      tooltip: '252-day Rolling Z-Score. Measures statistical cheapness.' 
    },
    { 
      label: 'Pain', 
      score: hasRealScores ? fund.painScore : 50, 
      weight: '15%', 
      full: 'Recovery Quality',
      tooltip: 'Blended Max Drawdown + OU Mean Reversion Speed.' 
    },
    { 
      label: 'Regime', 
      score: hasRealScores ? fund.regimeScore : 50, 
      weight: '12%', 
      full: 'Market Context',
      tooltip: 'HMM Bear Probability. Suppresses buys in deteriorating regimes.' 
    },
    { 
      label: 'Friction', 
      score: hasRealScores ? fund.frictionScore : 50, 
      weight: '10%', 
      full: 'Tax Logic',
      tooltip: 'Tax drag simulation (aligned to 20% STCG ceiling).' 
    },
    { 
      label: 'Quality', 
      score: hasRealScores ? fund.expenseScore : 50, 
      weight: '5%', 
      full: 'Cost & Size',
      tooltip: 'Expense Ratio drag + AUM sweet-spot scoring.' 
    },
  ];

  const radarData = components.map(c => ({ subject: c.label, A: c.score, fullMark: 100 }));

  const container: Variants = {
    hidden: { opacity: 0 },
    show: { opacity: 1, transition: { staggerChildren: 0.1 } }
  };

  const item: Variants = {
    hidden: { opacity: 0, x: 20 },
    show: { 
      opacity: 1, 
      x: 0, 
      transition: { 
        type: "spring" as const, 
        stiffness: 300, 
        damping: 24 
      } 
    }
  };

  return (
    <Dialog.Root open={isOpen} onOpenChange={onClose}>
      <Dialog.Portal>
        <Dialog.Overlay asChild>
          <motion.div 
            initial={{ opacity: 0 }} 
            animate={{ opacity: 1 }} 
            exit={{ opacity: 0 }}
            className="fixed inset-0 bg-black/80 backdrop-blur-md z-[100]" 
          />
        </Dialog.Overlay>
        <Dialog.Content asChild>
          <motion.div 
            initial={{ x: '100%' }} 
            animate={{ x: 0 }} 
            exit={{ x: '100%' }}
            transition={{ type: 'spring', damping: 30, stiffness: 250 }}
            className="fixed right-0 top-0 bottom-0 w-full max-w-3xl bg-surface/60 backdrop-blur-3xl border-l border-white/5 z-[101] shadow-[0_0_100px_rgba(0,0,0,0.5)] overflow-y-auto focus:outline-none"
          >
            <div className="p-10 space-y-12">
              <header className="flex items-start justify-between">
                <div className="space-y-5">
                  <div className="flex flex-wrap items-center gap-3">
                    <Dialog.Description asChild>
                      <span className="px-3 py-1 bg-white/[0.03] border border-white/10 rounded-full text-[10px] font-black text-accent uppercase tracking-[0.2em]">
                        {fund.category}
                      </span>
                    </Dialog.Description>
                    <span className={`px-3 py-1 rounded-full text-[10px] font-black uppercase tracking-[0.2em] shadow-lg border ${
                      fund.action === 'BUY' ? 'text-buy bg-buy/10 border-buy/20 glow-buy' : 
                      fund.action === 'EXIT' || fund.action === 'SELL' ? 'text-exit bg-exit/10 border-exit/20 glow-exit' : 'text-hold bg-hold/10 border-hold/20'
                    }`}>
                      {fund.action} Decision
                    </span>
                  </div>
                  <Dialog.Title asChild>
                    <h2 className="text-3xl font-black text-primary tracking-tighter leading-[1.1] max-w-md group hover:text-white transition-colors cursor-default">
                      {fund.simpleName || fund.schemeName}
                    </h2>
                  </Dialog.Title>
                </div>
                
                <div className="flex items-center gap-8">
                  <div className="text-right">
                    <p className="text-[10px] font-black uppercase tracking-[0.3em] text-muted mb-1 opacity-40">Win Rate</p>
                    <p className="text-xl font-black text-buy">{(fund.winRate * 100).toFixed(0)}%</p>
                  </div>
                  <div className="text-right">
                    <p className="text-[10px] font-black uppercase tracking-[0.3em] text-muted mb-1 opacity-40">Tail Loss</p>
                    <p className="text-xl font-black text-exit">{fund.cvar5?.toFixed(2)}%</p>
                  </div>
                  <div className="text-right">
                    <p className="text-[10px] font-black uppercase tracking-[0.3em] text-muted mb-1 opacity-40">Logic Confidence</p>
                    <p className="text-4xl font-black tabular-nums text-primary glow-accent">{fund.convictionScore}<span className="text-sm text-muted font-light ml-1">/100</span></p>
                  </div>
                  <Dialog.Close asChild>
                    <button className="p-3 bg-white/5 hover:bg-white/10 rounded-2xl transition-all text-muted hover:text-primary cursor-pointer active:scale-90 border border-white/5">
                      <X size={24} />
                    </button>
                  </Dialog.Close>
                </div>
              </header>

              {/* Advanced Quant Metadata */}
              <div className="flex gap-4 overflow-x-auto pb-2 scrollbar-none">
                <div className="flex-none px-4 py-2 rounded-2xl bg-white/[0.03] border border-white/5 space-y-1">
                  <p className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">Regime Climate</p>
                  <p className="text-xs font-black text-accent uppercase tracking-tighter">{fund.hmmState || 'STRESSED_NEUTRAL'}</p>
                </div>
                <div className="flex-none px-4 py-2 rounded-2xl bg-white/[0.03] border border-white/5 space-y-1">
                  <p className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">Mean Inversion Pulse</p>
                  <p className="text-xs font-black text-buy uppercase tracking-tighter">{fund.ouHalfLife > 0.01 ? `${fund.ouHalfLife.toFixed(1)} Days` : 'STABLE'}</p>
                </div>
                <div className="flex-none px-4 py-2 rounded-2xl bg-white/[0.03] border border-white/5 space-y-1">
                  <p className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">Fractal Force</p>
                  <p className="text-xs font-black text-secondary uppercase tracking-tighter">H = {fund.hurstExponent?.toFixed(2) || '0.50'}</p>
                </div>
                <div className="flex-none px-4 py-2 rounded-2xl bg-white/[0.03] border border-white/5 space-y-1">
                  <p className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">Vol Drag</p>
                  <p className="text-xs font-black text-exit uppercase tracking-tighter">{fund.volatilityTax?.toFixed(2) || '0.00'}% Annual</p>
                </div>
                <div className="flex-none px-4 py-2 rounded-2xl bg-white/[0.03] border border-white/5 space-y-1">
                  <p className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">Range Index</p>
                  <p className="text-xs font-black text-primary uppercase tracking-tighter">{Math.round((fund.navPercentile1yr || 0) * 100)}%</p>
                </div>
                <div className="flex-none px-4 py-2 rounded-2xl bg-white/[0.03] border border-white/5 space-y-1">
                  <p className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">Correction</p>
                  <p className="text-xs font-black text-exit uppercase tracking-tighter">{(Math.abs(fund.drawdownFromAth || 0) * 100).toFixed(1)}%</p>
                </div>
              </div>

              {/* Sticky financial strip */}
              <div className="grid grid-cols-3 gap-1 -mx-10 px-10 py-8 bg-black/20 border-y border-white/5 shadow-inner">
                {[
                  { label: 'Current Value', value: fund.currentValue, type: 'currency' },
                  { label: 'Personal XIRR', value: fund.xirr, type: 'text', color: parseFloat(fund.xirr) >= 0 ? 'text-buy' : 'text-exit' },
                  { label: 'System Return', value: fund.unrealizedGain, type: 'currency', color: fund.unrealizedGain >= 0 ? 'text-buy' : 'text-exit' }
                ].map((stat, i) => (
                  <div key={i} className="space-y-1.5 px-4 first:pl-0 border-r border-white/5 last:border-0 group cursor-default">
                    <p className="text-[9px] font-black uppercase tracking-[0.2em] text-muted opacity-50 group-hover:opacity-100 transition-opacity">{stat.label}</p>
                    {stat.type === 'currency' ? (
                      <CurrencyValue isPrivate={isPrivate} value={stat.value} className={`text-xl font-black tabular-nums tracking-tighter ${stat.color || 'text-primary'}`} />
                    ) : (
                      <p className={`text-xl font-black tabular-nums tracking-tighter ${stat.color}`}>{stat.value}</p>
                    )}
                  </div>
                ))}
              </div>

              {/* Quant Signals Strip */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <div className="p-4 bg-white/[0.02] border border-white/5 rounded-2xl">
                  <p className="text-[9px] font-black text-muted uppercase tracking-widest mb-1">Regime State</p>
                  <p className="text-xs font-black text-primary truncate">
                    {fund.hmmState?.replace('_', ' ') || 'NEUTRAL'}
                  </p>
                </div>
                <div className="p-4 bg-white/[0.02] border border-white/5 rounded-2xl">
                  <p className="text-[9px] font-black text-muted uppercase tracking-widest mb-1">Price Nature</p>
                  <p className="text-xs font-black text-primary truncate">
                    {fund.hurstRegime?.replace('_', ' ') || 'RANDOM WALK'}
                  </p>
                </div>
                <div className="p-4 bg-white/[0.02] border border-white/5 rounded-2xl">
                  <p className="text-[9px] font-black text-muted uppercase tracking-widest mb-1">Reversion</p>
                  <p className="text-xs font-black text-primary truncate">
                    {fund.ouValid ? `${fund.ouHalfLife.toFixed(0)} Days HL` : 'Weak Signal'}
                  </p>
                </div>
                <div className="p-4 bg-white/[0.02] border border-white/5 rounded-2xl">
                  <p className="text-[9px] font-black text-muted uppercase tracking-widest mb-1">Bear Prob</p>
                  <p className={`text-xs font-black ${fund.hmmBearProb > 0.6 ? 'text-exit' : 'text-buy'}`}>
                    {(fund.hmmBearProb * 100).toFixed(1)}%
                  </p>
                </div>
              </div>

              <div className="grid grid-cols-1 lg:grid-cols-2 gap-10">
                {/* Visual Engine Radar */}
                <section className="bg-white/[0.02] border border-white/5 p-6 rounded-[2.5rem] relative overflow-hidden group hover:border-white/10 transition-all shadow-2xl">
                  <div className="flex items-center justify-between mb-2 px-2">
                    <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.2em]">Quant Signature</h3>
                    <div className="w-2 h-2 rounded-full bg-accent animate-pulse shadow-[0_0_8px_rgba(129,140,248,0.8)]" />
                  </div>
                  <div className="h-64 w-full mt-4">
                    <ResponsiveContainer width="100%" height="100%">
                      <RadarChart cx="50%" cy="50%" outerRadius="80%" data={radarData}>
                        <PolarGrid stroke="rgba(255,255,255,0.05)" />
                        <PolarAngleAxis dataKey="subject" tick={{ fill: 'rgba(255,255,255,0.3)', fontSize: 10, fontWeight: 800 }} />
                        <PolarRadiusAxis angle={30} domain={[0, 100]} tick={false} axisLine={false} />
                        <Radar
                          name="Fund"
                          dataKey="A"
                          stroke="#818cf8"
                          fill="#818cf8"
                          fillOpacity={0.3}
                          animationDuration={1500}
                        />
                      </RadarChart>
                    </ResponsiveContainer>
                  </div>
                </section>

                {/* Score Breakdown Bars */}
                <section className="space-y-4">
                  <div className="flex items-center justify-between px-2">
                    <h3 className="text-muted text-[10px] font-black uppercase tracking-[0.2em] flex items-center gap-2">
                      <Zap size={12} className="text-warning fill-current" /> Engine Diagnostics
                    </h3>
                    {!hasRealScores && <span className="text-[9px] text-muted font-bold italic opacity-40 uppercase">Mode: Heuristic Estimate</span>}
                  </div>
                  
                  <motion.div variants={container} initial="hidden" animate="show" className="space-y-2">
                    {components.map((c) => (
                      <motion.div key={c.label} variants={item} className="bg-white/[0.03] border border-white/5 p-4 rounded-2xl flex items-center gap-6 group hover:bg-white/[0.06] transition-all cursor-default shadow-lg">
                        <div className="w-20">
                          <p className="text-[10px] font-black text-secondary uppercase tracking-widest">{c.label}</p>
                          <p className="text-[8px] font-bold opacity-40">WEIGHTED</p>
                        </div>
                        <div className="flex-1 h-1.5 bg-black/20 rounded-full overflow-hidden border border-white/5">
                          <motion.div 
                            initial={{ width: 0 }}
                            animate={{ width: `${c.score}%` }}
                            transition={{ duration: 1.2, delay: 0.5, type: "spring" }}
                            style={{ background: convictionColor(c.score) }}
                            className="h-full rounded-full shadow-[0_0_10px_rgba(0,0,0,0.5)]"
                          />
                        </div>
                        <div className="flex items-center gap-3">
                          <span className="text-xs font-black tabular-nums text-primary">{Math.round(c.score)}</span>
                          <Tooltip.Provider delayDuration={200}>
                            <Tooltip.Root>
                              <Tooltip.Trigger asChild>
                                <Info size={13} className="text-muted/40 cursor-help hover:text-accent transition-colors" />
                              </Tooltip.Trigger>
                              <Tooltip.Portal>
                                <Tooltip.Content className="bg-surface-overlay/90 backdrop-blur-xl border border-white/10 text-secondary text-[11px] font-bold rounded-xl px-4 py-3 max-w-[220px] leading-relaxed z-[200] shadow-2xl animate-in fade-in zoom-in-95">
                                  <p className="text-primary uppercase text-[9px] font-black mb-1">{c.full}</p>
                                  <p className="opacity-80 font-medium">{c.tooltip}</p>
                                  <Tooltip.Arrow className="fill-white/10" />
                                </Tooltip.Content>
                              </Tooltip.Portal>
                            </Tooltip.Root>
                          </Tooltip.Provider>
                        </div>
                      </motion.div>
                    ))}
                  </motion.div>
                </section>
              </div>

              {/* Valuation Visual */}
              <section className="bg-surface-elevated border border-white/5 p-8 rounded-[2.5rem] space-y-8 shadow-inner group hover:border-white/10 transition-all">
                <div className="flex items-center justify-between">
                  <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.2em]">Contextual Positioning</h3>
                  <span className="text-muted text-[9px] font-black uppercase tracking-widest opacity-40">1-Year History · Indexed to 100</span>
                </div>
                
                {/* Historical Performance Line Chart */}
                <div className="h-64 w-full bg-black/20 rounded-2xl overflow-hidden border border-white/5 p-4 shadow-inner">
                  {loading ? (
                    <div className="h-full w-full flex items-center justify-center">
                      <div className="w-8 h-8 border-4 border-accent border-t-transparent rounded-full animate-spin" />
                    </div>
                  ) : needsBackfill ? (
                    <div className="h-full flex flex-col items-center justify-center gap-3 p-8 text-center">
                      <div className="w-10 h-10 bg-amber-500/10 border border-amber-500/20 rounded-2xl flex items-center justify-center">
                        <Activity size={18} className="text-amber-400" />
                      </div>
                      <p className="text-amber-400 text-xs font-black uppercase tracking-widest">
                        Historical data not yet loaded
                      </p>
                      <p className="text-muted text-[11px] max-w-xs leading-relaxed">
                        This chart needs historical NAV data. Go to the{' '}
                        <span className="text-accent">Data tab</span> and click{' '}
                        <span className="text-accent">"Full History Refresh"</span> to load
                        3 years of NAV data for all your funds. This takes 2-5 minutes.
                      </p>
                    </div>
                  ) : normalizedHistory.length > 0 ? (
                    <ResponsiveLine
                      data={normalizedHistory}
                      margin={{ top: 20, right: 20, bottom: 20, left: 45 }}
                      xScale={{ type: 'point' }}
                      yScale={{ type: 'linear', min: 'auto', max: 'auto' }}
                      axisTop={null}
                      axisRight={null}
                      axisBottom={null}
                      axisLeft={{
                        tickSize: 0,
                        tickPadding: 10,
                        tickRotation: 0,
                        legend: 'Value',
                        legendOffset: -35,
                        legendPosition: 'middle'
                      }}
                      enableGridX={false}
                      enableGridY={true}
                      colors={d => (d as any).seriesColor as string}
                      lineWidth={2}
                      enablePoints={false}
                      useMesh={true}
                      theme={{
                        axis: { 
                          ticks: { text: { fill: "rgba(255,255,255,0.3)", fontSize: 10 } },
                          legend: { text: { fill: "rgba(255,255,255,0.2)", fontSize: 8, fontWeight: 900, textTransform: 'uppercase' } }
                        },
                        grid: { line: { stroke: "rgba(255,255,255,0.05)" } },
                        tooltip: { container: { background: "#181825", color: "#cdd6f4", fontSize: 12, borderRadius: 12 } }
                      }}
                      tooltip={({ point }) => (
                        <div className="bg-surface-overlay/95 backdrop-blur-2xl border border-white/10 p-4 rounded-2xl shadow-2xl space-y-2">
                          <p className="text-[10px] font-black text-muted uppercase tracking-widest">{point.data.x.toString()}</p>
                          <div className="flex items-center gap-3">
                            <div className="w-2 h-2 rounded-full" style={{ background: point.seriesColor }} />
                            <p className="text-sm font-black text-primary">{point.seriesId}: {point.data.yFormatted}</p>
                          </div>
                          <p className={`text-[9px] font-bold uppercase ${parseFloat(point.data.yFormatted as string) >= 100 ? 'text-buy' : 'text-exit'}`}>
                            {parseFloat(point.data.yFormatted as string) >= 100 ? '+' : ''}{(parseFloat(point.data.yFormatted as string) - 100).toFixed(1)}% vs Start
                          </p>
                        </div>
                      )}
                    />
                  ) : (
                    <div className="h-full w-full flex items-center justify-center text-muted text-[10px] uppercase font-black opacity-30 px-10 text-center">
                      Historical data loading or insufficient data points
                    </div>
                  )}
                </div>

                <div className="space-y-10 px-2">
                  <div className="relative h-1.5 w-full bg-black/40 rounded-full">
                    <div className="absolute top-0 left-0 h-4 w-px bg-white/20 -translate-y-1.5" />
                    <div className="absolute top-0 right-0 h-4 w-px bg-white/20 -translate-y-1.5" />
                    <span className="absolute -top-7 left-0 text-[8px] font-black text-muted uppercase tracking-[0.2em] opacity-30">1yr Floor</span>
                    <span className="absolute -top-7 right-0 text-[8px] font-black text-muted uppercase tracking-[0.2em] opacity-30">ATH Peak</span>
                    
                    <motion.div 
                      initial={{ left: '50%' }}
                      animate={{ left: `${Math.min(98, Math.max(2, (fund.navPercentile1yr || 0) * 100))}%` }}
                      transition={{ type: "spring", stiffness: 100, damping: 20 }}
                      className="absolute top-1/2 -translate-y-1/2 -translate-x-1/2 w-4 h-4 bg-accent rounded-full border-4 border-[#0d0d1a] shadow-[0_0_20px_rgba(129,140,248,0.8)] z-10 cursor-help"
                    />
                    <div className="absolute inset-0 bg-gradient-to-r from-buy/10 via-transparent to-exit/10 rounded-full" />
                  </div>
                  
                  <div className="grid grid-cols-2 gap-12 pt-4">
                    <div className="space-y-1">
                      <p className="text-muted text-[9px] font-black uppercase tracking-[0.2em] opacity-50">Range Index</p>
                      <p className="text-xl font-black text-primary tracking-tighter">
                        {Math.round((fund.navPercentile1yr || 0) * 100)}<span className="text-xs text-muted font-light ml-1">%ile</span>
                      </p>
                    </div>
                    <div className="space-y-1">
                      <p className="text-muted text-[9px] font-black uppercase tracking-[0.2em] opacity-50">Correction Depth</p>
                      <p className="text-xl font-black text-exit tracking-tighter tabular-nums">
                        {(Math.abs(fund.drawdownFromAth || 0) * 100).toFixed(1)}%
                      </p>
                    </div>
                  </div>
                </div>
              </section>

              {/* Analysis Verdict */}
              <section className="bg-accent/5 border border-accent/10 p-8 rounded-[2.5rem] relative overflow-hidden group hover:border-accent/20 transition-all">
                <div className="absolute top-0 right-0 p-8 opacity-5 group-hover:scale-110 transition-transform duration-700">
                  <Activity size={120} className="text-accent" />
                </div>
                <div className="flex items-center gap-4 mb-6">
                  <div className="p-3 bg-accent/10 rounded-2xl text-accent shadow-inner border border-accent/10">
                    <ShieldCheck size={20} />
                  </div>
                  <h3 className="text-[11px] font-black uppercase tracking-[0.3em] text-accent">System Analysis Verdict</h3>
                </div>
                <div className="space-y-4 relative z-10">
                  {(fund.justifications && fund.justifications.length > 0
                    ? fund.justifications
                    : [buildPlainEnglishReason(fund)]
                  ).map((j: string, i: number) => (
                    <motion.div 
                      key={i} 
                      initial={{ opacity: 0, y: 10 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: i * 0.1 }}
                      className="flex gap-4 text-[13px] text-secondary leading-relaxed bg-black/20 p-5 rounded-2xl border border-white/5 group/j hover:bg-black/40 hover:border-white/10 transition-all shadow-lg"
                    >
                      <span className="text-accent font-black mt-0.5 tabular-nums opacity-40">0{i+1}</span>
                      <p className="font-medium tracking-tight leading-snug">{j}</p>
                    </motion.div>
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
