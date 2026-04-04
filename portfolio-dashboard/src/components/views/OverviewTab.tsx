import { useState, useMemo } from 'react';
import { 
  ResponsiveContainer, XAxis, YAxis, Tooltip, 
  PieChart, Pie, Cell, BarChart, Bar, CartesianGrid
} from "recharts";
import { 
  Activity, Zap, PieChart as PieIcon,
  Wallet, TrendingUp, History, Fingerprint
} from "lucide-react";
import { formatCurrency } from "../../utils/formatters";

const BUCKET_COLORS: Record<string, string> = {
  AGGRESSIVE_GROWTH: "#3b82f6", 
  DEBT_SLAB_TAXED: "#71717a",    
  GOLD_HEDGE_24M: "#fbbf24",     
  SAFE_REBALANCER_EQUITY_TAX: "#10b981", 
  OTHER: "#27272a"
};

export default function OverviewTab({ data, portfolioSummary }: { data: any[], portfolioSummary: any }) {
  const [selectedIsin, setSelectedIsin] = useState<string | null>(data[0]?.isin || null);

  const selectedFund = useMemo(() => 
    data.find(s => s.isin === selectedIsin), [data, selectedIsin]
  );

  const bucketData = useMemo(() => {
    const groups: Record<string, number> = {};
    data.forEach(s => {
      const b = s.bucket || "OTHER";
      groups[b] = (groups[b] || 0) + (s.currentValue || 0);
    });
    return Object.keys(groups).map(key => ({ name: key, value: groups[key] }));
  }, [data]);

  const performanceData = useMemo(() => {
    return data
      .filter(s => (s.currentValue || 0) > 0)
      .map(s => ({
        name: s.schemeName.substring(0, 12) + '...',
        yield: parseFloat(s.xirr) || 0,
        allocation: s.currentValue
      }))
      .sort((a, b) => b.yield - a.yield);
  }, [data]);

  if (!portfolioSummary) return null;

  return (
    <div className="space-y-8 animate-in fade-in duration-700 pb-32">
      
      {/* 📟 HEADER HUD */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: "Portfolio Value", val: formatCurrency(portfolioSummary.currentValueAmount), color: "text-white" },
          { label: "System XIRR", val: portfolioSummary.overallXirr, color: (parseFloat(portfolioSummary.overallXirr) || 0) < 0 ? "text-rose-500" : "text-emerald-500" },
          { label: "Tax Liability (STCG)", val: formatCurrency(portfolioSummary.totalSTCG), color: "text-zinc-200" },
          { label: "Unrealized P&L", val: formatCurrency(portfolioSummary.totalUnrealizedGain), color: (portfolioSummary.totalUnrealizedGain || 0) < 0 ? "text-rose-500" : "text-emerald-500" },
        ].map((item, i) => (
          <div key={i} className="bg-zinc-900/40 border border-zinc-800/50 p-6 rounded-[2rem]">
            <p className="text-[9px] font-black text-zinc-500 uppercase tracking-widest mb-1">{item.label}</p>
            <p className={`text-xl font-black italic tracking-tighter ${item.color}`}>{item.val}</p>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
        {/* 🍰 STRATEGY WEIGHTS */}
        <div className="bg-zinc-900/20 border border-zinc-800/60 p-8 rounded-[3rem]">
          <h3 className="text-[10px] font-black text-zinc-400 uppercase tracking-[0.2em] mb-6 flex items-center gap-2">
            <PieIcon size={14}/> Strategy Distribution (%)
          </h3>
          <div className="h-64">
            <ResponsiveContainer>
              <PieChart>
                <Pie data={bucketData} innerRadius={60} outerRadius={90} paddingAngle={5} dataKey="value" stroke="none">
                  {bucketData.map((entry, index) => (
                    <Cell key={index} fill={BUCKET_COLORS[entry.name] || BUCKET_COLORS.OTHER} />
                  ))}
                </Pie>
                <Tooltip 
                   formatter={(value: any) => formatCurrency(Number(value))}
                   contentStyle={{backgroundColor: '#09090b', border: '1px solid #27272a', borderRadius: '12px'}}
                />
              </PieChart>
            </ResponsiveContainer>
          </div>
          <div className="mt-4 space-y-2">
             {bucketData.map((b, i) => (
               <div key={i} className="flex justify-between items-center text-[9px] font-black uppercase">
                  <span className="flex items-center gap-2 text-zinc-500 text-[8px]">
                    <div className="w-1.5 h-1.5 rounded-full" style={{backgroundColor: BUCKET_COLORS[b.name] || BUCKET_COLORS.OTHER}}/> {b.name}
                  </span>
                  <span className="text-zinc-300">{((b.value / (portfolioSummary.currentValueAmount || 1)) * 100).toFixed(1)}%</span>
               </div>
             ))}
          </div>
        </div>

        {/* 📊 YIELD VS ALLOCATION */}
        <div className="lg:col-span-2 bg-zinc-900/20 border border-zinc-800/60 p-8 rounded-[3rem]">
          <h3 className="text-[10px] font-black text-zinc-400 uppercase tracking-[0.2em] mb-6 flex items-center gap-2">
            <Activity size={14}/> Fund Yield Performance (XIRR %)
          </h3>
          <div className="h-80">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={performanceData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#18181b" vertical={false} />
                <XAxis dataKey="name" stroke="#3f3f46" fontSize={8} tickLine={false} axisLine={false} />
                <YAxis stroke="#3f3f46" fontSize={8} tickLine={false} axisLine={false} />
                <Tooltip 
                  cursor={{fill: '#ffffff05'}}
                  content={({active, payload}) => {
                    if (active && payload && payload.length) {
                      const val = Number(payload[0].value);
                      return (
                        <div className="bg-zinc-950 border border-zinc-800 p-3 rounded-xl shadow-2xl">
                          <p className="text-[10px] font-black text-white uppercase">{payload[0].payload.name}</p>
                          <p className={`text-xs font-black ${val < 0 ? 'text-rose-500' : 'text-emerald-500'}`}>Yield: {val}%</p>
                        </div>
                      )
                    }
                    return null;
                  }}
                />
                <Bar dataKey="yield" radius={[4, 4, 0, 0]} barSize={24}>
                  {performanceData.map((entry, index) => (
                    <Cell key={index} fill={entry.yield < 0 ? '#f43f5e' : '#10b981'} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>
      </div>

      {/* 🔍 DEEP INSPECTOR & LIST */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8 pt-8 border-t border-zinc-800">
         
         {/* 📁 High-Density Fund List */}
         <div className="space-y-3 max-h-[600px] overflow-y-auto pr-3 scrollbar-hide">
            <p className="text-[10px] font-black text-zinc-600 uppercase tracking-[0.3em] mb-4 pl-2">Asset Nodes</p>
            {data.map((fund) => {
              const isSelected = selectedIsin === fund.isin;
              const allocation = ((fund.currentValue / portfolioSummary.currentValueAmount) * 100).toFixed(1);
              const xirrVal = parseFloat(fund.xirr) || 0;

              return (
                <button
                  key={fund.isin}
                  onClick={() => setSelectedIsin(fund.isin)}
                  className={`w-full text-left px-5 py-4 rounded-2xl border transition-all relative overflow-hidden group ${
                    isSelected ? 'bg-white border-white shadow-xl' : 'bg-zinc-900/40 border-zinc-800 hover:border-zinc-600'
                  }`}
                >
                  <div className="flex justify-between items-start mb-2 relative z-10">
                    <div className="max-w-[65%]">
                      <span className={`text-[10px] font-black uppercase tracking-tighter block truncate ${isSelected ? 'text-black' : 'text-zinc-200'}`}>
                        {fund.schemeName}
                      </span>
                      <span className={`text-[8px] font-bold uppercase ${isSelected ? 'text-zinc-500' : 'text-zinc-600'}`}>
                        {fund.bucket?.replace(/_/g, ' ')}
                      </span>
                    </div>
                    <div className="text-right">
                       <span className={`text-[11px] font-black italic block ${isSelected ? 'text-black' : xirrVal < 0 ? 'text-rose-500' : 'text-emerald-500'}`}>
                        {fund.xirr}
                       </span>
                       <span className={`text-[7px] font-black uppercase ${isSelected ? 'text-zinc-400' : 'text-zinc-700'}`}>
                        XIRR
                       </span>
                    </div>
                  </div>

                  {/* Allocation Micro-Bar */}
                  <div className="relative z-10 flex items-center justify-between mt-3">
                    <div className={`h-1 flex-1 rounded-full mr-4 ${isSelected ? 'bg-zinc-200' : 'bg-zinc-800'}`}>
                       <div 
                        className={`h-full rounded-full ${isSelected ? 'bg-blue-600' : 'bg-blue-500/50'}`} 
                        style={{ width: `${allocation}%` }} 
                       />
                    </div>
                    <span className={`text-[9px] font-mono font-bold ${isSelected ? 'text-zinc-500' : 'text-zinc-400'}`}>
                      {allocation}%
                    </span>
                  </div>
                </button>
              );
            })}
         </div>

         {/* 🛰️ Deep Intelligence Panel */}
         <div className="lg:col-span-2 space-y-6">
            {selectedFund ? (
              <div className="bg-zinc-900/40 border border-zinc-800 p-10 rounded-[3rem] animate-in fade-in slide-in-from-right-4 duration-500">
                <div className="flex justify-between items-start mb-12">
                   <div className="space-y-2">
                      <div className="flex items-center gap-2">
                        <Fingerprint size={12} className="text-blue-500" />
                        <span className="text-[9px] font-black text-zinc-600 uppercase tracking-widest">{selectedFund.isin}</span>
                      </div>
                      <h2 className="text-2xl font-black text-white uppercase tracking-tighter leading-[1.1] max-w-lg italic">
                        {selectedFund.schemeName}
                      </h2>
                   </div>
                   <div className={`px-4 py-1.5 rounded-full border text-[9px] font-black uppercase tracking-widest ${selectedFund.status === 'ACTIVE' ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-500 shadow-[0_0_15px_rgba(16,185,129,0.1)]' : 'bg-rose-500/10 border-rose-500/20 text-rose-500'}`}>
                     {selectedFund.status}
                   </div>
                </div>

                <div className="grid grid-cols-2 lg:grid-cols-4 gap-8 mb-12">
                  <div className="space-y-1">
                    <p className="text-[9px] font-black text-zinc-500 uppercase tracking-widest flex items-center gap-2"><Wallet size={10}/> Invested</p>
                    <p className="text-lg font-black text-zinc-100">{formatCurrency(selectedFund.currentInvested)}</p>
                  </div>
                  <div className="space-y-1">
                    <p className="text-[9px] font-black text-zinc-500 uppercase tracking-widest flex items-center gap-2"><TrendingUp size={10}/> Current</p>
                    <p className="text-lg font-black text-white">{formatCurrency(selectedFund.currentValue)}</p>
                  </div>
                  <div className="space-y-1">
                    <p className="text-[9px] font-black text-zinc-500 uppercase tracking-widest flex items-center gap-2"><Zap size={10}/> Returns</p>
                    <p className={`text-lg font-black ${selectedFund.unrealizedGain < 0 ? 'text-rose-500' : 'text-emerald-500'}`}>{formatCurrency(selectedFund.unrealizedGain)}</p>
                  </div>
                  <div className="space-y-1">
                    <p className="text-[9px] font-black text-zinc-500 uppercase tracking-widest flex items-center gap-2"><History size={10}/> Nodes</p>
                    <p className="text-lg font-black text-blue-400">{selectedFund.transactionCount} <span className="text-[10px] text-zinc-600">TXs</span></p>
                  </div>
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                   <div className="p-6 bg-black/40 rounded-3xl border border-zinc-800 relative overflow-hidden group">
                      <div className="absolute top-0 right-0 p-4 opacity-5 group-hover:opacity-10 transition-opacity">
                        <Activity size={80} />
                      </div>
                      <p className="text-[10px] font-black text-zinc-500 uppercase tracking-widest mb-4">Risk Architecture</p>
                      <div className="space-y-3">
                         <div className="flex justify-between text-[11px] font-black border-b border-zinc-800/50 pb-2">
                            <span className="text-zinc-600">CATEGORY</span>
                            <span className="text-zinc-300 text-right w-40 truncate">{selectedFund.category}</span>
                         </div>
                         <div className="flex justify-between text-[11px] font-black border-b border-zinc-800/50 pb-2">
                            <span className="text-zinc-600">CONVICTION</span>
                            <span className="text-blue-500">STABLE_ALPHA</span>
                         </div>
                         <div className="flex justify-between text-[11px] font-black">
                            <span className="text-zinc-600">LIQUIDITY</span>
                            <span className="text-emerald-500 uppercase tracking-widest text-[9px]">High_Availability</span>
                         </div>
                      </div>
                   </div>

                   <div className="p-6 bg-zinc-900/50 rounded-3xl border border-zinc-800 flex flex-col justify-center">
                      <p className="text-[10px] font-black text-zinc-500 uppercase tracking-widest mb-2 text-center italic">Deployment Strategy</p>
                      <p className="text-[11px] text-zinc-400 font-bold leading-relaxed text-center">
                        This node belongs to the <span className="text-blue-400">{selectedFund.bucket?.replace(/_/g, ' ')}</span> framework. 
                        Targeting long-term capital appreciation with a focus on risk-adjusted quality metrics.
                      </p>
                   </div>
                </div>
              </div>
            ) : (
              <div className="h-full flex items-center justify-center border-2 border-dashed border-zinc-800 rounded-[3rem]">
                <p className="text-zinc-800 font-black uppercase tracking-[0.4em] text-xs">Awaiting Node Selection...</p>
              </div>
            )}
         </div>
      </div>
    </div>
  );
}