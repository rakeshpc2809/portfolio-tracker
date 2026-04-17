import { useMemo } from 'react';
import { 
  ResponsiveContainer, XAxis, YAxis, Tooltip as RechartsTooltip, 
  CartesianGrid, ScatterChart, Scatter, ZAxis, Cell, ReferenceLine
} from "recharts";
import { ResponsivePie } from '@nivo/pie';
import { ResponsiveBar } from '@nivo/bar';
import { ResponsiveTreeMap } from '@nivo/treemap';
import { ResponsiveHeatMap } from '@nivo/heatmap';
import { Shield, ChartPie, Info, Link } from 'lucide-react';
import { motion } from 'framer-motion';
import MetricWithTooltip from '../ui/MetricWithTooltip';
import LearnTooltip from '../ui/LearnTooltip';
import { formatCurrency } from '../../utils/formatters';
import { useCorrelationMatrix } from '../../hooks/useCorrelation';

const ACTION_COLORS: Record<string, { bg: string; text: string; label: string }> = {
  BUY:     { bg: '#a6e3a1', text: '#fff', label: 'Buy' },
  SELL:    { bg: '#f38ba8', text: '#fff', label: 'Sell' },
  EXIT:    { bg: '#f38ba8', text: '#fff', label: 'Exit' },
  WATCH:   { bg: '#f9e2af', text: '#000', label: 'Watch' },
  HOLD:    { bg: '#89b4fa', text: '#fff', label: 'Hold' },
  HARVEST: { bg: '#b4befe', text: '#fff', label: 'Harvest' },
  DEFAULT: { bg: '#45475a', text: '#fff', label: '' },
};

