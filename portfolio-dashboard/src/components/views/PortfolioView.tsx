import { 
  ResponsiveContainer, XAxis, YAxis, Tooltip as RechartsTooltip, 
  BarChart, Bar, CartesianGrid, Treemap
} from "recharts";
import { HeartPulse } from 'lucide-react';
import MetricWithTooltip from '../ui/MetricWithTooltip';
import LearnTooltip from '../ui/LearnTooltip';

const ACTION_COLORS: Record<string, string> = {
  BUY: '#34d399',
  SELL: '#f87171',
  WATCH: '#fbbf24',
  EXIT: '#ef4444',
  HOLD: '#6366f1',
  DEFAULT: '#94a3b8'
};

const CustomTreemapContent = (props: any) => {
  const { x, y, width, height, name, action } = props;
  const color = ACTION_COLORS[action as string] || ACTION_COLORS.HOLD;

  return (
    <g>
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        style={{
          fill: color,
          stroke: 'rgba(9,9,15,0.8)',
          strokeWidth: 2,
        }}
      />
      {width > 80 && height > 30 && (
        <text
          x={x + 5}
          y={y + 15}
          fill="#fff"
          fontSize={10}
          fontWeight="bold"
        >
          {name.length > 20 ? name.substring(0, 20) + '...' : name}
        </text>
      )}
      {width > 50 && action && (
        <rect
          x={x + width - 45}
          y={y + 5}
          width={40}
          height={14}
          rx={7}
          fill="rgba(0,0,0,0.2)"
        />
      )}
      {width > 50 && action && (
        <text
          x={x + width - 25}
          y={y + 15}
          textAnchor="middle"
          fill="#fff"
          fontSize={8}
          fontWeight="bold"
        >
          {action}
        </text>
      )}
    </g>
  );
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

  const totalUnrealizedLTCG = breakdown.reduce((a: number, s: any) => a + (s.ltcgUnrealizedGain || 0), 0);
  const totalUnrealizedGainPos = breakdown.reduce((a: number, s: any) => a + Math.max(0, (s.unrealizedGain || 0)), 0);
  const taxEfficiency = totalUnrealizedGainPos > 0 ? totalUnrealizedLTCG / totalUnrealizedGainPos : 0;

  // Prep Treemap Data (Nested hierarchy)
  const treemapData = Object.entries(
    breakdown.reduce((acc: any, s: any) => {
      const b = s.bucket || 'OTHERS';
      if (!acc[b]) acc[b] = { name: b.replace(/_/g, ' '), children: [] };
      acc[b].children.push({ name: s.schemeName, size: s.currentValue || 0, action: s.action });
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

  const formatCurrency = (val: number) => {
    if (isPrivate) return '₹••••';
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(val);
  };

  return (
    <div className="space-y-10 pb-32">
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-surface border border-border p-6 rounded-xl relative overflow-hidden">
          <div className="absolute left-0 top-4 bottom-4 w-px bg-accent/40" />
          <div className="pl-3">
            <MetricWithTooltip 
              label="Portfolio Value" 
              value={formatCurrency(totalValue)}
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
              value={formatCurrency(portfolioData.totalUnrealizedGain)}
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
              value={formatCurrency(portfolioData.totalSTCG)}
              valueClass="text-warning"
              tooltip="Estimated tax if you sold all units held for less than 1 year today."
            />
          </div>
        </div>
      </div>

      <section className="bg-surface border border-border p-8 rounded-xl">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-primary text-sm font-medium tracking-tight">Strategy Allocation Treemap</h3>
          <span className="text-muted text-[10px] uppercase tracking-widest">Box size = Market Value</span>
        </div>
        <p className="text-[10px] text-muted mb-6">
          Box size = portfolio value. Color = what the engine recommends.
          Hover any box for details.
        </p>
        
        <div className="h-[400px] w-full bg-white/[0.02] rounded-lg overflow-hidden border border-white/5">
          <ResponsiveContainer width="100%" height="100%">
            <Treemap
              data={treemapData}
              dataKey="size"
              aspectRatio={4 / 3}
              stroke="rgba(9,9,15,0.8)"
              content={<CustomTreemapContent />}
            >
              <RechartsTooltip 
                content={({ active, payload }) => {
                  if (active && payload && payload.length) {
                    const data = payload[0].payload;
                    return (
                      <div className="bg-surface-elevated border border-border p-3 rounded-lg shadow-2xl backdrop-blur-md">
                        <p className="text-[10px] text-muted uppercase tracking-widest mb-1">{data.name}</p>
                        <p className="text-xs font-bold text-primary mb-1">
                          {isPrivate ? '••••' : `₹${(data.size / 1000).toFixed(1)}k`}
                        </p>
                        {data.action && (
                          <p className="text-[9px] font-bold text-buy">{data.action} Signal</p>
                        )}
                      </div>
                    );
                  }
                  return null;
                }}
              />
            </Treemap>
          </ResponsiveContainer>
        </div>

        <div className="flex gap-4 flex-wrap mt-3">
          {[
            {label:'On sale — good entry', color:'#34d399', action:'BUY'},
            {label:'Overheated — trim', color:'#f87171', action:'SELL'},
            {label:'Watching', color:'#fbbf24', action:'WATCH'},
            {label:'Holding steady', color:'#6366f1', action:'HOLD'},
          ].map(item => (
            <div key={item.action} className="flex items-center gap-2">
              <div className="w-3 h-3 rounded-sm" style={{background: item.color}}/>
              <span className="text-[10px] text-muted">{item.label}</span>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h3 className="text-muted text-[10px] font-medium uppercase tracking-widest mb-4 flex items-center gap-2">
          <HeartPulse size={12} className="text-accent" /> Vital Signs
        </h3>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {/* 1. Portfolio CVaR */}
          <div className="bg-surface border border-border p-6 rounded-xl space-y-4">
            <MetricWithTooltip 
              label={<LearnTooltip term="CVaR">Portfolio CVaR</LearnTooltip>}
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
              {weightedCVaR < -3.5 ? (
                <span className="text-exit">⚠ Critical — buys paused</span>
              ) : weightedCVaR < -2.5 ? (
                <span className="text-warning">Elevated risk</span>
              ) : weightedCVaR < -1.5 ? (
                <span className="text-warning">Moderate risk</span>
              ) : (
                <span className="text-buy">Safe</span>
              )}
            </div>
          </div>

          {/* 2. Tax Efficiency */}
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
            <div className="flex flex-col gap-1">
              <div className="flex justify-between items-center text-[9px] text-muted uppercase tracking-tighter">
                {taxEfficiency < 0.4 ? (
                  <span className="text-exit">Low LTCG</span>
                ) : taxEfficiency < 0.7 ? (
                  <span className="text-warning">Building</span>
                ) : (
                  <span className="text-buy">Tax Optimised</span>
                )}
              </div>
              <p className="text-[8px] text-muted opacity-60">% of unrealised gains that qualify for the lower 12.5% LTCG rate</p>
            </div>
          </div>

          {/* 3. Regime Pulse (HMM) */}
          {(() => {
            const bull = breakdown.filter((s: any) => s.hmmState === 'CALM_BULL').length;
            const bear = breakdown.filter((s: any) => s.hmmState === 'VOLATILE_BEAR').length;
            const neutralCount = breakdown.length - bull - bear;
            const total = breakdown.length || 1;

            return (
              <div className="bg-surface border border-border p-6 rounded-xl space-y-4">
                <MetricWithTooltip 
                  label="Regime Pulse" 
                  value={`${bull} Bull / ${bear} Bear`}
                  tooltip="How many of your funds are currently in a bull vs bear regime according to the Hidden Markov Model analysis."
                />
                <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden flex">
                  <div style={{ width: `${(bull/total)*100}%` }} className="h-full bg-buy shadow-[0_0_8px_rgba(52,211,153,0.3)]" />
                  <div style={{ width: `${(neutralCount/total)*100}%` }} className="h-full bg-white/10" />
                  <div style={{ width: `${(bear/total)*100}%` }} className="h-full bg-exit shadow-[0_0_8px_rgba(248,113,113,0.3)]" />
                </div>
                <div className="flex justify-between items-center text-[9px] text-muted uppercase tracking-tighter">
                  <span>Market climate across funds</span>
                </div>
              </div>
            );
          })()}

          {/* 4. Half-life Clock (OU) */}
          {(() => {
            const validFunds = breakdown.filter((s: any) => s.ouValid);
            const avgHalfLife = validFunds.length > 0 
              ? validFunds.reduce((acc: number, s: any) => acc + s.ouHalfLife, 0) / validFunds.length 
              : 0;

            return (
              <div className="bg-surface border border-border p-6 rounded-xl space-y-4">
                <MetricWithTooltip 
                  label="Avg. Reversion Speed" 
                  value={avgHalfLife > 0 ? `${avgHalfLife.toFixed(0)} days` : 'N/A'}
                  tooltip="How long it typically takes for a fund's discount to halve (OU Process half-life)."
                />
                <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
                  <div 
                    className={`h-full transition-all duration-500 ${avgHalfLife < 20 ? 'bg-warning shadow-[0_0_8px_rgba(251,191,36,0.3)]' : avgHalfLife > 60 ? 'bg-accent shadow-[0_0_8px_rgba(129,140,248,0.3)]' : 'bg-white/20'}`} 
                    style={{ width: `${Math.min(100, avgHalfLife)}%` }} 
                  />
                </div>
                <div className="flex flex-col gap-1 text-[9px] text-muted uppercase tracking-tighter">
                  {avgHalfLife === 0 ? <span>Waiting for data</span> :
                   avgHalfLife < 20 ? <span className="text-warning">Fast market — act quickly</span> :
                   avgHalfLife > 60 ? <span className="text-accent">Patient market — discounts persist</span> :
                   <span>Normal reversion pace</span>
                  }
                </div>
              </div>
            );
          })()}
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
