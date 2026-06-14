import { useEffect, useState, useMemo } from 'react';
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
import ConvictionScoreWaterfall from '../ui/ConvictionScoreWaterfall';

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
    const oldestBenchPrice = (bData.length > 0) ? bData[0].closingPrice : 1;

    const series = [
      {
        id: "Fund",
        color: "#cba6f7",
        data: fData.map((d: any) => ({
          x: new Date(d.navDate),
          y: parseFloat(((d.nav / oldestFundNav) * 100).toFixed(2))
        }))
      }
    ];

    if (bData.length >= 2) {
      series.push({
        id: "Benchmark",
        color: "#a6e3a1",
        data: bData.map((d: any) => ({
          x: new Date(d.date),
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



  return (
    <Dialog.Root open={isOpen} onOpenChange={onClose}>
      <Dialog.Portal>
        <Dialog.Overlay asChild>
          <div 
            className="fixed inset-0 bg-black/80 backdrop-blur-md z-[100]" 
          />
        </Dialog.Overlay>
        <Dialog.Content asChild>
          <div 
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
                      fund.action === 'BUY' ? 'text-buy bg-buy/10 border-buy/20' : 
                      fund.action === 'EXIT' || fund.action === 'SELL' ? 'text-exit bg-exit/10 border-exit/20' : 'text-hold bg-hold/10 border-hold/20'
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
                    <p className="text-xl font-black text-buy">{fund.winRate > 0 ? `${fund.winRate.toFixed(0)}%` : '—'}</p>
                  </div>
                  <div className="text-right">
                    <p className="text-[10px] font-black uppercase tracking-[0.3em] text-muted mb-1 opacity-40">Tail Loss</p>
                    <p className="text-xl font-black text-exit">{fund.cvar5?.toFixed(2)}%</p>
                  </div>
                  <div className="text-right">
                    <p className="text-[10px] font-black uppercase tracking-[0.3em] text-muted mb-1 opacity-40">Logic Confidence</p>
                    <p className="text-4xl font-black tabular-nums text-primary" style={{ textShadow: '0 0 20px rgba(203,166,247,0.4)' }}>{fund.convictionScore}<span className="text-sm text-muted font-light ml-1">/100</span></p>
                    {fund.convictionHistory && fund.convictionHistory.length > 1 && (
                      <div className="mt-1 h-4 w-16 ml-auto">
                        <svg viewBox="0 0 100 100" className="w-full h-full overflow-visible" preserveAspectRatio="none">
                          <path
                            d={(() => {
                              const h = fund.convictionHistory.slice(-30);
                              const mn = Math.min(...h), mx = Math.max(...h);
                              const rng = mx - mn || 1;
                              const pts = h.map((v: number, i: number) => ({ x: (i / (h.length - 1)) * 100, y: 100 - ((v - mn) / rng) * 100 }));
                              return `M ${pts.map((p: {x:number,y:number}) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' L ')}`;
                            })()}
                            fill="none"
                            stroke={fund.convictionScore >= 60 ? '#a6e3a1' : fund.convictionScore >= 40 ? '#b4befe' : '#f38ba8'}
                            strokeWidth="12"
                            strokeLinecap="round"
                          />
                        </svg>
                      </div>
                    )}
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
                {/* Score Breakdown Bars */}
                <section className="space-y-6">
                  <div className="flex items-center justify-between px-2">
                    <h3 className="text-muted text-[10px] font-black uppercase tracking-[0.2em] flex items-center gap-2">
                      <Zap size={12} className="text-warning fill-current" /> Engine Diagnostics
                    </h3>
                    {!hasRealScores && <span className="text-[9px] text-muted font-bold italic opacity-40 uppercase">Mode: Heuristic Estimate</span>}
                  </div>

                  <div className="bg-white/[0.02] border border-white/5 p-6 rounded-[2.5rem] shadow-2xl">
                    <p className="text-[9px] font-black uppercase tracking-[0.2em] text-muted mb-4 opacity-60">Weighted Contribution Bridge</p>
                    <ConvictionScoreWaterfall 
                      yieldScore={fund.yieldScore ?? 50}
                      riskScore={fund.riskScore ?? 50}
                      valueScore={fund.valueScore ?? 50}
                      painScore={fund.painScore ?? 50}
                      regimeScore={fund.regimeScore ?? 50}
                      frictionScore={fund.frictionScore ?? 50}
                      expenseScore={fund.expenseScore ?? 50}
                      finalScore={fund.convictionScore ?? 50}
                    />
                  </div>
                  
                  <div className="space-y-2">
                    {components.map((c) => (
                      <div key={c.label} className="bg-white/[0.03] border border-white/5 p-4 rounded-2xl flex items-center gap-6 group hover:bg-white/[0.06] -all cursor-default shadow-lg">
                        <div className="w-20">
                          <p className="text-[10px] font-black text-secondary uppercase tracking-widest">{c.label}</p>
                          <p className="text-[8px] font-bold opacity-40">RAW SCORE</p>
                        </div>
                        <div className="flex-1 h-1.5 bg-black/20 rounded-full overflow-hidden border border-white/5">
                          <div 
                            style={{ width: `${c.score}%`, background: convictionColor(c.score) }}
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
                      </div>
                    ))}
                  </div>
                </section>
              </div>

              {/* Fama-French Attribution Section */}
              <section className="bg-surface-elevated border border-white/5 p-8 rounded-[2.5rem] space-y-8 shadow-inner group hover:border-white/10 transition-all">
                <div className="flex items-center justify-between">
                  <div className="space-y-1">
                    <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.2em]">Fama-French Attribution</h3>
                    <p className="text-[9px] text-muted font-bold uppercase tracking-widest opacity-40">Style & Factor Topography (OLS Regression)</p>
                  </div>
                  <div className="px-3 py-1 bg-accent/10 border border-accent/20 rounded-full">
                    <span className="text-[9px] font-black text-accent uppercase tracking-widest">Confidence: {((fund.rSquared || 0) * 100).toFixed(0)}% (R²)</span>
                  </div>
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
                  {/* Left Column: Manager Selection Edge (Alpha) & R² */}
                  <div className="lg:col-span-4 flex flex-col justify-between space-y-6">
                    {/* Alpha Card */}
                    <div className="p-6 bg-black/20 rounded-3xl border border-white/5 relative overflow-hidden flex flex-col items-center text-center justify-center min-h-[180px]">
                      {/* Semi-circular gradient background glow */}
                      <div className={`absolute -bottom-12 w-32 h-32 rounded-full blur-[40px] opacity-20 ${
                        (fund.alpha || 0) > 0.02 ? 'bg-buy' : (fund.alpha || 0) > 0 ? 'bg-hold' : 'bg-exit'
                      }`} />
                      
                      <span className="text-[9px] font-black uppercase tracking-widest text-muted mb-2">Manager Selection Edge</span>
                      
                      <div className="relative flex items-center justify-center my-2">
                        {/* Circular progress background */}
                        <svg className="w-24 h-24 transform -rotate-90">
                          <circle cx="48" cy="48" r="40" stroke="rgba(255,255,255,0.03)" strokeWidth="8" fill="transparent" />
                          <circle cx="48" cy="48" r="40" 
                            stroke={(fund.alpha || 0) > 0.02 ? '#a6e3a1' : (fund.alpha || 0) > 0 ? '#89b4fa' : '#f38ba8'} 
                            strokeWidth="8" 
                            fill="transparent" 
                            strokeDasharray={2 * Math.PI * 40}
                            strokeDashoffset={2 * Math.PI * 40 * (1 - Math.min(Math.max(((fund.alpha || 0) * 100 + 10) / 20, 0), 1))}
                            strokeLinecap="round"
                            className="transition-all duration-1000 ease-out"
                          />
                        </svg>
                        <div className="absolute inset-0 flex flex-col items-center justify-center">
                          <span className={`text-xl font-black tabular-nums tracking-tighter ${(fund.alpha || 0) > 0 ? 'text-buy glow-buy' : 'text-exit glow-exit'}`}>
                            {(fund.alpha || 0) > 0 ? '+' : ''}{((fund.alpha || 0) * 100).toFixed(2)}%
                          </span>
                          <span className="text-[8px] text-muted font-bold uppercase tracking-widest">Alpha p.a.</span>
                        </div>
                      </div>

                      <div className="mt-3">
                        <span className={`chip text-[8px] px-3 py-0.5 ${
                          (fund.alpha || 0) > 0.02 ? 'chip-buy' : (fund.alpha || 0) > 0 ? 'chip-hold' : 'chip-exit'
                        }`}>
                          {(fund.alpha || 0) > 0.02 ? 'Strong Selection Edge' : (fund.alpha || 0) > 0 ? 'Moderate Selection Edge' : 'No Selection Edge'}
                        </span>
                      </div>
                    </div>

                    {/* R² Confidence bar */}
                    <div className="p-5 bg-black/20 rounded-3xl border border-white/5 space-y-3">
                      <div className="flex justify-between items-center text-[9px] font-black uppercase tracking-widest">
                        <span className="text-muted">Regression R² (Fit)</span>
                        <span className="text-secondary">{((fund.rSquared || 0) * 100).toFixed(0)}% Fit</span>
                      </div>
                      <div className="w-full bg-white/5 rounded-full h-1.5 overflow-hidden">
                        <div 
                          className="bg-accent h-full rounded-full transition-all duration-1000 ease-out"
                          style={{ width: `${Math.min(Math.max((fund.rSquared || 0) * 100, 0), 100)}%` }}
                        />
                      </div>
                      <p className="text-[8px] text-muted/60 leading-tight">
                        {(fund.rSquared || 0) > 0.75 
                          ? "High style model accuracy. Factors strongly explain return variance."
                          : "Moderate style accuracy. Portion of returns driven by other factors."}
                      </p>
                    </div>
                  </div>

                  {/* Right Column: Visual Sliders/Gauges */}
                  <div className="lg:col-span-8 space-y-6 flex flex-col justify-between">
                    {/* Market Beta (0 to 2.0) */}
                    <div className="p-5 bg-black/20 rounded-3xl border border-white/5 space-y-3 relative overflow-hidden">
                      <div className="flex justify-between items-center">
                        <div className="space-y-0.5">
                          <span className="text-[9px] font-black uppercase tracking-widest text-muted">Market Correlation (Beta)</span>
                          <p className="text-[11px] font-bold text-primary">
                            {(fund.betaMkt || 0) < 0.8 ? 'Defensive & Lower Risk' : (fund.betaMkt || 0) <= 1.2 ? 'Market-Aligned Volatility' : 'Leveraged / Aggressive'}
                          </p>
                        </div>
                        <span className="text-lg font-black text-primary tabular-nums">{(fund.betaMkt || 0).toFixed(2)}</span>
                      </div>
                      
                      {/* Track and slider bar */}
                      <div className="relative py-2">
                        {/* Scale markers */}
                        <div className="flex justify-between text-[7px] font-black text-muted/40 uppercase tracking-widest px-1 mb-1">
                          <span>Conservative (0.0)</span>
                          <span>Market (1.0)</span>
                          <span>Aggressive (2.0)</span>
                        </div>
                        <div className="w-full bg-white/5 rounded-full h-2 relative overflow-hidden">
                          {/* Color overlay zones */}
                          <div className="absolute left-0 top-0 bottom-0 bg-info/20" style={{ width: '40%' }} /> {/* 0.0 - 0.8 */}
                          <div className="absolute left-[40%] top-0 bottom-0 bg-buy/20" style={{ width: '20%' }} /> {/* 0.8 - 1.2 */}
                          <div className="absolute left-[60%] top-0 bottom-0 bg-warning/20" style={{ width: '40%' }} /> {/* 1.2 - 2.0 */}
                        </div>
                        {/* Pointer indicator */}
                        <div 
                          className="absolute w-4 h-4 bg-white border-2 border-accent rounded-full -top-1.5 -ml-2 shadow-lg transition-all duration-1000 ease-out flex items-center justify-center"
                          style={{ left: `${Math.min(Math.max(((fund.betaMkt || 0) / 2.0) * 100, 0), 100)}%` }}
                        >
                          <div className="w-1.5 h-1.5 bg-accent rounded-full" />
                        </div>
                      </div>
                    </div>

                    {/* Size Tilt (SMB: -1 to +1) */}
                    <div className="p-5 bg-black/20 rounded-3xl border border-white/5 space-y-3 relative overflow-hidden">
                      <div className="flex justify-between items-center">
                        <div className="space-y-0.5">
                          <span className="text-[9px] font-black uppercase tracking-widest text-muted">Market Capitalization Bias (SMB)</span>
                          <p className="text-[11px] font-bold text-primary">
                            {(fund.betaSmb || 0) < -0.15 ? 'Tilt towards Large-Cap Stocks' : (fund.betaSmb || 0) <= 0.15 ? 'Blended / Multi-Cap Stance' : 'Tilt towards Small & Mid-Caps'}
                          </p>
                        </div>
                        <span className={`text-lg font-black tabular-nums ${(fund.betaSmb || 0) > 0 ? 'text-accent' : 'text-hold'}`}>
                          {(fund.betaSmb || 0) > 0 ? '+' : ''}{(fund.betaSmb || 0).toFixed(2)}
                        </span>
                      </div>
                      
                      {/* Track and slider bar */}
                      <div className="relative py-2">
                        {/* Scale markers */}
                        <div className="flex justify-between text-[7px] font-black text-muted/40 uppercase tracking-widest px-1 mb-1">
                          <span>Large-Cap Focus</span>
                          <span>Neutral (0.0)</span>
                          <span>Small-Cap Focus</span>
                        </div>
                        <div className="w-full bg-white/5 rounded-full h-2 relative">
                          {/* Centered zero line */}
                          <div className="absolute left-1/2 top-0 bottom-0 w-0.5 bg-white/20" />
                          {/* Color fill from center to indicator */}
                          <div 
                            className={`absolute top-0 bottom-0 rounded-full ${(fund.betaSmb || 0) >= 0 ? 'bg-accent/40' : 'bg-hold/40'}`} 
                            style={{ 
                              left: `${(fund.betaSmb || 0) >= 0 ? 50 : Math.max(50 + (fund.betaSmb || 0) * 50, 0)}%`,
                              right: `${(fund.betaSmb || 0) >= 0 ? Math.max(50 - (fund.betaSmb || 0) * 50, 0) : 50}%`
                            }}
                          />
                        </div>
                        {/* Pointer indicator */}
                        <div 
                          className={`absolute w-4 h-4 bg-white border-2 rounded-full -top-1.5 -ml-2 shadow-lg transition-all duration-1000 ease-out flex items-center justify-center ${
                            (fund.betaSmb || 0) >= 0 ? 'border-accent' : 'border-hold'
                          }`}
                          style={{ left: `${Math.min(Math.max(50 + (fund.betaSmb || 0) * 50, 0), 100)}%` }}
                        >
                          <div className={`w-1.5 h-1.5 rounded-full ${(fund.betaSmb || 0) >= 0 ? 'bg-accent' : 'bg-hold'}`} />
                        </div>
                      </div>
                    </div>

                    {/* Value vs Growth Tilt (HML: -1 to +1) */}
                    <div className="p-5 bg-black/20 rounded-3xl border border-white/5 space-y-3 relative overflow-hidden">
                      <div className="flex justify-between items-center">
                        <div className="space-y-0.5">
                          <span className="text-[9px] font-black uppercase tracking-widest text-muted">Value vs Growth Style Bias (HML)</span>
                          <p className="text-[11px] font-bold text-primary">
                            {(fund.betaHml || 0) < -0.15 ? 'High-growth Stock Selection' : (fund.betaHml || 0) <= 0.15 ? 'Balanced/Core Equity Stance' : 'Value & High-Yield Orientation'}
                          </p>
                        </div>
                        <span className={`text-lg font-black tabular-nums ${(fund.betaHml || 0) > 0 ? 'text-warning' : 'text-accent-bright'}`}>
                          {(fund.betaHml || 0) > 0 ? '+' : ''}{(fund.betaHml || 0).toFixed(2)}
                        </span>
                      </div>
                      
                      {/* Track and slider bar */}
                      <div className="relative py-2">
                        {/* Scale markers */}
                        <div className="flex justify-between text-[7px] font-black text-muted/40 uppercase tracking-widest px-1 mb-1">
                          <span>Growth (High PE)</span>
                          <span>Blended (0.0)</span>
                          <span>Value (Low PE)</span>
                        </div>
                        <div className="w-full bg-white/5 rounded-full h-2 relative">
                          {/* Centered zero line */}
                          <div className="absolute left-1/2 top-0 bottom-0 w-0.5 bg-white/20" />
                          {/* Color fill from center to indicator */}
                          <div 
                            className={`absolute top-0 bottom-0 rounded-full ${(fund.betaHml || 0) >= 0 ? 'bg-warning/40' : 'bg-accent-bright/40'}`} 
                            style={{ 
                              left: `${(fund.betaHml || 0) >= 0 ? 50 : Math.max(50 + (fund.betaHml || 0) * 50, 0)}%`,
                              right: `${(fund.betaHml || 0) >= 0 ? Math.max(50 - (fund.betaHml || 0) * 50, 0) : 50}%`
                            }}
                          />
                        </div>
                        {/* Pointer indicator */}
                        <div 
                          className={`absolute w-4 h-4 bg-white border-2 rounded-full -top-1.5 -ml-2 shadow-lg transition-all duration-1000 ease-out flex items-center justify-center ${
                            (fund.betaHml || 0) >= 0 ? 'border-warning' : 'border-accent-bright'
                          }`}
                          style={{ left: `${Math.min(Math.max(50 + (fund.betaHml || 0) * 50, 0), 100)}%` }}
                        >
                          <div className={`w-1.5 h-1.5 rounded-full ${(fund.betaHml || 0) >= 0 ? 'bg-warning' : 'bg-accent-bright'}`} />
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Plain English summary */}
                <div className="p-5 bg-white/[0.02] border border-dashed border-white/10 rounded-3xl">
                  <div className="flex items-start gap-3">
                    <div className="p-2 bg-accent/10 border border-accent/20 rounded-xl mt-0.5 flex-shrink-0">
                      <Zap size={14} className="text-accent" />
                    </div>
                    <div className="space-y-1">
                      <h4 className="text-[10px] font-black uppercase tracking-wider text-secondary">Attribution Intelligence Summary</h4>
                      <p className="text-[11px] text-secondary font-medium leading-relaxed italic opacity-80">
                        {Math.abs(fund.betaSmb || 0) > 0.15 
                          ? (fund.betaSmb || 0) > 0.15 
                            ? `This fund heavily exploits the Small-Cap factor (SMB = ${(fund.betaSmb || 0).toFixed(2)}), making it highly sensitive to mid and small-cap market rallies. ` 
                            : `This fund maintains a deep Large-Cap core (SMB = ${(fund.betaSmb || 0).toFixed(2)}), offering relative stability during high volatility. `
                          : `This fund maintains a flexible capitalisation blend (SMB = ${(fund.betaSmb || 0).toFixed(2)}). `}
                        {Math.abs(fund.betaHml || 0) > 0.15 
                          ? (fund.betaHml || 0) > 0.15 
                            ? `It exhibits a strong Value tilt (HML = ${(fund.betaHml || 0).toFixed(2)}), investing in undervalued, cash-flow rich businesses. ` 
                            : `It maintains a heavy Growth style bias (HML = ${(fund.betaHml || 0).toFixed(2)}), buying high-multiple compounders. ` 
                          : `It pursues a blended style valuation approach (HML = ${(fund.betaHml || 0).toFixed(2)}). `}
                        {(fund.alpha || 0) > 0.02 
                          ? `The manager has generated an impressive annualized alpha of ${((fund.alpha || 0) * 100).toFixed(2)}%, indicating strong stock selection execution.` 
                          : (fund.alpha || 0) < -0.01 
                          ? `The manager has underperformed standard style benchmarks (Alpha = ${((fund.alpha || 0) * 100).toFixed(2)}%), meaning returns are fully market-beta driven.` 
                          : `Returns are largely aligned with market beta, with nominal selection alpha.`}
                      </p>
                    </div>
                  </div>
                </div>
              </section>

              {/* Valuation Visual */}
              <section className="bg-surface-elevated border border-white/5 p-8 rounded-[2.5rem] space-y-8 shadow-inner group hover:border-white/10 transition-all">
                <div className="flex items-center justify-between">
                  <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.2em]">Contextual Positioning</h3>
                  <span className="text-muted text-[9px] font-black uppercase tracking-widest opacity-40">History normalized to 100</span>
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
                    </div>
                  ) : normalizedHistory.length > 0 ? (
                    <ResponsiveLine
                      data={normalizedHistory}
                      margin={{ top: 20, right: 20, bottom: 40, left: 45 }}
                      xScale={{ type: 'time', format: 'native' }}
                      xFormat="time:%Y-%m-%d"
                      yScale={{ type: 'linear', min: 'auto', max: 'auto' }}
                      axisTop={null}
                      axisRight={null}
                      axisBottom={{
                        format: '%b %Y',
                        tickValues: 4,
                        tickSize: 0,
                        tickPadding: 10,
                        tickRotation: 0,
                      }}
                      axisLeft={{
                        tickSize: 0,
                        tickPadding: 10,
                        tickRotation: 0,
                        legend: 'Rel Value',
                        legendOffset: -35,
                        legendPosition: 'middle'
                      }}
                      enableGridX={false}
                      enableGridY={true}
                      colors={d => d.color}
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
                    
                    <div 
                      style={{left: `${Math.min(98, Math.max(2, (fund.navPercentile1yr || 0) * 100))}%` }}
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
                    <div 
                      key={i} 
                      className="flex gap-4 text-[13px] text-secondary leading-relaxed bg-black/20 p-5 rounded-2xl border border-white/5 group/j hover:bg-black/40 hover:border-white/10 -all shadow-lg"
                    >
                      <span className="text-accent font-black mt-0.5 tabular-nums opacity-40">0{i+1}</span>
                      <p className="font-medium tracking-tight leading-snug">{j}</p>
                    </div>
                  ))}
                </div>
              </section>
            </div>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
