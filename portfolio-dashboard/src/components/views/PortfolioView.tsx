import { motion } from 'framer-motion';
import { 
  ResponsiveContainer, XAxis, YAxis, Tooltip as RechartsTooltip, 
  BarChart, Bar, Cell, CartesianGrid, Treemap
} from "recharts";
import { HeartPulse } from 'lucide-react';
import MetricWithTooltip from '../ui/MetricWithTooltip';
import CurrencyValue from '../ui/CurrencyValue';

const PALETTE = ['#818cf8', '#34d399', '#fbbf24', '#f87171', '#94a3b8', '#e879f9', '#38bdf8'];

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

  const totalUnrealizedLTCG = breakdown.reduce((a: number, s: any) => a + (s.ltcgUnrealizedGain || 0), 0);
  const totalUnrealizedGainPos = breakdown.reduce((a: number, s: any) => a + Math.max(0, (s.unrealizedGain || 0)), 0);
  const taxEfficiency = totalUnrealizedGainPos > 0 ? totalUnrealizedLTCG / totalUnrealizedGainPos : 0;

  const highConv = breakdown.filter((s: any) => s.convictionScore >= 65).length;
  const midConv = breakdown.filter((s: any) => s.convictionScore >= 45 && s.convictionScore < 65).length;
  const lowConv = breakdown.filter((s: any) => s.convictionScore < 45).length;
  const totalFunds = breakdown.length || 1;

  const unrealizedPct = portfolioData.totalInvestedAmount 
    ? (portfolioData.totalUnrealizedGain / portfolioData.totalInvestedAmount) * 100 
    : 0;
  const unrealizedAbsPct = Math.min(100, Math.abs(unrealizedPct));
  const isUnrealizedNegative = unrealizedPct < 0;

  const bucketMap: Record<string, number> = {};
  breakdown.forEach((s: any) => {
    bucketMap[s.bucket] = (bucketMap[s.bucket] || 0) + (s.currentValue || 0);
  });
  
  // Prep Treemap Data (Nested hierarchy)
  const treemapData = Object.entries(
    breakdown.reduce((acc: any, s: any) => {
      const b = s.bucket || 'OTHERS';
      if (!acc[b]) acc[b] = { name: b.replace(/_/g, ' '), children: [] };
      acc[b].children.push({ name: s.schemeName, size: s.currentValue || 0 });
      return acc;
    }, {})
  ).map(([_, val]: any) => val);

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
        <div className="bg-surface border border-border p-6 rounded-xl relative overflow-hidden">
          <div className="absolute left-0 top-4 bottom-4 w-px bg-accent/40" />
          <div className="pl-3">
            <MetricWithTooltip 
              label="Portfolio Value" 
              value={<CurrencyValue isPrivate={isPrivate} value={totalValue} />}
              tooltip="The current market value of all active holdings."
            />
          </div>
        </div>
        <div className="bg-surface border border-border p-6 rounded-xl relative overflow-hidden">
          <div className={`absolute left-0 top-4 bottom-4 w-px ${parseFloat(portfolioData.overallXirr) >= 0 ? 'bg-buy/40' : 'bg-exit/40'}`} />
          <div className="pl-3">
            <MetricWithTooltip 
              label="System XIRR" 
              value={portfolioData.overallXirr} 
              valueClass={parseFloat(portfolioData.overallXirr) >= 0 ? "text-buy glow-buy" : "text-exit glow-exit"}
              tooltip="Internal Rate of Return accounting for the timing of all cash flows."
            />
          </div>
        </div>
        <div className="bg-surface border border-border p-6 rounded-xl relative overflow-hidden">
          <div className={`absolute left-0 top-4 bottom-4 w-px ${portfolioData.totalUnrealizedGain >= 0 ? 'bg-buy/40' : 'bg-exit/40'}`} />
          <div className="pl-3">
            <MetricWithTooltip 
              label="Unrealised P&L" 
              value={<CurrencyValue isPrivate={isPrivate} value={portfolioData.totalUnrealizedGain} />}
              valueClass={portfolioData.totalUnrealizedGain >= 0 ? "text-buy glow-buy" : "text-exit glow-exit"}
              tooltip="Current profit or loss from holdings you haven't sold yet."
            />
          </div>
        </div>
        <div className="bg-surface border border-border p-6 rounded-xl relative overflow-hidden">
          <div className="absolute left-0 top-4 bottom-4 w-px bg-warning/40" />
          <div className="pl-3">
            <MetricWithTooltip 
              label="Tax Exposure (STCG)" 
              value={<CurrencyValue isPrivate={isPrivate} value={portfolioData.totalSTCG} />}
              valueClass="text-warning"
              tooltip="Estimated tax if you sold all units held for less than 1 year today."
            />
          </div>
        </div>
      </div>

      <section className="bg-surface border border-border p-8 rounded-xl">
        <div className="flex items-center justify-between mb-8">
          <h3 className="text-primary text-sm font-medium tracking-tight">Strategy Allocation Treemap</h3>
          <span className="text-muted text-[10px] uppercase tracking-widest">Box size = Market Value</span>
        </div>
        
        <div className="h-[400px] w-full bg-white/[0.02] rounded-lg overflow-hidden border border-white/5">
          <ResponsiveContainer width="100%" height="100%">
            <Treemap
              data={treemapData}
              dataKey="size"
              aspectRatio={4 / 3}
              stroke="rgba(9,9,15,0.8)"
              fill="#818cf8"
            >
              <RechartsTooltip 
                content={({ active, payload }) => {
                  if (active && payload && payload.length) {
                    const data = payload[0].payload;
                    return (
                      <div className="bg-surface-elevated border border-border p-3 rounded-lg shadow-2xl backdrop-blur-md">
                        <p className="text-[10px] text-muted uppercase tracking-widest mb-1">{data.name}</p>
                        <p className="text-xs font-bold text-primary">
                          {isPrivate ? '••••' : `₹${(data.size / 1000).toFixed(1)}k`}
                        </p>
                      </div>
                    );
                  }
                  return null;
                }}
              />
            </Treemap>
          </ResponsiveContainer>
        </div>
      </section>

      <section>
        <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest mb-4 flex items-center gap-2">
          <HeartPulse size={12} className="text-accent" /> Vital Signs
        </h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="bg-surface border border-border p-6 rounded-xl space-y-4">
            <MetricWithTooltip 
              label="Portfolio CVaR" 
              value={`${weightedCVaR.toFixed(2)}%`}
              tooltip="Expected loss on the worst 5% of trading days. Closer to zero is safer."
            />
            <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden flex flex-row-reverse">
              <div 
                className="h-full bg-accent transition-all duration-500 shadow-[0_0_10px_rgba(129,140,248,0.3)]" 
                style={{ width: `${Math.min(100, Math.abs(weightedCVaR) * 10)}%` }} 
              />
            </div>
            <div className="flex justify-between items-center text-[9px] text-muted uppercase tracking-tighter">
              <span>Risky</span>
              <span>Safe</span>
            </div>
          </div>

          <div className="bg-surface border border-border p-6 rounded-xl space-y-4">
            <MetricWithTooltip 
              label="Tax Efficiency (Unrealised)" 
              value={`${(taxEfficiency * 100).toFixed(1)}%`}
              tooltip="Percentage of your current paper profits that are in Long-Term holdings (LTCG)."
            />
            <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
              <div 
                className="h-full bg-buy transition-all duration-500 shadow-[0_0_10px_rgba(52,211,153,0.3)]" 
                style={{ width: `${taxEfficiency * 100}%` }} 
              />
            </div>
            <div className="flex justify-between items-center text-[9px] text-muted uppercase tracking-tighter">
              <span>Low LTCG</span>
              <span>Tax Optimized</span>
            </div>
          </div>

          <div className="bg-surface border border-border p-6 rounded-xl space-y-4">
            <MetricWithTooltip 
              label="Conviction Distribution" 
              value={`${highConv} High / ${midConv} Mid`}
              tooltip="Distribution of conviction scores across your active funds."
            />
            <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden flex">
              <div style={{ width: `${(highConv/totalFunds)*100}%` }} className="h-full bg-buy shadow-[0_0_10px_rgba(52,211,153,0.3)]" />
              <div style={{ width: `${(midConv/totalFunds)*100}%` }} className="h-full bg-warning shadow-[0_0_10px_rgba(251,191,36,0.3)]" />
              <div style={{ width: `${(lowConv/totalFunds)*100}%` }} className="h-full bg-exit shadow-[0_0_10px_rgba(248,113,113,0.3)]" />
            </div>
            <div className="flex gap-4">
              <span className="text-[10px] text-buy font-bold">{highConv} high</span>
              <span className="text-[10px] text-warning font-bold">{midConv} mid</span>
              <span className="text-[10px] text-exit font-bold">{lowConv} low</span>
            </div>
          </div>

          <div className="bg-surface border border-border p-6 rounded-xl space-y-4">
            <MetricWithTooltip 
              label="Unrealised Gain %" 
              value={`${unrealizedPct.toFixed(1)}%`}
              valueClass={isUnrealizedNegative ? "text-exit glow-exit" : "text-buy glow-buy"}
              tooltip="Absolute return on currently active capital."
            />
            <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
              <div 
                className={`h-full transition-all duration-500 ${isUnrealizedNegative ? 'bg-exit shadow-[0_0_10px_rgba(248,113,113,0.3)]' : 'bg-buy shadow-[0_0_10px_rgba(52,211,153,0.3)]'}`}
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

      <section className="bg-surface border border-border p-8 rounded-xl">
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
