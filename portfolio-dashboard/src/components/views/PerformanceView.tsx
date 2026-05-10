import { useState, useMemo } from 'react';
import { 
  ResponsiveContainer, ComposedChart, Line, Bar, XAxis, YAxis, Tooltip as RechartsTooltip, CartesianGrid,
  BarChart as RechartsBarChart, Bar as RechartsBar
} from 'recharts';
import { ResponsiveLine } from '@nivo/line';
import { ResponsiveHeatMap } from '@nivo/heatmap';
import { Activity, Target } from 'lucide-react';
import { formatCurrency } from '../../utils/formatters';
import { motion } from 'framer-motion';
import { Slider } from '../ui/slider';
import { usePerformanceHistory } from '../../hooks/usePerformance';
import { useQuery } from '@tanstack/react-query';
import { benchmarkService } from '../../services/api';
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
  const { data: benchmarkData } = useQuery({
    queryKey: ['benchmark', 'NIFTY 50'],
    queryFn: () => benchmarkService.getBenchmarkReturns("NIFTY 50"),
    staleTime: 1000 * 60 * 60, // 1 hour
  });

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

  const drawdownData = useMemo(() => {
    if (!perf || !perf.history || perf.history.length === 0) return [];
    let maxVal = 0;
    return [
      {
        id: "Drawdown",
        color: "#f38ba8",
        data: perf.history.map((p: any) => {
          const val = p.value;
          if (val > maxVal) maxVal = val;
          const dd = maxVal > 0 ? (val / maxVal) - 1 : 0;
          return {
            x: new Date(p.date),
            y: parseFloat((dd * 100).toFixed(2))
          };
        })
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

  const dailyReturns = useMemo(() => {
    if (!perf || !perf.history || perf.history.length < 2) return [];
    const rets = [];
    for (let i = 1; i < perf.history.length; i++) {
      const prevIdx = perf.history[i-1].value / (perf.history[i-1].invested || 1);
      const currIdx = perf.history[i].value / (perf.history[i].invested || 1);
      if (prevIdx > 0) {
        rets.push((currIdx - prevIdx) / prevIdx);
      }
    }
    return rets;
  }, [perf]);

  const { data: mcData } = useQuery({
    queryKey: ['monte-carlo', currentValue, monthlyAddition, yearsToGoal],
    queryFn: async () => {
      if (!yearsToGoal || dailyReturns.length === 0) return null;
      const res = await fetch('/parser/api/v1/quant/monte-carlo', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          daily_returns: dailyReturns,
          current_value: currentValue,
          monthly_sip: monthlyAddition,
          horizon_months: Math.ceil(yearsToGoal * 12),
          n_simulations: 1000
        })
      });
      if (!res.ok) throw new Error('MC failed');
      return res.json();
    },
    enabled: !!yearsToGoal && dailyReturns.length > 0,
    staleTime: 60000,
  });

  const { data: vintageData } = useQuery({
    queryKey: ['vintage', pan],
    queryFn: async () => {
      const res = await fetch(`/api/dashboard/vintage-returns/${pan}`);
      if (!res.ok) throw new Error('Failed to fetch vintage returns');
      return res.json();
    },
    staleTime: 1000 * 60 * 60,
  });

  const heatmapData = useMemo(() => {
    if (!vintageData) return [];
    const yearMap: Record<string, Record<string, number>> = {};
    Object.entries(vintageData).forEach(([ym, val]) => {
      const [y, m] = ym.split('-');
      if (!yearMap[y]) yearMap[y] = {};
      yearMap[y][m] = val as number;
    });

    const months = ["01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"];
    const monthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

    return Object.keys(yearMap).sort((a,b) => parseInt(b) - parseInt(a)).map(year => ({
      id: year,
      data: months.map((m, i) => ({
        x: monthNames[i],
        y: yearMap[year][m] !== undefined ? parseFloat((yearMap[year][m]).toFixed(1)) : null
      }))
    }));
  }, [vintageData]);

  const rollingRiskData = useMemo(() => {
    if (!perf || !perf.history || perf.history.length < 90) return [];
    const windowSize = 90;
    const data = [];
    
    for (let i = windowSize; i < perf.history.length; i++) {
      const windowRets = dailyReturns.slice(i - windowSize, i);
      if (windowRets.length === 0) continue;
      
      const meanRet = windowRets.reduce((a,b) => a+b, 0) / windowRets.length;
      const downRets = windowRets.filter(r => r < 0);
      const downsideDev = Math.sqrt(downRets.reduce((a,b) => a + b*b, 0) / windowRets.length);
      const sortino = downsideDev > 0 ? (meanRet / downsideDev) * Math.sqrt(252) : 0;
      
      let peak = 0;
      let maxDD = 0;
      for (let j = i - windowSize; j <= i; j++) {
        const val = perf.history[j].value;
        if (val > peak) peak = val;
        const dd = peak > 0 ? (val / peak) - 1 : 0;
        if (dd < maxDD) maxDD = dd;
      }
      
      data.push({
        date: perf.history[i].date,
        sortino: parseFloat(sortino.toFixed(2)),
        maxDrawdown: parseFloat((Math.abs(maxDD) * 100).toFixed(2))
      });
    }
    return data.filter((_, idx) => idx % 5 === 0);
  }, [perf, dailyReturns]);

  const distributionData = useMemo(() => {
    if (!dailyReturns || dailyReturns.length === 0) return [];
    const pctRets = dailyReturns.map(r => r * 100);
    const min = Math.floor(Math.min(...pctRets));
    const max = Math.ceil(Math.max(...pctRets));
    const bins = 40;
    const step = (max - min) / bins;
    
    const histogram = Array.from({length: bins}, (_, i) => ({
      bin: `${(min + i * step).toFixed(1)}%`,
      val: min + i * step,
      count: 0
    }));
    
    pctRets.forEach(r => {
      let binIdx = Math.floor((r - min) / step);
      if (binIdx >= bins) binIdx = bins - 1;
      if (binIdx < 0) binIdx = 0;
      histogram[binIdx].count += 1;
    });
    
    return histogram;
  }, [dailyReturns]);

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

      {/* Underwater / Drawdown Chart */}
      <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl space-y-8">
        <div className="flex items-center justify-between">
          <div className="space-y-1">
            <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Underwater Drawdown</h3>
            <p className="text-sm font-bold text-secondary">Historical depth and duration of portfolio drawdowns from peak</p>
          </div>
        </div>

        <div className="h-64 w-full bg-black/20 rounded-3xl border border-white/5 p-6 shadow-inner">
          <ResponsiveLine
            data={drawdownData}
            margin={{ top: 10, right: 20, bottom: 30, left: 50 }}
            xScale={{ type: 'time', format: 'native' }}
            xFormat="time:%Y-%m-%d"
            yScale={{ type: 'linear', min: 'auto', max: 0, stacked: false, reverse: false }}
            axisTop={null}
            axisRight={null}
            axisBottom={{
              format: '%b %Y',
              tickValues: 'every 6 months',
              tickSize: 5,
              tickPadding: 5,
              tickRotation: 0,
            }}
            axisLeft={{
              tickSize: 5,
              tickPadding: 5,
              tickRotation: 0,
              format: v => `${v}%`,
            }}
            enableGridX={false}
            colors={d => d.color}
            lineWidth={2}
            enablePoints={false}
            enableArea={true}
            areaOpacity={0.2}
            useMesh={true}
            theme={{
              axis: {
                ticks: { text: { fill: "#6c7086", fontSize: 10, fontWeight: 700 } },
              },
              grid: { line: { stroke: "rgba(255,255,255,0.05)" } },
              tooltip: { container: { background: "#181825", color: "#cdd6f4", fontSize: 12, borderRadius: 12 } }
            }}
            tooltip={({ point }) => (
              <div className="bg-surface-overlay/95 backdrop-blur-2xl border border-white/10 p-3 rounded-xl shadow-2xl">
                <p className="text-[10px] font-black text-muted uppercase tracking-widest mb-1">{point.data.x.toString()}</p>
                <p className="text-sm font-black text-exit">Drawdown: {point.data.yFormatted}%</p>
              </div>
            )}
          />
        </div>
      </section>

      {/* Cohort Heatmap Chart */}
      {heatmapData.length > 0 && (
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl space-y-8">
          <div className="flex items-center justify-between">
            <div className="space-y-1">
              <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Rolling Return by Vintage</h3>
              <p className="text-sm font-bold text-secondary">Profitability of active investments grouped by the month they were purchased</p>
            </div>
          </div>

          <div className="h-80 w-full bg-black/20 rounded-3xl border border-white/5 p-6 shadow-inner">
            <ResponsiveHeatMap
              data={heatmapData}
              margin={{ top: 20, right: 20, bottom: 20, left: 60 }}
              valueFormat=">-.1f"
              axisTop={null}
              axisBottom={{
                tickSize: 5,
                tickPadding: 5,
                tickRotation: 0,
              }}
              axisLeft={{
                tickSize: 5,
                tickPadding: 5,
                tickRotation: 0,
              }}
              colors={{
                type: 'diverging',
                scheme: 'red_yellow_green',
                minValue: -20,
                maxValue: 40,
                divergeAt: 0
              }}
              emptyColor="rgba(255,255,255,0.02)"
              borderWidth={1}
              borderColor="rgba(0,0,0,0.5)"
              enableLabels={true}
              labelTextColor="#ffffff"
              theme={{
                axis: {
                  ticks: { text: { fill: "#6c7086", fontSize: 10, fontWeight: 700 } },
                },
                tooltip: { container: { background: "#181825", color: "#cdd6f4", fontSize: 12, borderRadius: 12 } }
              }}
              tooltip={({ cell }) => (
                <div className="bg-surface-overlay/95 backdrop-blur-2xl border border-white/10 p-3 rounded-xl shadow-2xl">
                  <p className="text-[10px] font-black text-muted uppercase tracking-widest mb-1">{cell.serieId} {cell.data.x}</p>
                  <p className={`text-sm font-black ${cell.value !== null && cell.value >= 0 ? 'text-buy' : 'text-exit'}`}>
                    {cell.value !== null ? `${cell.value}%` : 'No SIP'}
                  </p>
                </div>
              )}
            />
          </div>
        </section>
      )}

      {/* Return Distribution Histogram */}
      {distributionData.length > 0 && (
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl space-y-8">
          <div className="flex items-center justify-between">
            <div className="space-y-1">
              <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Return Distribution</h3>
              <p className="text-sm font-bold text-secondary">Histogram of pseudo-daily portfolio returns</p>
            </div>
          </div>
          <div className="h-80 w-full bg-black/20 rounded-3xl border border-white/5 p-6 shadow-inner">
            <ResponsiveContainer width="100%" height="100%">
              <RechartsBarChart data={distributionData} margin={{ top: 10, right: 10, bottom: 20, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" vertical={false} />
                <XAxis dataKey="bin" tick={{ fill: '#6c7086', fontSize: 10, fontWeight: 700 }} axisLine={false} tickLine={false} minTickGap={20} />
                <YAxis tick={{ fill: '#6c7086', fontSize: 10, fontWeight: 700 }} axisLine={false} tickLine={false} />
                <RechartsTooltip 
                  cursor={{ fill: 'rgba(255,255,255,0.05)' }}
                  content={({ active, payload }) => {
                    if (!active || !payload?.length) return null;
                    return (
                      <div className="bg-surface-overlay/95 backdrop-blur-2xl border border-white/10 p-3 rounded-xl shadow-2xl">
                        <p className="text-[10px] font-black text-muted uppercase tracking-widest mb-1">{payload[0].payload.bin}</p>
                        <p className="text-sm font-black text-primary">Count: {payload[0].value} days</p>
                      </div>
                    );
                  }}
                />
                <RechartsBar dataKey="count" fill="#89b4fa" radius={[4, 4, 0, 0]} />
              </RechartsBarChart>
            </ResponsiveContainer>
          </div>
        </section>
      )}

      {/* Rolling Risk Dual Axis Chart */}
      {rollingRiskData.length > 0 && (
        <section className="bg-surface/40 backdrop-blur-xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl space-y-8">
          <div className="flex items-center justify-between">
            <div className="space-y-1">
              <h3 className="text-primary text-[10px] font-black uppercase tracking-[0.3em]">Rolling Risk Metrics</h3>
              <p className="text-sm font-bold text-secondary">90-Day Rolling Sortino Ratio vs Max Drawdown</p>
            </div>
            <div className="flex gap-4">
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full bg-[#f38ba8]" />
                <span className="text-[10px] font-bold text-muted uppercase">Max DD %</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-3 h-1 rounded-full bg-[#a6e3a1]" />
                <span className="text-[10px] font-bold text-muted uppercase">Sortino</span>
              </div>
            </div>
          </div>
          <div className="h-80 w-full bg-black/20 rounded-3xl border border-white/5 p-6 shadow-inner">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={rollingRiskData} margin={{ top: 10, right: 10, bottom: 20, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" vertical={false} />
                <XAxis dataKey="date" tick={{ fill: '#6c7086', fontSize: 10, fontWeight: 700 }} axisLine={false} tickLine={false} tickFormatter={v => v.substring(0,7)} minTickGap={30} />
                <YAxis yAxisId="left" orientation="left" tick={{ fill: '#f38ba8', fontSize: 10, fontWeight: 700 }} axisLine={false} tickLine={false} />
                <YAxis yAxisId="right" orientation="right" tick={{ fill: '#a6e3a1', fontSize: 10, fontWeight: 700 }} axisLine={false} tickLine={false} />
                <RechartsTooltip 
                  cursor={{ fill: 'rgba(255,255,255,0.05)' }}
                  content={({ active, payload }) => {
                    if (!active || !payload?.length) return null;
                    return (
                      <div className="bg-surface-overlay/95 backdrop-blur-2xl border border-white/10 p-3 rounded-xl shadow-2xl space-y-1">
                        <p className="text-[10px] font-black text-muted uppercase tracking-widest mb-1">{payload[0].payload.date}</p>
                        <p className="text-xs font-black text-exit">Drawdown: -{payload[0].payload.maxDrawdown}%</p>
                        <p className="text-xs font-black text-buy">Sortino: {payload[0].payload.sortino}</p>
                      </div>
                    );
                  }}
                />
                <Bar yAxisId="left" dataKey="maxDrawdown" fill="#f38ba8" fillOpacity={0.5} radius={[4, 4, 0, 0]} />
                <Line yAxisId="right" type="monotone" dataKey="sortino" stroke="#a6e3a1" strokeWidth={3} dot={false} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        </section>
      )}

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
                { label: '1 Month', val: perf.periodReturns.oneMonth, bench: benchmarkData?.oneMonth ?? 0 }, 
                { label: '3 Months', val: perf.periodReturns.threeMonth, bench: benchmarkData?.threeMonth ?? 0 },
                { label: '6 Months', val: perf.periodReturns.sixMonth, bench: benchmarkData?.sixMonth ?? 0 },
                { label: '1 Year', val: perf.periodReturns.oneYear, bench: benchmarkData?.oneYear ?? 0 },
                { label: '3 Years', val: perf.periodReturns.threeYear, bench: benchmarkData?.threeYear ?? 0 },
                { label: 'ITD', val: perf.periodReturns.itd, bench: benchmarkData?.itd ?? 0 },
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

            {mcData && (
              <div className="mt-6 p-4 bg-white/5 rounded-2xl w-full text-left space-y-2 border border-white/5">
                <p className="text-[10px] font-black text-muted uppercase tracking-widest text-center mb-4">Monte Carlo Simulation (1000 Paths)</p>
                <div className="flex justify-between items-center text-xs">
                  <span className="font-bold text-hint">90% Best Case (p90)</span>
                  <span className="font-black text-buy">{formatCurrency(mcData.p90)}</span>
                </div>
                <div className="flex justify-between items-center text-xs">
                  <span className="font-bold text-hint">Expected (p50)</span>
                  <span className="font-black text-primary">{formatCurrency(mcData.p50)}</span>
                </div>
                <div className="flex justify-between items-center text-xs">
                  <span className="font-bold text-hint">10% Worst Case (p10)</span>
                  <span className="font-black text-exit">{formatCurrency(mcData.p10)}</span>
                </div>
              </div>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
