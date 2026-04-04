import { useState, useMemo } from 'react';
import { ShieldAlert, Info, Zap, Search, Filter, X } from "lucide-react";
import { CATEGORY_COLORS, getScoreColor, formatCurrency } from "../../utils/formatters";

export default function HoldingsTab({ data }: { data: any[] }) {
  const [search, setSearch] = useState("");
  const [filterAction, setFilterAction] = useState<string>("ALL");

  const filteredData = useMemo(() => {
    if (!data) return [];
    return data
      .filter(s => {
        const matchesSearch = s.schemeName.toLowerCase().includes(search.toLowerCase());
        const matchesAction = filterAction === "ALL" || s.action === filterAction;
        return matchesSearch && matchesAction;
      })
      .sort((a, b) => (b.convictionScore || 0) - (a.convictionScore || 0));
  }, [data, search, filterAction]);

  if (!data || data.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center p-20 border-2 border-dashed border-zinc-800 rounded-[2rem] bg-zinc-900/10">
        <Info size={40} className="text-zinc-700 mb-4 animate-pulse" />
        <p className="text-zinc-500 font-black uppercase tracking-[0.3em] text-xs">Awaiting Intelligence Feed...</p>
      </div>
    );
  }

  return (
    <div className="space-y-6 animate-in fade-in slide-in-from-bottom-2 duration-700">
      
      {/* 📡 GLOBAL COMMAND BAR */}
      <div className="flex flex-col md:flex-row gap-4 items-center justify-between px-6 py-4 bg-zinc-900/60 border border-zinc-800/50 rounded-3xl backdrop-blur-xl shadow-2xl">
        <div className="relative w-full md:w-96 group">
          <Search className="absolute left-4 top-1/2 -translate-y-1/2 text-zinc-500 group-focus-within:text-blue-500 transition-colors" size={16}/>
          <input 
            type="text"
            placeholder="Search Intelligence Matrix..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full bg-black/40 border border-zinc-800 rounded-2xl py-3 pl-12 pr-4 text-xs font-bold text-white placeholder:text-zinc-600 focus:outline-none focus:border-blue-500/50 focus:ring-1 focus:ring-blue-500/20 transition-all"
          />
          {search && (
            <button onClick={() => setSearch("")} className="absolute right-4 top-1/2 -translate-y-1/2 text-zinc-500 hover:text-white">
              <X size={14}/>
            </button>
          )}
        </div>

        <div className="flex items-center gap-2 overflow-x-auto pb-2 md:pb-0 w-full md:w-auto">
          <Filter size={14} className="text-zinc-500 mr-2 shrink-0"/>
          {["ALL", "BUY", "EXIT", "HOLD"].map((act) => (
            <button
              key={act}
              onClick={() => setFilterAction(act)}
              className={`px-4 py-2 rounded-xl text-[9px] font-black uppercase tracking-widest transition-all border ${
                filterAction === act 
                ? 'bg-blue-500/10 border-blue-500/50 text-blue-400 shadow-[0_0_15px_rgba(59,130,246,0.1)]' 
                : 'bg-zinc-900/40 border-zinc-800 text-zinc-500 hover:border-zinc-700 hover:text-zinc-300'
              }`}
            >
              {act === "EXIT" ? "HARVEST" : act === "BUY" ? "SCALE" : act}
            </button>
          ))}
        </div>
      </div>

      {/* 📊 MAIN GRID */}
      <div className="bg-zinc-900/20 border border-zinc-800/60 rounded-[2.5rem] overflow-hidden backdrop-blur-sm shadow-2xl">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-black/40 text-[9px] uppercase text-zinc-500 font-black tracking-[0.2em] border-b border-zinc-800">
              <th className="px-8 py-6">Asset Architecture</th>
              <th className="px-4 py-6 text-center">Quant Score</th>
              <th className="px-4 py-6 text-center">Efficiency (Sortino)</th>
              <th className="px-4 py-6 text-center text-rose-500">Risk Vector (MDD)</th>
              <th className="px-8 py-6 text-right">Capital Value</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-800/30">
            {filteredData.map((s: any, i: number) => {
              const scoreColor = getScoreColor(s.convictionScore);
              const isUnderweight = s.deviation < 0;

              return (
                <tr key={i} className="group hover:bg-white/[0.03] transition-all duration-300">
                  <td className="px-8 py-7">
                      <div className="flex flex-col gap-1.5">
                        <p className="text-sm font-black text-zinc-100 group-hover:text-white transition-colors tracking-tight italic">
                          {s.schemeName}
                        </p>
                        <div className="flex items-center gap-3">
                           <span 
                              className="px-2 py-0.5 rounded-[4px] text-[8px] font-black uppercase border tracking-widest bg-black/20" 
                              style={{
                                borderColor: `${CATEGORY_COLORS[s.cleanCategory]}44` || "#3f3f46", 
                                color: CATEGORY_COLORS[s.cleanCategory] || "#64748b"
                              }}
                            >
                              {s.cleanCategory}
                           </span>
                           {s.action === 'EXIT' && (
                             <div className="flex items-center gap-1 text-[8px] font-black text-rose-500 bg-rose-500/10 px-2 py-0.5 rounded-[4px] border border-rose-500/20">
                               <ShieldAlert size={10} strokeWidth={3}/> HARVEST
                             </div>
                           )}
                           {s.action === 'BUY' && (
                             <div className="flex items-center gap-1 text-[8px] font-black text-emerald-500 bg-emerald-500/10 px-2 py-0.5 rounded-[4px] border border-emerald-500/20">
                               <Zap size={10} strokeWidth={3}/> SCALE
                             </div>
                           )}
                        </div>
                      </div>
                  </td>

                  <td className="px-4 py-7 text-center">
                    <div className="relative inline-block">
                      <div className="absolute inset-0 blur-lg opacity-20" style={{ backgroundColor: scoreColor }} />
                      <span className="relative text-2xl font-black italic tracking-tighter" style={{ color: scoreColor }}>
                        {s.convictionScore}
                      </span>
                    </div>
                  </td>

                  <td className="px-4 py-7">
                    <div className="flex flex-col items-center gap-2">
                      <span className="text-[11px] font-black font-mono text-blue-400">
                        {s.sortinoRatio?.toFixed(2) || "0.00"}
                      </span>
                      <div className="w-16 h-1 bg-zinc-800 rounded-full overflow-hidden">
                        <div 
                          className="h-full bg-blue-500 shadow-[0_0_8px_rgba(59,130,246,0.5)]" 
                          style={{ width: `${Math.min(100, (s.sortinoRatio || 0) * 40)}%` }} 
                        />
                      </div>
                    </div>
                  </td>

                  <td className="px-4 py-7 text-center font-mono">
                    <div className="inline-flex flex-col items-center px-3 py-1 bg-rose-500/5 rounded-lg border border-rose-500/10">
                      <span className="text-[11px] font-black text-rose-500">
                        -{Math.abs(s.maxDrawdown || 0).toFixed(2)}%
                      </span>
                    </div>
                  </td>

                  <td className="px-8 py-7 text-right">
                      <p className="font-mono text-sm font-black text-white tracking-tighter">
                        {formatCurrency(s.currentValue)}
                      </p>
                      <div className="flex items-center justify-end gap-2 mt-1.5">
                        <p className="text-[9px] font-black text-zinc-500 uppercase tracking-widest">Drift</p>
                        <span className={`text-[10px] font-black font-mono ${isUnderweight ? 'text-rose-500' : 'text-emerald-500'}`}>
                          {isUnderweight ? '↓' : '↑'} {Math.abs(s.deviation)}%
                        </span>
                      </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {filteredData.length === 0 && (
          <div className="p-12 text-center text-[10px] font-black text-zinc-600 uppercase tracking-widest">
            Zero matches found for current filter criteria
          </div>
        )}
      </div>
    </div>
  );
}