export default function PortfolioView({ 
  portfolioData, 
  isPrivate,
  onFundClick,
  pan
}: { 
  portfolioData: any; 
  isPrivate: boolean;
  onFundClick: (name: string) => void;
  pan: string;
}) {
  const mask = (val: number | string) => isPrivate ? "••••" : String(val);
  const { data: corrData } = useCorrelationMatrix(pan);

  const activeBreakdown = useMemo(() => 
    (portfolioData.schemeBreakdown ?? []).filter((s: any) => (s.currentValue || 0) > 0),
    [portfolioData.schemeBreakdown]
  );

  // FLAT Treemap Data (Already filtered)
  const treemapData = useMemo(() => activeBreakdown.map((s: any) => ({
    name: s.simpleName || s.schemeName,
    size: s.currentValue || 0,
    action: s.action || 'HOLD',
    shortName: (s.simpleName || s.schemeName).length > 22
      ? (s.simpleName || s.schemeName).substring(0, 22) + '…'
      : (s.simpleName || s.schemeName),
  })), [activeBreakdown]);

  // Scatter Data: Risk (MaxDrawdown) vs Conviction
  const scatterData = useMemo(() => activeBreakdown.map((s: any) => ({
    name: s.simpleName || s.schemeName,
    x: s.convictionScore,
    y: Math.abs(s.maxDrawdown || 0),
    z: s.currentValue,
    action: s.action
  })), [activeBreakdown]);

  // Bucket Allocation Data
  const bucketData = useMemo(() => {
    const buckets: Record<string, number> = {};
    activeBreakdown.forEach((s: any) => {
      const b = s.bucket || 'OTHERS';
      buckets[b] = (buckets[b] || 0) + (s.currentValue || 0);
    });
    return Object.entries(buckets)
      .map(([name, value]) => ({ id: name, label: name.replace(/_/g, ' '), value }))
      .sort((a, b) => b.value - a.value);
  }, [activeBreakdown]);

  // AMC Allocation Data
  const amcData = useMemo(() => {
    const amcs: Record<string, number> = {};
    activeBreakdown.forEach((s: any) => {
      const amc = s.amc || 'OTHER AMC';
      amcs[amc] = (amcs[amc] || 0) + (s.currentValue || 0);
    });
    return Object.entries(amcs)
      .map(([name, value]) => ({ 
        id: name, 
        label: name, 
        value,
        color: `hsl(${Math.random() * 360}, 70%, 50%)`
      }))
      .sort((a, b) => b.value - a.value);
  }, [activeBreakdown]);

  // Transform matrix for Nivo Heatmap
  const nivoCorrData = useMemo(() => {
    if (!corrData || !corrData.labels || !corrData.matrix || corrData.matrix.length === 0) return [];
    if (corrData.labels.length !== corrData.matrix.length) return []; // Size mismatch safety

    return corrData.labels.map((label: string, i: number) => ({
      id: label,
      data: corrData.labels.map((otherLabel: string, j: number) => ({
        x: otherLabel,
        y: corrData.matrix[i] ? corrData.matrix[i][j] : 0
      }))
    }));
  }, [corrData]);

  return (
    <div className="space-y-12 pb-32">
      {/* ── Header ── */}
      <header className="px-2">
        <h2 className="text-muted text-[10px] font-black uppercase tracking-[0.3em] mb-1.5 opacity-60">Asset Intelligence</h2>
        <p className="text-2xl font-black text-primary tracking-tighter">Portfolio Topography</p>
      </header>

      {/* Allocation Donuts Row */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl h-[450px] flex flex-col group hover:border-accent/30 transition-all">
          <div className="flex items-center gap-3 mb-6">
            <ChartPie size={16} className="text-accent" />
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">AMC Allocation</h3>
          </div>
          <div className="flex-1 min-h-0">
            <ResponsivePie
              data={amcData}
              margin={{ top: 20, right: 60, bottom: 40, left: 60 }}
              innerRadius={0.65}
              padAngle={2}
              cornerRadius={12}
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
          </div>
        </section>

        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl h-[450px] flex flex-col group hover:border-buy/30 transition-all">
          <div className="flex items-center gap-3 mb-6">
            <Shield size={16} className="text-buy" />
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Category Diversification</h3>
          </div>
          <div className="flex-1 min-h-0">
            <ResponsivePie
              data={bucketData.map(b => ({ id: b.id, label: b.label, value: b.value }))}
              margin={{ top: 20, right: 60, bottom: 40, left: 60 }}
              innerRadius={0.65}
              padAngle={2}
              cornerRadius={12}
              activeOuterRadiusOffset={8}
              borderWidth={1}
              borderColor={{ from: 'color', modifiers: [['darker', 0.2]] }}
              colors={{ scheme: 'set3' }}
              enableArcLinkLabels={false}
              arcLabelsSkipAngle={10}
              arcLabelsTextColor="#ffffff"
              theme={{
                tooltip: { container: { background: '#181825', border: '1px solid rgba(255,255,255,0.1)', color: '#cdd6f4', fontSize: 11, borderRadius: 12, boxShadow: '0 10px 15px -3px rgba(0,0,0,0.5)' } },
                labels: { text: { fontSize: 10, fontWeight: 700 } }
              }}
              valueFormat={(value) => mask(formatCurrency(value))}
            />
          </div>
        </section>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {/* Treemap Section */}
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] relative overflow-hidden shadow-2xl group hover:border-white/10 transition-all">
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Allocation Heatmap</h3>
            <span className="text-muted text-[10px] uppercase tracking-widest font-black opacity-50 underline decoration-accent/30">Value Weighted</span>
          </div>
          
          <div 
            className="h-[400px] w-full mt-6 bg-black/20 rounded-3xl overflow-hidden border border-white/5 shadow-inner"
          >
            <ResponsiveTreeMap
              data={{
                name: 'portfolio',
                children: treemapData
              }}
              identity="name"
              value="size"
              valueFormat=".02s"
              margin={{ top: 10, right: 10, bottom: 10, left: 10 }}
              labelSkipSize={12}
              label={node => node.id.toString()}
              labelTextColor="#ffffff"
              parentLabelPosition="top"
              parentLabelSize={14}
              parentLabelTextColor="#ffffff"
              borderColor={{ from: 'color', modifiers: [['darker', 0.1]] }}
              colors={(node: any) => ACTION_COLORS[node.data.action as string]?.bg || '#45475a'}
              theme={{
                tooltip: { container: { background: '#181825', border: '1px solid rgba(255,255,255,0.1)', color: '#cdd6f4', fontSize: 11, borderRadius: 12 } }
              }}
            />
          </div>

          <div className="flex gap-4 flex-wrap mt-8">
            {Object.entries(ACTION_COLORS).filter(([k]) => k !== 'DEFAULT').map(([key, item]) => (
              <div key={key} className="flex items-center gap-2 px-3 py-1.5 rounded-xl hover:bg-white/5 transition-all border border-transparent hover:border-white/5 active:scale-95">
                <div className="w-2 h-2 rounded-full" style={{ background: item.bg }} />
                <span className="text-[9px] font-black uppercase tracking-widest text-muted">{item.label}</span>
              </div>
            ))}
          </div>
        </section>

        {/* Alpha Generation Section */}
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl group hover:border-buy/20 transition-all">
          <div className="flex items-center justify-between mb-2 px-2">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Alpha Generation</h3>
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2">
                <div className="w-3 h-1 rounded-full bg-buy" />
                <span className="text-[8px] font-black text-muted uppercase tracking-widest">Winning</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-3 h-1 rounded-full bg-white/10" />
                <span className="text-[8px] font-black text-muted uppercase tracking-widest">Benchmark</span>
              </div>
            </div>
          </div>
          
        <div className="h-80 w-full mt-4">
          <ResponsiveBar
            data={activeBreakdown
              .filter((s: any) => (s.currentValue || 0) > 5000)
              .map((s: any) => {
                const personal = parseFloat(s.xirr || '0');
                const bench = parseFloat(s.benchmarkXirr || '14.8');
                const name = s.simpleName || s.schemeName;
                return {
                  fund: name.length > 12 ? name.substring(0, 12) + '…' : name,
                  "Personal XIRR": parseFloat(personal.toFixed(1)),
                  "Benchmark": parseFloat(bench.toFixed(1)),
                  isWinning: personal >= bench
                };
              })
              .sort((a: any, b: any) => a["Personal XIRR"] - b["Personal XIRR"])
            }
            keys={["Personal XIRR", "Benchmark"]}
            indexBy="fund"
            margin={{ top: 20, right: 20, bottom: 80, left: 40 }}
            padding={0.4}
            layout="vertical"
            groupMode="grouped"
            colors={({ id, data }: any) => {
              if (id === "Benchmark") return "rgba(255,255,255,0.1)";
              return data.isWinning ? "#a6e3a1" : "#f38ba8";
            }}
            borderRadius={4}
            axisLeft={{
              tickSize: 0,
              tickPadding: 10,
              tickRotation: 0,
              format: (v) => `${v}%`,
            }}
            axisBottom={{
              tickSize: 0,
              tickPadding: 10,
              tickRotation: -45,
            }}
            enableGridX={false}
            enableGridY={true}
            labelSkipWidth={12}
            labelSkipHeight={12}
            labelTextColor="#ffffff"
            theme={{
              axis: {
                ticks: { text: { fill: "#6c7086", fontSize: 10, fontWeight: 700 } },
              },
              grid: { line: { stroke: "rgba(255,255,255,0.05)", strokeWidth: 1 } },
              tooltip: { container: { background: "#181825", color: "#cdd6f4", fontSize: 12, borderRadius: 12 } }
            }}
            tooltip={({ id, value, color }) => (
              <div className="bg-surface-overlay/95 backdrop-blur-2xl border border-white/10 p-3 rounded-xl shadow-2xl">
                <span className="text-[10px] font-black uppercase tracking-widest block mb-1" style={{ color }}>{id}</span>
                <span className="text-sm font-black text-primary">{value}%</span>
              </div>
            )}
          />
        </div>
      </section>
      </div>

      {/* Correlation Matrix Section */}
      {nivoCorrData.length > 0 && (
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl space-y-10">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-secondary/10 rounded-2xl text-secondary border border-secondary/20 shadow-lg">
              <Link size={20} />
            </div>
            <div>
              <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">HRP Correlation Matrix</h3>
              <p className="text-sm font-bold text-secondary">Diversification intelligence from the Hierarchical Risk Parity engine</p>
            </div>
          </div>

          <div className="h-[500px] w-full bg-black/20 rounded-3xl border border-white/5 p-6 shadow-inner relative overflow-hidden">
            <ResponsiveHeatMap
              data={nivoCorrData}
              margin={{ top: 100, right: 60, bottom: 60, left: 100 }}
              valueFormat=">-.2f"
              axisTop={{
                tickSize: 5,
                tickPadding: 5,
                tickRotation: -45,
                legend: '',
                legendOffset: 46
              }}
              axisLeft={{
                tickSize: 5,
                tickPadding: 5,
                tickRotation: 0,
                legend: '',
                legendOffset: -46
              }}
              colors={{
                type: 'sequential',
                scheme: 'purples',
                minValue: -1,
                maxValue: 1,
              }}
              emptyColor="#555555"
              theme={{
                axis: {
                  ticks: { text: { fill: "#6c7086", fontSize: 10, fontWeight: 700 } }
                },
                tooltip: { container: { background: "#181825", color: "#cdd6f4", fontSize: 12, borderRadius: 12 } }
              }}
              annotations={[]}
            />
            
            {/* Heatmap Legend Overlay */}
            <div className="absolute bottom-10 right-10 bg-surface-overlay/80 backdrop-blur-xl p-4 rounded-2xl border border-white/10 space-y-3 shadow-2xl">
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded bg-[#f2f0f7]" />
                <span className="text-[10px] font-bold text-muted uppercase tracking-widest">High Correlation</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded bg-[#6a51a3]" />
                <span className="text-[10px] font-bold text-muted uppercase tracking-widest">Low Correlation</span>
              </div>
              <p className="text-[9px] text-accent font-black uppercase tracking-[0.1em] pt-2 border-t border-white/5 italic">
                Target: GENUINE DIVERSIFICATION
              </p>
            </div>
          </div>
        </section>
      )}

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Vital Signs Pulse */}
        <section className="xl:col-span-2 bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl space-y-10">
          <div className="flex items-center justify-between">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Execution Efficiency</h3>
            <span className="text-muted text-[9px] font-black uppercase tracking-widest opacity-40">Z-Score Normalised</span>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {/* Regime Climate (HMM) */}
            {(() => {
              const bull = activeBreakdown.filter((s: any) => s.hmmState === 'CALM_BULL').length;
              const bear = activeBreakdown.filter((s: any) => s.hmmState === 'VOLATILE_BEAR').length;
              const total = activeBreakdown.length || 1;
              const neutral = total - bull - bear;

              return (
                <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-3xl space-y-4 shadow-lg group hover:border-indigo-400/20 transition-all">
                  <MetricWithTooltip 
                    label="Regime Climate" 
                    value={`${bull} Bull / ${bear} Bear`}
                    tooltip="Market regime classification via HMM."
                  />
                  <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden flex border border-white/5 shadow-inner">
                    <div style={{ width: `${(bull/total)*100}%` }} className="h-full bg-buy shadow-[0_0_8px_rgba(166,227,161,0.4)]" />
                    <div style={{ width: `${(neutral/total)*100}%` }} className="h-full bg-accent/20" />
                    <div style={{ width: `${(bear/total)*100}%` }} className="h-full bg-exit shadow-[0_0_8px_rgba(243,139,168,0.4)]" />
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
              const validFunds = activeBreakdown.filter((s: any) => (s.ouHalfLife > 0.01));
              const avgHalfLife = validFunds.length > 0 
                ? validFunds.reduce((acc: number, s: any) => acc + s.ouHalfLife, 0) / validFunds.length 
                : 0;

              return (
                <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-3xl space-y-4 shadow-lg group hover:border-orange-400/20 transition-all">
                  <MetricWithTooltip 
                    label="Mean Reversion Pulse" 
                    value={avgHalfLife > 0.01 ? `${avgHalfLife.toFixed(1)}d Half-life` : 'STABLE'}
                    tooltip="Average time for discount to halve (OU Process)."
                  />
                  <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden border border-white/5 shadow-inner">
                    <motion.div 
                      initial={{ width: 0 }}
                      animate={{ width: `${Math.min(100, avgHalfLife * 2)}%` }}
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

            {/* Alpha Edge */}
            {(() => {
              const winning = activeBreakdown.filter((s: any) => parseFloat(s.xirr) > s.benchmarkXirr).length;
              const total = activeBreakdown.length || 1;
              const pct = (winning / total) * 100;

              return (
                <div className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-3xl space-y-4 shadow-lg group hover:border-buy/20 transition-all">
                  <MetricWithTooltip 
                    label="Alpha Consistency" 
                    value={`${winning}/${total} Assets`}
                    tooltip="Percentage of funds outperforming their benchmark."
                  />
                  <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden border border-white/5 shadow-inner">
                    <motion.div 
                      initial={{ width: 0 }}
                      animate={{ width: `${pct}%` }}
                      className="h-full bg-buy shadow-[0_0_8px_rgba(166,227,161,0.4)]" 
                    />
                  </div>
                  <div className="flex justify-between text-[9px] font-black text-muted uppercase tracking-widest">
                    <span>Performance Lead</span>
                    <span className="opacity-40">Active Alpha</span>
                  </div>
                </div>
              );
            })()}
          </div>

          <div className="overflow-x-auto pt-4">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-white/5">
                  <th className="pb-4 text-[9px] font-black text-muted uppercase tracking-widest pl-4">Asset Identification</th>
                  <th className="pb-4 text-[9px] font-black text-muted uppercase tracking-widest text-center">Action</th>
                  <th className="pb-4 text-[9px] font-black text-muted uppercase tracking-widest text-center">Portfolio %</th>
                  <th className="pb-4 text-[9px] font-black text-muted uppercase tracking-widest text-right pr-4">Alpha Delta</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-white/5">
                {activeBreakdown.map((s: any) => {
                  const alpha = parseFloat(s.xirr || '0') - (s.benchmarkXirr || 14.8);
                  return (
                    <tr key={s.schemeName} className="group hover:bg-white/[0.02] transition-colors">
                      <td className="py-5 pl-4">
                        <p className="text-xs font-black text-primary group-hover:text-white transition-colors tracking-tight">{s.simpleName || s.schemeName}</p>
                        <p className="text-[9px] text-muted font-bold uppercase tracking-widest mt-0.5">{s.category}</p>
                      </td>
                      <td className="py-5 text-center">
                        <span className={`px-2 py-0.5 rounded-lg text-[9px] font-black uppercase tracking-widest border ${
                          s.action === 'BUY' ? 'text-buy bg-buy/10 border-buy/20' : 
                          s.action === 'EXIT' || s.action === 'SELL' ? 'text-exit bg-exit/10 border-exit/20' : 'text-hint bg-hint/10 border-white/5'
                        }`}>
                          {s.action || 'HOLD'}
                        </span>
                      </td>
                      <td className="py-5 text-center tabular-nums text-xs font-black text-secondary">{mask((s.allocationPercentage || 0).toFixed(1) + '%')}</td>
                      <td className={`py-5 text-right pr-4 tabular-nums text-xs font-black ${alpha >= 0 ? 'text-buy' : 'text-exit'}`}>
                        {mask((alpha > 0 ? '+' : '') + alpha.toFixed(1) + '%')}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>

        {/* Risk-Conviction Matrix */}
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] relative overflow-hidden shadow-2xl group hover:border-accent/20 transition-all">
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Risk-Conviction Matrix</h3>
            <LearnTooltip term="CVaR">
              <Info size={14} className="text-muted/40 cursor-help hover:text-accent transition-colors" />
            </LearnTooltip>
          </div>
          
          <div className="h-80 w-full mt-6">
            <ResponsiveContainer width="100%" height="100%">
              <ScatterChart margin={{ top: 20, right: 20, bottom: 20, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.02)" vertical={false} />
                <XAxis 
                  type="number" 
                  dataKey="x" 
                  name="Conviction" 
                  domain={[0, 100]} 
                  axisLine={false} 
                  tickLine={false} 
                  tick={{ fill: '#6c7086', fontSize: 10, fontWeight: 700 }}
                  label={{ value: 'Conviction score →', position: 'insideBottom', offset: -5, fill: '#6c7086', fontSize: 9, fontWeight: 900 }}
                />
                <YAxis 
                  type="number" 
                  dataKey="y" 
                  name="Max Drawdown" 
                  unit="%" 
                  axisLine={false} 
                  tickLine={false} 
                  tick={{ fill: '#6c7086', fontSize: 10, fontWeight: 700 }}
                  label={{ value: '↑ Max drawdown %', angle: -90, position: 'insideLeft', fill: '#6c7086', fontSize: 9, fontWeight: 900 }}
                />
                <ZAxis type="number" dataKey="z" range={[50, 400]} name="Value" />
                <RechartsTooltip 
                  cursor={{ strokeDasharray: '3 3', stroke: 'rgba(255,255,255,0.1)' }}
                  content={({ active, payload }) => {
                    if (!active || !payload?.length) return null;
                    const d = payload[0].payload;
                    return (
                      <div className="bg-surface-overlay/95 backdrop-blur-2xl border border-white/10 p-4 rounded-2xl shadow-2xl space-y-1">
                        <p className="text-[10px] font-black text-primary uppercase tracking-widest">{d.name}</p>
                        <p className="text-xs font-bold text-secondary">Conviction: {d.x}</p>
                        <p className="text-xs font-bold text-exit">Drawdown: {d.y.toFixed(1)}%</p>
                        <p className="text-xs font-bold text-hint">Value: {mask(formatCurrency(d.z))}</p>
                      </div>
                    );
                  }}
                />
                
                <ReferenceLine x={50} stroke="rgba(255,255,255,0.1)" strokeDasharray="5 5" label={{ position: 'top', value: 'Avg Conviction', fill: 'rgba(255,255,255,0.35)', fontSize: 10, fontWeight: 900 }} />
                <ReferenceLine y={15} stroke="rgba(255,255,255,0.1)" strokeDasharray="5 5" label={{ position: 'right', value: 'Typical DD', fill: 'rgba(255,255,255,0.35)', fontSize: 10, fontWeight: 900 }} />

                <Scatter 
                  name="Funds" 
                  data={scatterData} 
                  onClick={(data) => onFundClick((data as any).name)}
                  className="cursor-pointer"
                >
                  {scatterData.map((entry: any, index: number) => (
                    <Cell key={`cell-${index}`} fill={ACTION_COLORS[entry.action as string]?.bg || '#cba6f7'} fillOpacity={0.6} />
                  ))}
                </Scatter>

                {/* Quadrant Labels */}
                <text x="25%" y="30%" textAnchor="middle" fill="rgba(243, 139, 168, 0.15)" fontSize="10" fontWeight="900" className="pointer-events-none uppercase tracking-widest">Exit Candidates</text>
                <text x="75%" y="30%" textAnchor="middle" fill="rgba(203, 166, 247, 0.15)" fontSize="10" fontWeight="900" className="pointer-events-none uppercase tracking-widest">Hold with Care</text>
                <text x="25%" y="80%" textAnchor="middle" fill="rgba(108, 112, 134, 0.15)" fontSize="10" fontWeight="900" className="pointer-events-none uppercase tracking-widest">Review Needed</text>
                <text x="75%" y="80%" textAnchor="middle" fill="rgba(166, 227, 161, 0.15)" fontSize="10" fontWeight="900" className="pointer-events-none uppercase tracking-widest">Ideal Core</text>
              </ScatterChart>
            </ResponsiveContainer>
          </div>
        </section>
      </div>
    </div>
  );
}
