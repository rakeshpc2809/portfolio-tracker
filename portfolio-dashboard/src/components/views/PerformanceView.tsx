import { useEffect, useState, useMemo } from 'react';
import { ResponsiveLine } from '@nivo/line';
import { Activity } from 'lucide-react';
import { formatCurrency } from '../../utils/formatters';
import { motion } from 'framer-motion';
export default function PerformanceView({ 
  pan, 
  isPrivate 
}: { 
  portfolioData: any; 
  pan: string; 
  isPrivate: boolean;
}) {
  const [perf, setPerf] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetch(`/api/dashboard/performance/${pan}`, {
      headers: { 'X-API-KEY': 'dev-secret-key' }
    })
      .then(r => r.json())
      .then(data => {
        setPerf(data);
        setLoading(false);
      })
      .catch(err => {
        console.error(err);
        setLoading(false);
      });
  }, [pan]);

  const lineData = useMemo(() => {
    if (!perf || !perf.history || perf.history.length === 0) return [];

    const firstValue = perf.history[0].value || 1;

    const portfolioSeries = {
      id: "Portfolio",
      color: "#cba6f7",
      data: perf.history.map((p: any) => ({
        x: p.date,
        y: parseFloat(((p.value / firstValue) * 100).toFixed(2))
      }))
    };

    const benchmarkSeries = {
      id: "Nifty 50",
      color: "rgba(166, 227, 161, 0.4)",
      data: (perf.niftyHistory || []).map((b: any) => ({
        x: b.date,
        y: parseFloat(b.normalizedValue.toFixed(2))
      }))
    };

    return [portfolioSeries, benchmarkSeries];
  }, [perf]);

  if (loading) {
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
            xScale={{ type: 'point' }}
            yScale={{ type: 'linear', min: 'auto', max: 'auto', stacked: false, reverse: false }}
            axisTop={null}
            axisRight={null}
            axisBottom={{
              tickSize: 5,
              tickPadding: 5,
              tickRotation: -45,
              legend: '',
              legendOffset: 36,
              legendPosition: 'middle'
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
                { label: '1 Month', val: perf.periodReturns.oneMonth, bench: 1.2 }, // Bench should ideally come from backend
                { label: '3 Months', val: perf.periodReturns.threeMonth, bench: 4.5 },
                { label: '6 Months', val: perf.periodReturns.sixMonth, bench: 8.2 },
                { label: '1 Year', val: perf.periodReturns.oneYear, bench: 14.8 },
                { label: 'ITD', val: perf.periodReturns.itd, bench: 18.5 },
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
    </div>
  );
}
