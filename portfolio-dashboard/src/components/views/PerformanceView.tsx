import { useState, useMemo } from 'react';
import { ResponsiveLine } from '@nivo/line';
import { Activity, Target } from 'lucide-react';
import { formatCurrency } from '../../utils/formatters';
import { motion } from 'framer-motion';
import { Slider } from '../ui/slider';
import { usePerformanceHistory } from '../../hooks/usePerformance';

export default function PerformanceView({ 
  pan, 
  isPrivate,
  portfolioData
}: { 
  portfolioData: any; 
  pan: string; 
  isPrivate: boolean;
}) {
  const { data: perf, isLoading } = usePerformanceHistory(pan);

  // Goal Projector State
  const [goalAmount, setGoalAmount] = useState(10000000); // ₹1Cr default
  const [monthlyAddition, setMonthlyAddition] = useState(0);

  const lineData = useMemo(() => {
    if (!perf || !perf.history || perf.history.length === 0) return [];
    return [
      {
        id: "Portfolio",
        color: "#cba6f7",
        data: perf.history.map((p: any) => ({ 
          x: new Date(p.date), 
          y: parseFloat(((p.value / (p.invested || 1)) * 100).toFixed(2)) 
        }))
      },
      {
        id: "Nifty 50",
        color: "rgba(166, 227, 161, 0.4)",
        data: (perf.niftyHistory || []).map((b: any) => ({ 
          x: new Date(b.date), 
          y: parseFloat(b.normalizedValue.toFixed(2)) 
        }))
      }
    ];
  }, [perf]);

  // Goal Projector Computation
  const xirr = perf?.xirr ?? 0;
  const currentValue = portfolioData?.currentValueAmount ?? 0;

  const yearsToGoal = useMemo(() => {
    if (xirr <= 0 || currentValue <= 0 || currentValue >= goalAmount) return null;
    const monthlyRate = Math.pow(1 + xirr / 100, 1 / 12) - 1;
    let n = 0;
    let fv = currentValue;
    while (fv < goalAmount && n < 600) { // max 50 years
      fv = fv * (1 + monthlyRate) + monthlyAddition;
      n++;
    }
    return n < 600 ? n / 12 : null;
  }, [xirr, currentValue, goalAmount, monthlyAddition]);

  if (isLoading) {
    return (
      <div className="h-96 flex items-center justify-center">
        <div className="w-10 h-10 border-4 border-accent border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  if (!perf || perf.history.length === 0) {
    return (
      <div className="py-20 text-center bg-surface/20 backdrop-blur-xl border border-dashed border-white/10 rounded-[2.5rem] space-y-4">
        <Activity size={40} className="text-muted/10 mx-auto" />
        <p className="text-muted text-[10px] font-black uppercase tracking-widest opacity-40">Performance history will appear after the first nightly snapshot</p>
      </div>
    );
  }

  const stats = [
    { label: 'Total Return', value: `${perf.totalReturn.toFixed(1)}%`, color: perf.totalReturn >= 0 ? 'text-buy' : 'text-exit' },
    { label: 'Annualised XIRR', value: `${perf.xirr.toFixed(1)}%`, color: perf.xirr >= 0 ? 'text-buy' : 'text-exit' },
    { label: 'Alpha vs Nifty', value: `${perf.alphaPct >= 0 ? '+' : ''}${perf.alphaPct.toFixed(1)}%`, color: perf.alphaPct >= 0 ? 'text-buy' : 'text-exit' },
    { label: 'Market Gain', value: formatCurrency(perf.marketGainRs), color: perf.marketGainRs >= 0 ? 'text-buy' : 'text-exit' },
  ];

  return (
    <div className="space-y-10 pb-32">
      {/* Hero Stats */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {stats.map((s, i) => (
          <motion.div 
            key={s.label}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.1 }}
            className="bg-surface/40 backdrop-blur-xl border border-white/5 p-6 rounded-2xl shadow-lg"
          >
            <p className="text-[10px] font-black uppercase tracking-widest text-muted mb-2">{s.label}</p>
            <p className={`text-2xl font-black tabular-nums tracking-tighter ${s.color}`}>
              {isPrivate && s.label.includes('Gain') ? '₹••••' : s.value}
            </p>
          </motion.div>
        ))}
      </div>

      {/* Main Growth Chart */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl space-y-8">
        <div className="flex items-center justify-between">
          <div className="space-y-1">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Wealth Trajectory</h3>
            <p className="text-sm font-bold text-secondary">Growth of ₹100 invested since inception · Normalized comparison</p>
          </div>
          <div className={`px-4 py-1.5 rounded-full border text-[10px] font-black uppercase tracking-widest ${perf.alphaPct >= 0 ? 'bg-buy/10 text-buy border-buy/20' : 'bg-exit/10 text-exit border-exit/20'}`}>
            Alpha: {perf.alphaPct >= 0 ? '+' : ''}{perf.alphaPct.toFixed(1)}% p.a.
          </div>
        </div>

        <div className="h-96 w-full bg-black/20 rounded-3xl border border-white/5 p-6 shadow-inner">
          <ResponsiveLine
            data={lineData}
            margin={{ top: 20, right: 20, bottom: 50, left: 50 }}
            xScale={{ type: 'time', format: 'native' }}
            xFormat="time:%Y-%m-%d"
            yScale={{ type: 'linear', min: 'auto', max: 'auto', stacked: false, reverse: false }}
            axisTop={null}
            axisRight={null}
            axisBottom={{
              format: '%b %Y',
              tickValues: 'every 3 months',
              tickSize: 5,
              tickPadding: 5,
              tickRotation: -45,
            }}
            axisLeft={{
              tickSize: 5,
              tickPadding: 5,
              tickRotation: 0,
              legend: 'Indexed Value',
              legendOffset: -40,
              legendPosition: 'middle'
            }}
            enableGridX={false}
            colors={d => d.color}
            lineWidth={3}
            enablePoints={false}
            enableArea={true}
            areaOpacity={0.05}
            useMesh={true}
            theme={{
              axis: {
                ticks: { text: { fill: "#6c7086", fontSize: 10, fontWeight: 700 } },
                legend: { text: { fill: "#6c7086", fontSize: 10, fontWeight: 900, textTransform: 'uppercase' } }
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
                <p className="text-[9px] font-bold text-buy uppercase">+{((point.data.y as number) - 100).toFixed(1)}% since start</p>
              </div>
            )}
          />
        </div>
      </section>

      {/* Period Returns Table */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl">
        <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em] mb-8">Periodic Performance Matrix</h3>
        <div className="overflow-x-auto">
          <table className="w-full text-left">
            <thead>
              <tr className="border-b border-white/5">
                <th className="pb-4 text-[9px] font-black text-muted uppercase tracking-widest">Period</th>
                <th className="pb-4 text-[9px] font-black text-muted uppercase tracking-widest text-center">Portfolio</th>
                <th className="pb-4 text-[9px] font-black text-muted uppercase tracking-widest text-center">Nifty 50</th>
                <th className="pb-4 text-[9px] font-black text-muted uppercase tracking-widest text-right">Alpha</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {[
                { label: '1 Month', val: perf.periodReturns.oneMonth, bench: perf.niftyReturns?.oneMonth ?? 0 }, 
                { label: '3 Months', val: perf.periodReturns.threeMonth, bench: perf.niftyReturns?.threeMonth ?? 0 },
                { label: '6 Months', val: perf.periodReturns.sixMonth, bench: perf.niftyReturns?.sixMonth ?? 0 },
                { label: '1 Year', val: perf.periodReturns.oneYear, bench: perf.niftyReturns?.oneYear ?? 0 },
                { label: '3 Years', val: perf.periodReturns.threeYear, bench: perf.niftyReturns?.threeYear ?? 0 },
                { label: 'ITD', val: perf.periodReturns.itd, bench: perf.niftyReturns?.itd ?? 0 },
              ].map((row) => (
                <tr key={row.label} className="group hover:bg-white/[0.02] transition-colors">
                  <td className="py-5 text-xs font-black text-secondary uppercase tracking-widest">{row.label}</td>
                  <td className={`py-5 text-center text-sm font-black tabular-nums ${row.val >= 0 ? 'text-buy' : 'text-exit'}`}>
                    {row.val >= 0 ? '+' : ''}{row.val.toFixed(1)}%
                  </td>
                  <td className="py-5 text-center text-sm font-black tabular-nums text-muted">{row.bench.toFixed(1)}%</td>
                  <td className={`py-5 text-right text-sm font-black tabular-nums ${row.val - row.bench >= 0 ? 'text-buy' : 'text-exit'}`}>
                    {(row.val - row.bench) >= 0 ? '+' : ''}{(row.val - row.bench).toFixed(1)}%
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {/* Goal Projector Section */}
      <section className="bg-accent/5 backdrop-blur-xl border border-accent/10 p-10 rounded-[2.5rem] shadow-2xl space-y-10">
        <div className="flex items-center gap-4">
          <div className="p-3 bg-accent/10 rounded-2xl text-accent border border-accent/20">
            <Target size={24} />
          </div>
          <div>
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Wealth Goal Projector</h3>
            <p className="text-sm font-bold text-secondary">Based on your actual {xirr.toFixed(1)}% XIRR engine performance</p>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-16">
          <div className="space-y-10">
            <div className="space-y-6">
              <div className="flex justify-between items-center">
                <label className="text-[10px] font-black uppercase tracking-widest text-muted">Target Corpus</label>
                <span className="text-sm font-black text-accent">{formatCurrency(goalAmount)}</span>
              </div>
              <Slider 
                value={[goalAmount]} 
                min={2500000} 
                max={100000000} 
                step={500000} 
                onValueChange={([val]) => setGoalAmount(val)}
              />
            </div>

            <div className="space-y-6">
              <div className="flex justify-between items-center">
                <label className="text-[10px] font-black uppercase tracking-widest text-muted">Monthly Contribution (SIP)</label>
                <span className="text-sm font-black text-secondary">{formatCurrency(monthlyAddition)}</span>
              </div>
              <Slider 
                value={[monthlyAddition]} 
                min={0} 
                max={200000} 
                step={5000} 
                onValueChange={([val]) => setMonthlyAddition(val)}
              />
            </div>
          </div>

          <div className="bg-black/20 rounded-[2rem] p-8 border border-white/5 flex flex-col justify-center items-center text-center relative overflow-hidden">
            <div className="absolute top-0 left-0 w-full h-1 bg-accent/20" />
            
            {yearsToGoal ? (
              <motion.div 
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                className="space-y-4"
              >
                <p className="text-[10px] font-black uppercase tracking-[0.2em] text-muted">Estimated Arrival</p>
                <div className="flex items-baseline justify-center gap-2">
                  <span className="text-6xl font-black text-primary tracking-tighter">{yearsToGoal.toFixed(1)}</span>
                  <span className="text-xl font-bold text-secondary">Years</span>
                </div>
                <p className="text-xs font-bold text-accent uppercase tracking-widest pt-2 px-6 py-2 bg-accent/10 rounded-full">
                  At your current velocity
                </p>
              </motion.div>
            ) : (
              <div className="space-y-2">
                <p className="text-exit font-black uppercase text-[10px] tracking-widest">Goal Out of Range</p>
                <p className="text-muted text-xs font-medium">Increase SIP or XIRR expectations</p>
              </div>
            )}

            <div className="mt-8 w-full space-y-2">
              <div className="flex justify-between text-[9px] font-black uppercase text-muted tracking-widest px-1">
                <span>Progress</span>
                <span>{Math.min(100, (currentValue / goalAmount) * 100).toFixed(1)}%</span>
              </div>
              <div className="h-2 w-full bg-white/5 rounded-full overflow-hidden border border-white/5">
                <motion.div 
                  initial={{ width: 0 }}
                  animate={{ width: `${Math.min(100, (currentValue / goalAmount) * 100)}%` }}
                  className="h-full bg-accent shadow-[0_0_15px_rgba(129,140,248,0.5)]"
                />
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
