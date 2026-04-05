import { 
  ResponsiveContainer, XAxis, YAxis, Tooltip, 
  BarChart, Bar, Cell, CartesianGrid, ReferenceLine
} from "recharts";
import CurrencyValue from '../ui/CurrencyValue';

export default function RebalanceView({ 
  portfolioData, 
  sipAmount, 
  setSipAmount,
  isPrivate 
}: { 
  portfolioData: any;
  sipAmount: number;
  setSipAmount: (val: number) => void;
  isPrivate: boolean;
}) {
  const data = (portfolioData.schemeBreakdown || [])
    .filter((s: any) => s.plannedPercentage > 0 || s.allocationPercentage > 0)
    .map((s: any) => {
      const drift = (s.allocationPercentage || 0) - (s.plannedPercentage || 0);
      return {
        ...s,
        name: s.schemeName.substring(0, 20),
        drift: parseFloat(drift.toFixed(2)),
        color: drift > 1 ? "#f87171" : drift < -1 ? "#34d399" : "#94a3b8"
      };
    })
    .sort((a: any, b: any) => b.drift - a.drift);

  const totalDrift = data.reduce((acc: number, s: any) => acc + Math.abs(s.drift), 0);
  const needsAttention = data.filter((s: any) => Math.abs(s.drift) > 1).length;

  return (
    <div className="space-y-10 pb-32">
      <header>
        <h2 className="text-muted text-[10px] font-medium uppercase tracking-[0.2em] mb-1">Target alignment</h2>
        <p className="text-xl font-medium text-primary tracking-tight">Allocation drift · actual vs target</p>
      </header>

      {/* DRIFT CHART */}
      <section className="bg-surface border border-white/5 p-8 rounded-xl">
        <div className="h-80 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 20, right: 30, left: 20, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.03)" vertical={false} />
              <XAxis 
                dataKey="name" 
                axisLine={false} 
                tickLine={false} 
                tick={{ fill: 'rgba(241,245,249,0.3)', fontSize: 10 }}
              />
              <YAxis 
                axisLine={false} 
                tickLine={false} 
                tick={{ fill: 'rgba(241,245,249,0.3)', fontSize: 10 }}
                unit="%"
              />
              {!isPrivate && (
                <Tooltip 
                  cursor={{ fill: 'rgba(255,255,255,0.02)' }}
                  contentStyle={{ backgroundColor: '#0f0f18', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '8px' }}
                />
              )}
              <ReferenceLine y={0} stroke="rgba(255,255,255,0.1)" />
              <Bar dataKey="drift" radius={[4, 4, 0, 0]} barSize={30}>
                {data.map((entry: any, index: number) => (
                  <Cell key={`cell-${index}`} fill={entry.color} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
        <div className="mt-8 pt-8 border-t border-white/5 flex items-center gap-6">
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-sm bg-exit" />
            <span className="text-[10px] uppercase tracking-widest text-muted">Overweight</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-sm bg-buy" />
            <span className="text-[10px] uppercase tracking-widest text-muted">Underweight</span>
          </div>
          <div className="ml-auto text-secondary text-[11px] font-medium">
            Total drift: <span className="text-primary">{totalDrift.toFixed(1)}%</span> across your portfolio. {needsAttention} funds need rebalancing.
          </div>
        </div>
      </section>

      {/* DETAILS TABLE */}
      <section className="bg-surface border border-white/5 rounded-xl overflow-hidden">
        <table className="w-full text-left">
          <thead className="bg-white/[0.02] text-muted text-[10px] uppercase tracking-widest border-b border-white/5">
            <tr>
              <th className="px-6 py-4 font-medium">Fund Name</th>
              <th className="px-6 py-4 font-medium text-right">Target %</th>
              <th className="px-6 py-4 font-medium text-right">Actual %</th>
              <th className="px-6 py-4 font-medium text-right">Drift</th>
              <th className="px-6 py-4 font-medium text-right">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/5">
            {data.map((s: any) => (
              <tr key={s.schemeName} className="hover:bg-white/[0.01] transition-colors group">
                <td className="px-6 py-4 text-[13px] text-primary truncate max-w-md">{s.schemeName}</td>
                <td className="px-6 py-4 text-right text-[13px] text-secondary tabular-nums">{(s.plannedPercentage || 0).toFixed(1)}%</td>
                <td className="px-6 py-4 text-right text-[13px] text-secondary tabular-nums">{(s.allocationPercentage || 0).toFixed(1)}%</td>
                <td className={`px-6 py-4 text-right text-[13px] font-medium tabular-nums ${
                  s.drift > 1 ? 'text-exit' : s.drift < -1 ? 'text-buy' : 'text-hold'
                }`}>
                  {s.drift > 0 ? '+' : ''}{s.drift}%
                </td>
                <td className="px-6 py-4 text-right">
                  <span className={`text-[10px] font-bold uppercase tracking-widest ${
                    s.action === 'BUY' ? 'text-buy' : 
                    s.action === 'EXIT' ? 'text-exit' : 'text-muted'
                  }`}>
                    {s.action}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {/* SIP SIMULATOR */}
      <section className="bg-accent/5 border border-accent/10 p-8 rounded-xl flex flex-col md:flex-row items-center gap-10">
        <div className="flex-1 space-y-4">
          <div className="flex justify-between items-center">
            <h3 className="text-primary text-sm font-medium tracking-tight">SIP Correction Simulator</h3>
            <CurrencyValue isPrivate={isPrivate} value={sipAmount} className="text-primary tabular-nums font-medium" />
          </div>
          <input 
            type="range" min="0" max="200000" step="1000" 
            value={sipAmount} 
            onChange={(e) => setSipAmount(parseInt(e.target.value) || 0)}
            className="w-full h-1 bg-white/10 rounded-lg appearance-none cursor-pointer accent-accent"
          />
          <p className="text-muted text-[11px] leading-relaxed">
            Increasing your SIP will automatically prioritize underweight funds with the highest conviction scores, 
            naturally pulling your portfolio back to its target alignment over time without incurring exit taxes.
          </p>
        </div>
        <div className="shrink-0 flex flex-col items-center justify-center p-6 bg-surface-elevated border border-white/5 rounded-2xl w-48 text-center">
          <p className="text-muted text-[10px] uppercase tracking-widest mb-2">Alignment ETA</p>
          <p className="text-2xl font-medium text-primary">3.5 <span className="text-xs text-muted">mo</span></p>
          <p className="text-[10px] text-buy font-bold mt-2 uppercase tracking-tighter">Healthy Recovery</p>
        </div>
      </section>
    </div>
  );
}
