import { useState, useMemo } from 'react';
import { 
  ResponsiveContainer, XAxis, YAxis, Tooltip as RechartsTooltip, 
  BarChart, Bar, CartesianGrid, Treemap, ScatterChart, Scatter, ZAxis, Cell
} from "recharts";
import { HeartPulse, Shield, Activity, ListChecks, Database } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import MetricWithTooltip from '../ui/MetricWithTooltip';
import LearnTooltip from '../ui/LearnTooltip';

const ACTION_COLORS: Record<string, { bg: string; text: string; label: string }> = {
  BUY:     { bg: '#34d399', text: '#fff', label: 'Buy' },
  SELL:    { bg: '#f87171', text: '#fff', label: 'Sell' },
  EXIT:    { bg: '#ef4444', text: '#fff', label: 'Exit' },
  WATCH:   { bg: '#fbbf24', text: '#000', label: 'Watch' },
  HOLD:    { bg: '#6366f1', text: '#fff', label: 'Hold' },
  HARVEST: { bg: '#a78bfa', text: '#fff', label: 'Harvest' },
  DEFAULT: { bg: '#475569', text: '#fff', label: '' },
};

const CustomTreemapContent = (props: any) => {
  const { x, y, width, height, name, action, shortName } = props;
  const cfg = ACTION_COLORS[action as string] || ACTION_COLORS.DEFAULT;
  const showLabel = width > 72 && height > 28;
  const showBadge = width > 52 && height > 42;

  return (
    <motion.g
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ type: "spring", damping: 20, stiffness: 300 }}
    >
      <rect
        x={x + 1} y={y + 1}
        width={Math.max(0, width - 2)}
        height={Math.max(0, height - 2)}
        rx={8}
        style={{ fill: cfg.bg, opacity: 0.85, backdropFilter: 'blur(10px)' }}
        className="transition-all duration-300 hover:opacity-100"
      />
      {showLabel && (
        <text
          x={x + 8} y={y + 18}
          fill={cfg.text}
          fontSize={10}
          fontWeight="700"
          className="pointer-events-none"
          style={{ textShadow: '0 2px 4px rgba(0,0,0,0.3)' }}
        >
          {shortName || (name || '').substring(0, 22)}
        </text>
      )}
      {showBadge && (
        <>
          <rect
            x={x + width - 42} y={y + height - 22}
            width={36} height={16}
            rx={8}
            fill="rgba(0,0,0,0.3)"
            className="backdrop-blur-md border border-white/10"
          />
          <text
            x={x + width - 24} y={y + height - 10}
            textAnchor="middle"
            fill={cfg.text}
            fontSize={8}
            fontWeight="800"
            className="pointer-events-none"
          >
            {action}
          </text>
        </>
      )}
    </motion.g>
  );
};

