import { useMemo } from 'react';
import { 
  BarChart, Bar, XAxis, YAxis, CartesianGrid, ResponsiveContainer, 
  Tooltip as RechartsTooltip, ReferenceLine, Cell 
} from "recharts";
import { ArrowRightLeft, Target, AlertCircle, CheckCircle2, ChevronRight } from "lucide-react";

export default function DeviationTab({ data }: { data: any[] }) {
  
  const { chartData, totalDriftMagnitude } = useMemo(() => {
    if (!data || data.length === 0) return { chartData: [], totalDriftMagnitude: "0.00" };

    const processed = data
      .map(s => {
        const actual = parseFloat(s.actualPercentage) || 0;
        const target = parseFloat(s.plannedPercentage) || 0;
        
        // 🚀 THE DRIFT FORMULA
        // Positive Result = Overweight (Too much)
        // Negative Result = Underweight (Too little)
        const rawDrift = actual - target;

        return {
          ...s,
          // For the Chart:
          // We want UNDERWEIGHT to be a positive bar (Green, needs inflow)
          // We want OVERWEIGHT to be a negative bar (Red, needs outflow)
          displayDrift: Number((rawDrift * -1).toFixed(2)), 
          absDrift: Math.abs(rawDrift),
          actual: actual,
          target: target
        };
      })
      // Only show funds that actually have a drift (ignore perfect 0s)
      .filter(s => s.absDrift > 0.01)
      // Sort by the biggest absolute problems first
      .sort((a, b) => b.absDrift - a.absDrift);

    const total = processed.reduce((acc, curr) => acc + curr.absDrift, 0).toFixed(2);
    
    return { chartData: processed, totalDriftMagnitude: total };
  }, [data]);

  return (
    <div className="space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-1000">
       
       {/* 📟 COMMAND HUD */}
       <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="bg-zinc-900/40 border border-zinc-800/50 p-6 rounded-3xl backdrop-blur-md relative overflow-hidden group">
             <div className="absolute -right-2 -top-2 opacity-5 group-hover:opacity-10 transition-opacity text-blue-500">
                <Target size={80}/>
             </div>
             <p className="text-[9px] font-black text-zinc-500 uppercase tracking-widest mb-1 flex items-center gap-2">
                <Target size={12} className="text-blue-500"/> Cumulative Drift Index
             </p>
             <p className="text-2xl font-black text-white italic tracking-tighter">{totalDriftMagnitude}%</p>
             <p className="text-[9px] text-zinc-600 font-bold mt-1 uppercase tracking-tighter italic opacity-60">Variance Tension</p>
          </div>

          <div className="bg-zinc-900/40 border border-zinc-800/50 p-6 rounded-3xl backdrop-blur-md flex items-center gap-4">
             <div className={`p-3 rounded-2xl ${parseFloat(totalDriftMagnitude) > 10 ? 'bg-rose-500/10 text-rose-500' : 'bg-emerald-500/10 text-emerald-500'}`}>
                {parseFloat(totalDriftMagnitude) > 10 ? <AlertCircle size={20}/> : <CheckCircle2 size={20}/>}
             </div>
             <div>
                <p className="text-[9px] font-black text-zinc-500 uppercase tracking-widest">System Status</p>
                <p className="text-xs font-black text-zinc-200 mt-0.5 uppercase tracking-tighter italic">
                   {parseFloat(totalDriftMagnitude) > 10 ? 'Structural Realignment Required' : 'Optimal Trajectory'}
                </p>
             </div>
          </div>

          <div className="bg-zinc-900/40 border border-zinc-800/50 p-6 rounded-3xl backdrop-blur-md flex items-center justify-between group">
             <div>
                <p className="text-[9px] font-black text-zinc-500 uppercase tracking-widest">Critical Drift Fund</p>
                <p className="text-xs font-black text-blue-400 mt-0.5 truncate w-32 italic uppercase tracking-tighter">
                   {chartData[0]?.shortName || 'Analyzing...'}
                </p>
             </div>
             <ChevronRight className="text-zinc-800 group-hover:text-zinc-400 transition-colors" />
          </div>
       </div>

       {/* 📊 ACTIONABLE DEVIATION MATRIX */}
       <div className="bg-zinc-900/20 border border-zinc-800/60 p-8 rounded-[3rem] shadow-2xl backdrop-blur-sm relative overflow-hidden">
          <div className="flex justify-between items-start mb-10">
             <div>
                <h3 className="text-[10px] font-black text-zinc-400 uppercase tracking-[0.3em] flex items-center gap-2">
                   <ArrowRightLeft size={14} className="text-blue-500"/> Allocation Alignment Matrix
                </h3>
                <p className="text-[8px] text-zinc-600 font-black uppercase mt-1 tracking-widest italic">
                   RHS (Green) = Scalable Inflow (Underweight) | LHS (Red) = Target Exhaustion (Overweight)
                </p>
             </div>
             <div className="px-4 py-1.5 bg-black/40 border border-zinc-800 rounded-full flex gap-4">
                <div className="flex items-center gap-2">
                   <div className="w-1.5 h-1.5 rounded-full bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.4)]"/>
                   <span className="text-[8px] font-black text-zinc-500 uppercase">Under</span>
                </div>
                <div className="flex items-center gap-2">
                   <div className="w-1.5 h-1.5 rounded-full bg-rose-500 shadow-[0_0_8px_rgba(244,63,94,0.4)]"/>
                   <span className="text-[8px] font-black text-zinc-500 uppercase">Over</span>
                </div>
             </div>
          </div>
          
          <div className="h-[600px]">
             <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chartData} layout="vertical" margin={{left: 10, right: 30}}>
                   <CartesianGrid strokeDasharray="3 3" stroke="#18181b" horizontal={false} />
                   <XAxis type="number" hide domain={['auto', 'auto']} />
                   <YAxis 
                      dataKey="shortName" 
                      type="category" 
                      width={160} 
                      fontSize={9} 
                      stroke="#52525b" 
                      axisLine={false} 
                      tickLine={false}
                      tick={(props) => (
                        <text {...props} className="font-black italic fill-zinc-500 text-[9px] uppercase tracking-tighter">
                          {props.payload.value}
                        </text>
                      )}
                   />
                   <RechartsTooltip 
                      cursor={{fill: '#27272a', opacity: 0.3}}
                      content={({ active, payload }) => {
                        if (active && payload && payload.length) {
                           const d = payload[0].payload;
                           return (
                              <div className="bg-zinc-950 border border-zinc-800 p-4 rounded-xl shadow-2xl backdrop-blur-lg">
                                 <p className="text-[9px] font-black text-white mb-3 uppercase tracking-widest border-b border-zinc-800 pb-2">{d.schemeName}</p>
                                 <div className="grid grid-cols-2 gap-4">
                                    <div>
                                       <p className="text-[8px] font-black text-zinc-500 uppercase tracking-tighter">Current</p>
                                       <p className="text-xs font-black text-zinc-200">{d.actual.toFixed(2)}%</p>
                                    </div>
                                    <div>
                                       <p className="text-[8px] font-black text-zinc-500 uppercase tracking-tighter">Target</p>
                                       <p className={`text-xs font-black ${d.target === 0 ? 'text-rose-500' : 'text-blue-400'}`}>
                                          {d.target === 0 ? '0.00% (EXIT)' : `${d.target.toFixed(2)}%`}
                                       </p>
                                    </div>
                                 </div>
                                 <div className="mt-3 pt-3 border-t border-zinc-800">
                                    <p className="text-[8px] font-black text-zinc-500 uppercase tracking-widest">Alignment Vector</p>
                                    <p className={`text-sm font-black ${d.displayDrift > 0 ? 'text-emerald-500' : 'text-rose-500'}`}>
                                       {d.displayDrift > 0 ? 'BUY' : 'SELL'} {Math.abs(d.displayDrift).toFixed(2)}%
                                    </p>
                                 </div>
                              </div>
                           );
                        }
                        return null;
                      }}
                   />
                   <ReferenceLine x={0} stroke="#3f3f46" strokeWidth={2} label={{ 
                      value: 'EQUILIBRIUM', position: 'top', fill: '#3f3f46', fontSize: 7, fontWeight: '900', letterSpacing: '0.2em'
                   }} />
                   <Bar dataKey="displayDrift" barSize={14}>
                      {chartData.map((entry, index) => (
                        <Cell 
                           key={`cell-${index}`} 
                           // Green = Underweight (Positive bar to the right)
                           // Red = Overweight (Negative bar to the left)
                           fill={entry.displayDrift > 0 ? '#10b981' : '#f43f5e'} 
                           fillOpacity={0.8}
                           className="hover:fill-opacity-100 transition-all duration-300"
                        />
                      ))}
                   </Bar>
                </BarChart>
             </ResponsiveContainer>
          </div>
       </div>

       {/* 🧩 FOOTER STRATEGY */}
       <div className="bg-zinc-900/30 border border-zinc-800 p-6 rounded-[2.5rem] flex items-center gap-6 backdrop-blur-sm">
          <div className="p-4 bg-zinc-950 border border-zinc-800 rounded-2xl shadow-inner">
             <ArrowRightLeft className="text-blue-500" size={24}/>
          </div>
          <div className="flex-1">
             <h4 className="text-[9px] font-black text-zinc-400 uppercase tracking-[0.2em] mb-1 italic">Realignment Intelligence</h4>
             <p className="text-[11px] text-zinc-500 leading-relaxed font-bold">
                Total variance is <span className="text-white font-black italic">{totalDriftMagnitude}%</span>. 
                Focus on <span className="text-rose-500 font-black">RED</span> bars to unlock tax-free capital and redeploy into <span className="text-emerald-500 font-black">GREEN</span> zones.
             </p>
          </div>
       </div>
    </div>
  );
}