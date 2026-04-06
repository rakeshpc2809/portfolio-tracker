import { motion } from 'framer-motion';
import { 
  ResponsiveContainer, XAxis, YAxis, Tooltip as RechartsTooltip, 
  BarChart, Bar, Cell, CartesianGrid, PieChart, Pie
} from "recharts";
import { HeartPulse } from 'lucide-react';
import MetricWithTooltip from '../ui/MetricWithTooltip';
import CurrencyValue from '../ui/CurrencyValue';

const BUCKET_COLORS: Record<string, string> = {
  AGGRESSIVE_GROWTH: "#818cf8",
  SAFE_REBALANCER_EQUITY_TAX: "#34d399",
  GOLD_HEDGE_24M: "#fbbf24",
  DEBT_SLAB_TAXED: "#f87171",
  OTHERS_CHECK_ISIN: "#94a3b8"
};

export default function PortfolioView({ 
  portfolioData, 
  isPrivate 
}: { 
  portfolioData: any; 
  isPrivate: boolean;
}) {
  const breakdown = portfolioData.schemeBreakdown || [];
  
  const totalValue = portfolioData.currentValueAmount || 0;
  const weightedCVaR = breakdown.reduce((acc: number, s: any) => {
    const weight = (s.currentValue || 0) / (totalValue || 1);
    return acc + (s.cvar5 || 0) * weight;
  }, 0);

  // Bug 4 Fix: Tax efficiency using unrealized LTCG ratio
  const totalUnrealizedLTCG = breakdown.reduce((a: number, s: any) => a + (s.ltcgUnrealizedGain || 0), 0);
  const totalUnrealizedGainPos = breakdown.reduce((a: number, s: any) => a + Math.max(0, (s.unrealizedGain || 0)), 0);
  const taxEfficiency = totalUnrealizedGainPos > 0 ? totalUnrealizedLTCG / totalUnrealizedGainPos : 0;

  // Design Improvement 3: Conviction distribution
  const highConv = breakdown.filter((s: any) => s.convictionScore >= 65).length;
  const midConv = breakdown.filter((s: any) => s.convictionScore >= 45 && s.convictionScore < 65).length;
  const lowConv = breakdown.filter((s: any) => s.convictionScore < 45).length;
  const totalFunds = breakdown.length || 1;

  // Bug 3 Fix: Unrealized Gain % gauge
  const unrealizedPct = portfolioData.totalInvestedAmount 
    ? (portfolioData.totalUnrealizedGain / portfolioData.totalInvestedAmount) * 100 
    : 0;
  const unrealizedAbsPct = Math.min(100, Math.abs(unrealizedPct));
  const isUnrealizedNegative = unrealizedPct < 0;

  const bucketMap: Record<string, number> = {};
  breakdown.forEach((s: any) => {
    bucketMap[s.bucket] = (bucketMap[s.bucket] || 0) + (s.currentValue || 0);
  });
  
  const bucketData = Object.entries(bucketMap).map(([name, value]) => {
    const fundsInBucket = breakdown.filter((s: any) => s.bucket === name);
    return {
      name: name.replace(/_/g, ' '),
      value: (value / (totalValue || 1)) * 100,
      color: BUCKET_COLORS[name] || "#94a3b8",
      count: fundsInBucket.length
    };
  }).sort((a, b) => b.value - a.value);

  const xirrData = breakdown
    .filter((s: any) => (s.currentValue || 0) > 0)
    .map((s: any) => ({
      name: s.schemeName.substring(0, 20),
      Personal: parseFloat(s.xirr || '0'),
      Benchmark: s.category?.includes("MIDCAP") ? 18 : 14
    }))
    .sort((a: any, b: any) => b.Personal - a.Personal);

  return (
    <div className="space-y-10 pb-32">
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-surface border border-white/5 p-6 rounded-xl">
          <MetricWithTooltip 
            label="Portfolio Value" 
            value={<CurrencyValue isPrivate={isPrivate} value={totalValue} />}
            tooltip="The current market value of all active holdings."
          />
        </div>
        <div className="bg-surface border border-white/5 p-6 rounded-xl">
          <MetricWithTooltip 
            label="System XIRR" 
            value={portfolioData.overallXirr} 
            valueClass={parseFloat(portfolioData.overallXirr) >= 0 ? "text-buy" : "text-exit"}
            tooltip="Internal Rate of Return accounting for the timing of all cash flows."
          />
        </div>
        <div className="bg-surface border border-white/5 p-6 rounded-xl">
          <MetricWithTooltip 
            label="Unrealised P&L" 
            value={<CurrencyValue isPrivate={isPrivate} value={portfolioData.totalUnrealizedGain} />}
            valueClass={portfolioData.totalUnrealizedGain >= 0 ? "text-buy" : "text-exit"}
            tooltip="Current profit or loss from holdings you haven't sold yet."
          />
        </div>
        <div className="bg-surface border border-white/5 p-6 rounded-xl">
          <MetricWithTooltip 
            label="Tax Exposure (STCG)" 
            value={<CurrencyValue isPrivate={isPrivate} value={portfolioData.totalSTCG} />}
            valueClass="text-warning"
            tooltip="Estimated tax if you sold all units held for less than 1 year today."
          />
        </div>
      </div>

      <section className="bg-surface border border-white/5 p-8 rounded-xl">
        <div className="flex items-center justify-between mb-8">
          <h3 className="text-primary text-sm font-medium tracking-tight">Allocation by Strategy Bucket</h3>
          <span className="text-muted text-[10px] uppercase tracking-widest">Target vs Actual</span>
        </div>
        
        <div className="flex flex-col lg:flex-row items-center gap-12">
          {/* Donut chart */}
          <div className="w-full lg:w-64 h-64 shrink-0">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={bucketData}
                  cx="50%"
                  cy="50%"
                  innerRadius={60}
                  outerRadius={90}
                  paddingAngle={3}
                  dataKey="value"
                  stroke="none"
                >
                  {bucketData.map((entry, index) => (
                    <Cell key={`cell-${index}`} fill={entry.color} />
                  ))}
                </Pie>
                {!isPrivate && (
                  <RechartsTooltip
                    formatter={(value: any) => [`${parseFloat(value).toFixed(1)}%`, '']}
                    contentStyle={{ backgroundColor: '#0f0f18', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '8px' }}
                  />
                )}
              </PieChart>
            </ResponsiveContainer>
          </div>

          {/* Bucket bars with custom legend */}
          <div className="flex-1 space-y-5 w-full">
            {bucketData.map((b) => (
              <div key={b.name} className="space-y-1.5">
                <div className="flex justify-between items-end">
                  <div className="flex items-center">
                    <span className="text-[10px] font-bold text-muted uppercase tracking-widest">{b.name}</span>
                    <span className="text-[9px] text-muted tabular-nums ml-2">{b.count} funds</span>
                  </div>
                  <span className="text-[11px] font-medium tabular-nums" style={{ color: b.color }}>{b.value.toFixed(1)}%</span>
                </div>
                <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
                  <motion.div 
                    initial={{ width: 0 }}
                    animate={{ width: `${b.value}%` }}
                    transition={{ duration: 1, ease: "easeOut" }}
                    className="h-full rounded-full" 
                    style={{ background: b.color }} 
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section>
        <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest mb-4 flex items-center gap-2">
          <HeartPulse size={12} className="text-accent" /> Vital Signs
        </h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* CVaR: fills from right (worst) to left (best) */}
          <div className="bg-surface border border-white/5 p-6 rounded-xl space-y-4">
            <MetricWithTooltip 
              label="Portfolio CVaR" 
              value={`${weightedCVaR.toFixed(2)}%`}
              tooltip="Expected loss on the worst 5% of trading days. Closer to zero is safer."
            />
            <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden flex flex-row-reverse">
              <div 
                className="h-full bg-accent transition-all duration-500" 
                style={{ width: `${Math.min(100, Math.abs(weightedCVaR) * 10)}%` }} 
              />
            </div>
            <div className="flex justify-between items-center text-[9px] text-muted uppercase tracking-tighter">
              <span>Risky</span>
              <span>Safe</span>
            </div>
          </div>

          {/* Tax Efficiency */}
          <div className="bg-surface border border-white/5 p-6 rounded-xl space-y-4">
            <MetricWithTooltip 
              label="Tax Efficiency (Unrealised)" 
              value={`${(taxEfficiency * 100).toFixed(1)}%`}
              tooltip="Percentage of your current paper profits that are in Long-Term holdings (LTCG)."
            />
            <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
              <div 
                className="h-full bg-buy transition-all duration-500" 
                style={{ width: `${taxEfficiency * 100}%` }} 
              />
            </div>
            <div className="flex justify-between items-center text-[9px] text-muted uppercase tracking-tighter">
              <span>Low LTCG</span>
              <span>Tax Optimized</span>
            </div>
          </div>

          {/* Average Conviction Distribution */}
          <div className="bg-surface border border-white/5 p-6 rounded-xl space-y-4">
            <MetricWithTooltip 
              label="Conviction Distribution" 
              value={`${highConv} High / ${midConv} Mid`}
              tooltip="Distribution of conviction scores across your active funds."
            />
            <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden flex">
              <div style={{ width: `${(highConv/totalFunds)*100}%` }} className="h-full bg-buy" />
              <div style={{ width: `${(midConv/totalFunds)*100}%` }} className="h-full bg-warning" />
              <div style={{ width: `${(lowConv/totalFunds)*100}%` }} className="h-full bg-exit" />
            </div>
            <div className="flex gap-4">
              <span className="text-[10px] text-buy">{highConv} high</span>
              <span className="text-[10px] text-warning">{midConv} mid</span>
              <span className="text-[10px] text-exit">{lowConv} low</span>
            </div>
          </div>

          {/* Unrealised Gain % Gauge */}
          <div className="bg-surface border border-white/5 p-6 rounded-xl space-y-4">
            <MetricWithTooltip 
              label="Unrealised Gain %" 
              value={`${unrealizedPct.toFixed(1)}%`}
              valueClass={isUnrealizedNegative ? "text-exit" : "text-buy"}
              tooltip="Absolute return on currently active capital."
            />
            <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
              <div 
                className={`h-full transition-all duration-500 ${isUnrealizedNegative ? 'bg-exit' : 'bg-buy'}`}
                style={{ width: `${unrealizedAbsPct}%` }} 
              />
            </div>
            <div className="flex justify-between items-center text-[9px] text-muted uppercase tracking-tighter">
              <span>0%</span>
              <span>{Math.max(10, Math.ceil(unrealizedAbsPct / 10) * 10)}%</span>
            </div>
          </div>
        </div>
      </section>

      <section className="bg-surface border border-white/5 p-8 rounded-xl">
        <div className="flex items-center justify-between mb-8">
          <h3 className="text-primary text-sm font-medium tracking-tight">XIRR performance · vs category avg</h3>
          <span className="text-muted text-[10px] uppercase tracking-widest">Tabular View Available in Ledger</span>
        </div>
        <div className="h-96 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={xirrData} layout="vertical" margin={{ left: 20, right: 40 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.03)" horizontal={true} vertical={false} />
              <XAxis type="number" axisLine={false} tickLine={false} tick={{ fill: 'rgba(241,245,249,0.3)', fontSize: 10 }} unit="%" />
              <YAxis 
                dataKey="name" 
                type="category" 
                axisLine={false} 
                tickLine={false} 
                width={150}
                tick={{ fill: 'rgba(241,245,249,0.7)', fontSize: 11, fontWeight: 500 }}
              />
              {!isPrivate && (
                <RechartsTooltip 
                  cursor={{ fill: 'rgba(255,255,255,0.02)' }}
                  contentStyle={{ backgroundColor: '#0f0f18', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '8px' }}
                />
              )}
              <Bar dataKey="Personal" fill="#818cf8" radius={[0, 2, 2, 0]} barSize={10} />
              <Bar dataKey="Benchmark" fill="rgba(255,255,255,0.05)" radius={[0, 2, 2, 0]} barSize={10} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </section>
    </div>
  );
}