export default function PortfolioView({ 
  portfolioData, 
  isPrivate 
}: { 
  portfolioData: any; 
  isPrivate: boolean;
}) {
  // 1. FILTER OUT ZERO VALUE FUNDS
  const activeBreakdown = useMemo(() => 
    (portfolioData.schemeBreakdown || []).filter((s: any) => (s.currentValue || 0) > 0),
    [portfolioData.schemeBreakdown]
  );
  
  const totalValue = portfolioData.currentValueAmount || 0;
  
  const weightedCVaR = useMemo(() => activeBreakdown.reduce((acc: number, s: any) => {
    const weight = (s.currentValue || 0) / (totalValue || 1);
    return acc + (s.cvar5 || 0) * weight;
  }, 0), [activeBreakdown, totalValue]);

  const totalUnrealizedLTCG = activeBreakdown.reduce((a: number, s: any) => a + (s.ltcgUnrealizedGain || 0), 0);
  const totalUnrealizedGainPos = activeBreakdown.reduce((a: number, s: any) => a + Math.max(0, (s.unrealizedGain || 0)), 0);
  const taxEfficiency = totalUnrealizedGainPos > 0 ? totalUnrealizedLTCG / totalUnrealizedGainPos : 0;

  // FLAT Treemap Data (Already filtered)
  const treemapData = useMemo(() => activeBreakdown.map((s: any) => ({
    name: s.schemeName,
    size: s.currentValue || 0,
    action: s.action || 'HOLD',
    shortName: s.schemeName.length > 22
      ? s.schemeName.substring(0, 22) + '…'
      : s.schemeName,
  })), [activeBreakdown]);

  // Scatter Data: Risk (MaxDrawdown) vs Conviction
  const scatterData = useMemo(() => activeBreakdown.map((s: any) => ({
    name: s.schemeName,
    x: s.convictionScore,
    y: Math.abs(s.maxDrawdown || 0),
    z: s.currentValue,
    action: s.action
  })), [activeBreakdown]);

  const [hoveredFund, setHoveredFund] = useState<any>(null);
  const [hoverPos, setHoverPos] = useState({ x: 0, y: 0 });

  const handleMouseMove = (e: React.MouseEvent) => {
    setHoverPos({ x: e.clientX, y: e.clientY });
  };

  // Bucket Allocation Data
  const bucketData = useMemo(() => {
    const buckets: Record<string, number> = {};
    activeBreakdown.forEach((s: any) => {
      const b = s.bucket || 'OTHERS';
      buckets[b] = (buckets[b] || 0) + (s.currentValue || 0);
    });
    return Object.entries(buckets)
      .map(([name, value]) => ({ name: name.replace(/_/g, ' '), value }))
      .sort((a, b) => b.value - a.value);
  }, [activeBreakdown]);

  const xirrData = useMemo(() => activeBreakdown
    .map((s: any) => ({
      name: s.schemeName.substring(0, 20),
      Personal: parseFloat(s.xirr || '0'),
      Benchmark: s.category?.includes("MIDCAP") ? 18 : 14
    }))
    .sort((a: any, b: any) => b.Personal - a.Personal), [activeBreakdown]);

  const formatCurrency = (val: number) => {
    if (isPrivate) return '₹••••';
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(val);
  };

  return (
    <div className="space-y-10 pb-32">
      {/* Liquid Glass Header Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: 'Portfolio Value', value: formatCurrency(totalValue), tooltip: 'Market value of all active holdings.', accent: 'bg-accent/40' },
          { label: 'System XIRR', value: portfolioData.overallXirr, tooltip: 'Internal Rate of Return accounting for cash flows.', accent: parseFloat(portfolioData.overallXirr) >= 0 ? 'bg-buy/40' : 'bg-exit/40', valueClass: parseFloat(portfolioData.overallXirr) >= 0 ? "text-buy glow-buy" : "text-exit glow-exit" },
          { label: 'Unrealised P&L', value: formatCurrency(portfolioData.totalUnrealizedGain), tooltip: 'Current paper profit/loss.', accent: portfolioData.totalUnrealizedGain >= 0 ? 'bg-buy/40' : 'bg-exit/40', valueClass: portfolioData.totalUnrealizedGain >= 0 ? "text-buy glow-buy" : "text-exit glow-exit" },
          { label: 'Tax Exposure (STCG)', value: formatCurrency(portfolioData.totalSTCG), tooltip: 'Estimated tax if sold today.', accent: 'bg-warning/40', valueClass: 'text-warning' }
        ].map((m, i) => (
          <motion.div 
            key={m.label}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.1, type: "spring", stiffness: 200 }}
            className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-2xl relative overflow-hidden group hover:border-white/10 transition-all shadow-lg"
          >
            <div className={`absolute left-0 top-4 bottom-4 w-1 rounded-r-full ${m.accent} group-hover:scale-y-110 transition-transform`} />
            <div className="pl-3">
              <MetricWithTooltip label={m.label} value={m.value} valueClass={m.valueClass} tooltip={m.tooltip} />
            </div>
          </motion.div>
        ))}
      </div>

      {/* Meta Stats Strip */}
      <div className="flex flex-wrap gap-4 px-2">
        {[
          { icon: <Database size={12}/>, label: 'Folios', value: portfolioData.totalFolios },
          { icon: <Activity size={12}/>, label: 'Trades', value: portfolioData.totalTransactions },
          { icon: <Shield size={12}/>, label: 'Tax Lots', value: portfolioData.openTaxLots },
          { icon: <ListChecks size={12}/>, label: 'Active Funds', value: activeBreakdown.length }
        ].map((s) => (
          <div key={s.label} className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-white/[0.03] border border-white/5 text-[10px] font-bold uppercase tracking-widest text-muted hover:bg-white/[0.06] transition-colors cursor-default">
            <span className="text-accent">{s.icon}</span>
            <span>{s.label}:</span>
            <span className="text-primary tabular-nums">{s.value}</span>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {/* Treemap Section */}
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl relative overflow-hidden shadow-2xl">
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-primary text-sm font-black tracking-tight uppercase tracking-[0.15em]">Allocation Heatmap</h3>
            <span className="text-muted text-[10px] uppercase tracking-widest font-black opacity-50 underline decoration-accent/30">Value Weighted</span>
          </div>
          
          <div 
            className="h-[400px] w-full mt-6 bg-black/20 rounded-2xl overflow-hidden border border-white/5 shadow-inner"
            onMouseMove={handleMouseMove}
          >
            <ResponsiveContainer width="100%" height="100%">
              <Treemap
                data={treemapData}
                dataKey="size"
                aspectRatio={4 / 3}
                content={<CustomTreemapContent />}
                onMouseEnter={(data) => setHoveredFund(data)}
                onMouseLeave={() => setHoveredFund(null)}
              />
            </ResponsiveContainer>
          </div>

          <div className="flex gap-4 flex-wrap mt-6">
            {Object.entries(ACTION_COLORS).filter(([k]) => k !== 'DEFAULT').map(([key, item]) => (
              <div key={key} className="flex items-center gap-2 px-2.5 py-1.5 rounded-xl hover:bg-white/5 transition-all border border-transparent hover:border-white/5 active:scale-95">
                <div className="w-3 h-3 rounded-full shadow-[0_0_12px_rgba(0,0,0,0.5)] border border-white/10" style={{background: item.bg}}/>
                <span className="text-[10px] font-black text-secondary uppercase tracking-widest">{item.label}</span>
              </div>
            ))}
          </div>
        </section>

        {/* Scatter Chart: Risk vs Conviction */}
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-3xl relative overflow-hidden shadow-2xl">
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-primary text-sm font-black tracking-tight uppercase tracking-[0.15em]">Risk-Conviction Matrix</h3>
            <div className="flex gap-2">
              <span className="text-[9px] text-buy font-black uppercase bg-buy/10 px-2 py-0.5 rounded-full border border-buy/20 shadow-sm">Alpha Zone ↗</span>
            </div>
          </div>
          <p className="text-[10px] text-muted mb-6 uppercase tracking-widest font-bold opacity-60">Visualizing structural health vs market volatility.</p>
          
          <div className="h-[400px] w-full bg-black/20 rounded-2xl overflow-hidden border border-white/5 p-4 shadow-inner">
            <ResponsiveContainer width="100%" height="100%">
              <ScatterChart margin={{ top: 20, right: 20, bottom: 20, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.03)" vertical={false}/>
                <XAxis 
                  type="number" 
                  dataKey="x" 
                  name="Conviction" 
                  unit="" 
                  domain={[0, 100]}
                  axisLine={false}
                  tickLine={false}
                  tick={{fill: 'rgba(255,255,255,0.3)', fontSize: 10, fontWeight: 700}}
                  label={{ value: 'Conviction Score', position: 'bottom', fill: 'rgba(255,255,255,0.2)', fontSize: 9, offset: 0, fontWeight: 800 }}
                />
                <YAxis 
                  type="number" 
                  dataKey="y" 
                  name="Max Drawdown" 
                  unit="%" 
                  reversed
                  axisLine={false}
                  tickLine={false}
                  tick={{fill: 'rgba(255,255,255,0.3)', fontSize: 10, fontWeight: 700}}
                  label={{ value: 'Max Drawdown', angle: -90, position: 'insideLeft', fill: 'rgba(255,255,255,0.2)', fontSize: 9, fontWeight: 800 }}
                />
                <ZAxis type="number" dataKey="z" range={[50, 400]} />
                <RechartsTooltip 
                  cursor={{ strokeDasharray: '3 3', stroke: 'rgba(255,255,255,0.1)' }}
                  content={({ active, payload }) => {
                    if (active && payload && payload.length) {
                      const data = payload[0].payload;
                      return (
                        <div className="bg-surface-overlay/90 backdrop-blur-3xl border border-white/10 p-4 rounded-2xl shadow-[0_20px_50px_rgba(0,0,0,0.5)]">
                          <p className="text-[10px] font-black text-primary uppercase tracking-widest mb-3 border-b border-white/5 pb-2">{data.name}</p>
                          <div className="space-y-2">
                            <div className="flex justify-between gap-10"><span className="text-[9px] text-muted uppercase font-black">Conviction</span><span className="text-[10px] font-black text-buy tabular-nums">{data.x}</span></div>
                            <div className="flex justify-between gap-10"><span className="text-[9px] text-muted uppercase font-black">Max DD</span><span className="text-[10px] font-black text-exit tabular-nums">-{data.y}%</span></div>
                            <div className="flex justify-between gap-10"><span className="text-[9px] text-muted uppercase font-black">Weight</span><span className="text-[10px] font-black text-primary tabular-nums">₹{(data.z/1000).toFixed(1)}k</span></div>
                          </div>
                        </div>
                      );
                    }
                    return null;
                  }}
                />
                <Scatter data={scatterData}>
                  {scatterData.map((entry: any, index: number) => (
                    <Cell 
                      key={`cell-${index}`} 
                      fill={ACTION_COLORS[entry.action as string]?.bg || '#818cf8'} 
                      stroke="rgba(255,255,255,0.2)"
                      strokeWidth={1}
                      className="hover:scale-125 transition-transform origin-center cursor-pointer"
                    />
                  ))}
                </Scatter>
              </ScatterChart>
            </ResponsiveContainer>
          </div>
        </section>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Vital Signs Pulse */}
        <section className="xl:col-span-2">
          <h3 className="text-muted text-[10px] font-black uppercase tracking-[0.2em] mb-6 flex items-center gap-2">
            <HeartPulse size={12} className="text-accent animate-pulse" /> Vital Signs Pulse
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* CVaR */}
            <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-3xl space-y-4 shadow-lg group hover:border-accent/20 transition-all">
              <MetricWithTooltip 
                label={<LearnTooltip term="CVaR">Portfolio CVaR</LearnTooltip>}
                value={`${weightedCVaR.toFixed(2)}%`}
                tooltip="Expected loss on worst 5% days. Closer to zero is safer."
              />
              <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden flex flex-row-reverse border border-white/5 shadow-inner">
                <motion.div 
                  initial={{ width: 0 }}
                  animate={{ width: `${Math.min(100, Math.abs(weightedCVaR) * 10)}%` }}
                  className="h-full bg-gradient-to-l from-accent to-indigo-400 transition-all duration-1000 shadow-[0_0_12px_rgba(129,140,248,0.4)]" 
                />
              </div>
              <div className="flex justify-between items-center text-[9px] font-black text-muted uppercase tracking-widest">
                {weightedCVaR < -3.5 ? <span className="text-exit animate-pulse">⚠ Critical Risk</span> : weightedCVaR < -2.5 ? <span className="text-warning">Elevated Risk</span> : <span className="text-buy">Health Optimal</span>}
                <span className="opacity-40">Systemic Guard</span>
              </div>
            </div>

            {/* Tax Efficiency */}
            <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-3xl space-y-4 shadow-lg group hover:border-buy/20 transition-all">
              <MetricWithTooltip 
                label="Tax Shield Resilience" 
                value={`${(taxEfficiency * 100).toFixed(1)}%`}
                tooltip="% of paper profits qualifying for LTCG rates."
              />
              <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden border border-white/5 shadow-inner">
                <motion.div 
                  initial={{ width: 0 }}
                  animate={{ width: `${taxEfficiency * 100}%` }}
                  className="h-full bg-gradient-to-r from-buy to-emerald-400 transition-all duration-1000 shadow-[0_0_12px_rgba(52,211,153,0.4)]" 
                />
              </div>
              <div className="flex justify-between items-center text-[9px] font-black text-muted uppercase tracking-widest">
                {taxEfficiency < 0.4 ? <span className="text-exit">Weak Protection</span> : <span className="text-buy">Optimized Shield</span>}
                <span className="opacity-40">Fiscal Shield</span>
              </div>
            </div>

            {/* Regime Climate (HMM) */}
            {(() => {
              const bull = activeBreakdown.filter((s: any) => s.hmmState === 'CALM_BULL').length;
              const bear = activeBreakdown.filter((s: any) => s.hmmState === 'VOLATILE_BEAR').length;
              const total = activeBreakdown.length || 1;

              return (
                <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-3xl space-y-4 shadow-lg group hover:border-indigo-400/20 transition-all">
                  <MetricWithTooltip 
                    label="Regime Climate" 
                    value={`${bull} Bull / ${bear} Bear`}
                    tooltip="Market regime classification via HMM."
                  />
                  <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden flex border border-white/5 shadow-inner">
                    <div style={{ width: `${(bull/total)*100}%` }} className="h-full bg-buy shadow-[0_0_8px_rgba(74,222,128,0.4)]" />
                    <div style={{ width: `${((total-bull-bear)/total)*100}%` }} className="h-full bg-white/10" />
                    <div style={{ width: `${(bear/total)*100}%` }} className="h-full bg-exit shadow-[0_0_8px_rgba(248,113,113,0.4)]" />
                  </div>
                  <div className="flex justify-between text-[9px] font-black text-muted uppercase tracking-widest">
                    <span>Engine Sentiment</span>
                    <span className="opacity-40">HMM Filter</span>
                  </div>
                </div>
              );
            })()}

            {/* Half-life Clock (OU) */}
            {(() => {
              const validFunds = activeBreakdown.filter((s: any) => s.ouValid);
              const avgHalfLife = validFunds.length > 0 
                ? validFunds.reduce((acc: number, s: any) => acc + s.ouHalfLife, 0) / validFunds.length 
                : 0;

              return (
                <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-3xl space-y-4 shadow-lg group hover:border-orange-400/20 transition-all">
                  <MetricWithTooltip 
                    label="Mean Reversion Pulse" 
                    value={avgHalfLife > 0 ? `${avgHalfLife.toFixed(0)}d Half-life` : 'N/A'}
                    tooltip="Average time for discount to halve (OU Process)."
                  />
                  <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden border border-white/5 shadow-inner">
                    <motion.div 
                      initial={{ width: 0 }}
                      animate={{ width: `${Math.min(100, avgHalfLife)}%` }}
                      className="h-full bg-gradient-to-r from-orange-400 to-amber-300 opacity-80 shadow-[0_0_8px_rgba(251,146,60,0.4)]" 
                    />
                  </div>
                  <div className="flex justify-between text-[9px] font-black text-muted uppercase tracking-widest">
                    <span>Elasticity Speed</span>
                    <span className="opacity-40">OU Calibration</span>
                  </div>
                </div>
              );
            })()}
          </div>
        </section>

        {/* Bucket Allocation Chart */}
        <section className="flex flex-col">
          <h3 className="text-muted text-[10px] font-black uppercase tracking-[0.2em] mb-6 flex items-center gap-2">
            <Shield size={12} className="text-buy" /> Diversification Audit
          </h3>
          <div className="flex-1 bg-surface/40 backdrop-blur-xl border border-white/5 p-8 rounded-[2rem] shadow-2xl relative overflow-hidden">
            <div className="h-full w-full min-h-[280px]">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={bucketData} layout="vertical" margin={{ left: -20, right: 20 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.02)" horizontal={false} />
                  <XAxis type="number" hide />
                  <YAxis 
                    dataKey="name" 
                    type="category" 
                    axisLine={false} 
                    tickLine={false} 
                    width={100}
                    tick={{ fill: 'rgba(241,245,249,0.5)', fontSize: 9, fontWeight: 800 }}
                  />
                  <RechartsTooltip 
                    cursor={{ fill: 'rgba(255,255,255,0.03)' }}
                    contentStyle={{ backgroundColor: 'rgba(15,15,24,0.9)', backdropFilter: 'blur(20px)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '12px' }}
                    labelStyle={{ color: '#fff', fontSize: '10px', fontWeight: '900', textTransform: 'uppercase', marginBottom: '4px' }}
                    itemStyle={{ color: '#818cf8', fontSize: '11px', fontWeight: '700' }}
                    formatter={(value: any) => [`₹${(Number(value || 0)/1000).toFixed(1)}k`, 'Value']}
                  />
                  <Bar dataKey="value" fill="#818cf8" radius={[0, 4, 4, 0]} barSize={12}>
                    {bucketData.map((_entry: any, index: number) => (
                      <Cell key={`cell-${index}`} fillOpacity={0.6} fill={`hsl(${230 + index * 20}, 70%, 60%)`} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
            <div className="mt-4 pt-4 border-t border-white/5 flex justify-between items-center">
              <span className="text-[9px] font-black text-muted uppercase tracking-widest">Active Buckets</span>
              <span className="text-[10px] font-black text-primary uppercase">{bucketData.length} Structural Groups</span>
            </div>
          </div>
        </section>
      </div>

      {/* Performance Delta section remains similar but with upgraded styling */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl">
        <div className="flex items-center justify-between mb-10">
          <div className="space-y-1">
            <h3 className="text-primary text-lg font-black tracking-tight uppercase tracking-[0.1em]">Performance Delta</h3>
            <p className="text-xs text-muted font-bold uppercase tracking-widest opacity-60">Personal XIRR vs System Alpha Benchmarks</p>
          </div>
          <button className="px-6 py-2.5 bg-white/5 rounded-full border border-white/10 text-[10px] font-black uppercase tracking-widest text-secondary hover:text-white hover:bg-white/10 hover:border-white/20 transition-all active:scale-95 shadow-lg">Detailed Ledger</button>
        </div>
        <div className="h-96 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={xirrData} layout="vertical" margin={{ left: 20, right: 40 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.02)" horizontal={true} vertical={false} />
              <XAxis type="number" axisLine={false} tickLine={false} tick={{ fill: 'rgba(241,245,249,0.3)', fontSize: 10, fontWeight: 700 }} unit="%" />
              <YAxis 
                dataKey="name" 
                type="category" 
                axisLine={false} 
                tickLine={false} 
                width={150}
                tick={{ fill: 'rgba(241,245,249,0.6)', fontSize: 11, fontWeight: 600 }}
              />
              {!isPrivate && (
                <RechartsTooltip 
                  cursor={{ fill: 'rgba(255,255,255,0.02)' }}
                  contentStyle={{ backgroundColor: 'rgba(15,15,24,0.9)', backdropFilter: 'blur(20px)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '16px', boxShadow: '0 20px 50px rgba(0,0,0,0.5)' }}
                />
              )}
              <Bar dataKey="Personal" fill="#818cf8" radius={[0, 4, 4, 0]} barSize={12}>
                {xirrData.map((entry: any, index: number) => (
                  <Cell key={`cell-${index}`} fill={entry.Personal >= 0 ? '#4ade80' : '#f87171'} fillOpacity={0.8} />
                ))}
              </Bar>
              <Bar dataKey="Benchmark" fill="rgba(255,255,255,0.05)" radius={[0, 4, 4, 0]} barSize={12} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </section>

      {/* Hover Tooltip for Treemap */}
      <AnimatePresence>
        {hoveredFund && (
          <motion.div 
            initial={{ opacity: 0, scale: 0.95, y: 10 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.95, y: 10 }}
            className="fixed z-[100] bg-surface-overlay/95 backdrop-blur-3xl border border-white/10 p-5 rounded-[1.5rem] shadow-[0_30px_60px_rgba(0,0,0,0.6)] pointer-events-none"
            style={{ left: hoverPos.x + 25, top: hoverPos.y + 25 }}
          >
            <p className="text-[10px] text-muted uppercase tracking-[0.2em] mb-1.5 font-black opacity-60">{hoveredFund.name}</p>
            <p className="text-xl font-black text-primary mb-3 tabular-nums tracking-tighter">
              {isPrivate ? '••••' : `₹${(hoveredFund.size / 1000).toFixed(1)}k`}
            </p>
            <div className="flex items-center gap-2.5 px-3 py-2 rounded-xl bg-black/40 border border-white/5">
              <div className="w-2.5 h-2.5 rounded-full animate-pulse-dot" style={{ background: ACTION_COLORS[hoveredFund.action as string]?.bg }} />
              <p className="text-[10px] font-black uppercase tracking-[0.15em] text-secondary">{hoveredFund.action} Signal</p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
