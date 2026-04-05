import React, { useState, useMemo } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { 
  TrendingUp, ShieldAlert, CheckCircle2, ChevronRight, 
  Info, ArrowRight, Wallet, Zap, Receipt,
  Snowflake, Clock
} from 'lucide-react';
import { 
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, 
  ResponsiveContainer, Legend 
} from 'recharts';
import { formatCurrency } from '../../utils/formatters';

interface TacticalSignal {
  schemeName: string;
  action: 'BUY' | 'EXIT' | 'HOLD';
  signalAmount: number;
  plannedPercentage: number;
  allocationPercentage: number;
  convictionScore: number;
  sortinoRatio: number;
  maxDrawdown: number;
  navPercentile3yr: number;
  drawdownFromAth: number;
  returnZScore: number;
  lastBuyDate: string; 
  justifications: string[];
}

interface TacticalPanelProps {
  signals: TacticalSignal[];
  totalPortfolioValue: number;
}

const TacticalPanel: React.FC<TacticalPanelProps> = ({ 
  signals = [],
  totalPortfolioValue = 0
}) => {
  const [sipAmount, setSipAmount] = useState(75000); 
  const [lumpsum, setLumpsum] = useState(0);
  const [selectedSchemeName, setSelectedSchemeName] = useState<string | null>(null);

  // 🚀 THE SCALING & SORTING ENGINE
  const { scaledSignals, totalWarChest, deployedCapital } = useMemo(() => {
    // 1. CONSTANT: Cash from Exits (from Backend)
    const exitProceeds = signals
      .filter(s => s.action === 'EXIT')
      .reduce((acc, s) => acc + (s.signalAmount || 0), 0);

    // 2. TOTAL: Live War Chest
    const currentWarChest = (sipAmount || 0) + (lumpsum || 0) + exitProceeds;

    // 3. REFERENCE: What did the backend originally want to buy?
    const apiTotalBuyRequest = signals
      .filter(s => s.action === 'BUY')
      .reduce((acc, s) => acc + (s.signalAmount || 0), 0);

    // 4. MAPPING: 
    const scaled = signals.map(s => {
      // 🚀 B. COOLDOWN LOGIC (21 days window)
      const lastBuy = new Date(s.lastBuyDate);
      const diffDays = (new Date().getTime() - lastBuy.getTime()) / (1000 * 3600 * 24);
      const isOnCooldown = diffDays < 21;

      // EXITS: Do not scale. Keep backend amount.
      if (s.action === 'EXIT') return { ...s, displayAmount: s.signalAmount, isOnCooldown }; 
      
      // HOLDS: Force to 0.
      if (s.action === 'HOLD') return { ...s, displayAmount: 0, isOnCooldown };

      // BUYS: These are the only ones that "breathe" with the slider
      if (s.action === 'BUY') {
        const originalBuyAmount = s.signalAmount || 0;
        const weight = originalBuyAmount / (apiTotalBuyRequest || 1); // Avoid division by zero
        
        // If on cooldown, effectively force amount to 0 for display
        const liveScaledAmount = isOnCooldown ? 0 : (weight * currentWarChest);
        
        return { ...s, displayAmount: liveScaledAmount, isOnCooldown };
      }
      return { ...s, displayAmount: 0, isOnCooldown };
    });

    // 5. SORT: Actionable (High Amount) -> Non-actionable
    const sorted = [...scaled].sort((a: any, b: any) => {
      if (a.action === 'HOLD' && b.action !== 'HOLD') return 1;
      if (b.action === 'HOLD' && a.action !== 'HOLD') return -1;
      if (a.isOnCooldown && !b.isOnCooldown) return 1;
      if (!a.isOnCooldown && b.isOnCooldown) return -1;
      return (b.displayAmount || 0) - (a.displayAmount || 0);
    });

    return { 
      scaledSignals: sorted, 
      totalWarChest: currentWarChest, 
      deployedCapital: currentWarChest 
    };
  }, [signals, sipAmount, lumpsum]);
  
  // 📊 Live Chart Simulation Data
  const chartData = useMemo(() => {
    return scaledSignals.map(s => {
      const amt = s.displayAmount || 0;
      const weightChange = totalPortfolioValue > 0 ? (amt / totalPortfolioValue) * 100 : 0;
      
      let simulated = s.allocationPercentage || 0;
      if (s.action === 'BUY') simulated += weightChange;
      if (s.action === 'EXIT') simulated -= weightChange;

      return {
        name: s.schemeName ? s.schemeName.split(' - ')[0].substring(0, 12) + '...' : 'Unknown',
        Actual: parseFloat((s.allocationPercentage || 0).toFixed(2)),
        Target: parseFloat((s.plannedPercentage || 0).toFixed(2)),
        Simulated: parseFloat(Math.max(0, simulated).toFixed(2)),
      };
    });
  }, [scaledSignals, totalPortfolioValue]);

  const activeScheme = scaledSignals.find(s => s.schemeName === selectedSchemeName);

  return (
    <div className="flex flex-col gap-6 p-4 max-w-7xl mx-auto">
      
      {/* 🎚️ SLIDERS */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-8 bg-zinc-900/80 backdrop-blur-md border border-zinc-800 p-6 rounded-2xl shadow-2xl">
        <div className="space-y-4">
          <div className="flex justify-between items-center">
            <label className="text-[10px] font-black uppercase text-zinc-500 tracking-widest">Monthly SIP Engine</label>
            <span className="text-sm font-black text-white">{formatCurrency(sipAmount)}</span>
          </div>
          <input 
            type="range" min="0" max="200000" step="5000" 
            value={sipAmount} 
            onChange={(e) => setSipAmount(parseInt(e.target.value) || 0)}
            className="w-full h-2 bg-zinc-800 rounded-lg appearance-none cursor-pointer accent-white"
          />
        </div>
        <div className="space-y-4">
          <div className="flex justify-between items-center">
            <label className="text-[10px] font-black uppercase text-zinc-500 tracking-widest">Tactical Lumpsum</label>
            <span className="text-sm font-black text-white">{formatCurrency(lumpsum)}</span>
          </div>
          <input 
            type="range" min="0" max="1000000" step="10000" 
            value={lumpsum} 
            onChange={(e) => setLumpsum(parseInt(e.target.value) || 0)}
            className="w-full h-2 bg-zinc-800 rounded-lg appearance-none cursor-pointer accent-white"
          />
        </div>
      </div>

      {/* 🏦 MACRO STATS */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-5 relative overflow-hidden group">
          <div className="absolute -top-4 -right-4 opacity-5 group-hover:opacity-10 transition-opacity"><Wallet size={80}/></div>
          <p className="text-[10px] font-black text-zinc-500 uppercase tracking-widest relative z-10">Total War Chest</p>
          <p className="text-2xl font-black text-white mt-1 relative z-10">{formatCurrency(totalWarChest)}</p>
        </div>
        <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-5 relative overflow-hidden group">
          <div className="absolute -top-4 -right-4 opacity-5 group-hover:opacity-10 transition-opacity text-emerald-500"><Zap size={80}/></div>
          <p className="text-[10px] font-black text-zinc-500 uppercase tracking-widest text-emerald-500/80 relative z-10">Deployed Capital</p>
          <p className="text-2xl font-black text-emerald-400 mt-1 relative z-10">{formatCurrency(deployedCapital)}</p>
        </div>
        <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-5 relative overflow-hidden group">
          <div className="absolute -top-4 -right-4 opacity-5 group-hover:opacity-10 transition-opacity text-amber-500"><Receipt size={80}/></div>
          <p className="text-[10px] font-black text-zinc-500 uppercase tracking-widest text-amber-500/80 relative z-10">Idle / Safety Margin</p>
          <p className="text-2xl font-black text-zinc-300 mt-1 relative z-10">{formatCurrency(Math.max(0, totalWarChest - deployedCapital))}</p>
        </div>
      </div>

      {/* 📊 CHART */}
      <div className="bg-zinc-950 border border-zinc-800 p-6 rounded-2xl">
        <div className="h-[300px] w-full">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke="#18181b" vertical={false} />
              <XAxis dataKey="name" stroke="#52525b" fontSize={9} axisLine={false} tickLine={false} />
              <YAxis stroke="#52525b" fontSize={9} axisLine={false} tickLine={false} unit="%" />
              <Tooltip cursor={{ fill: '#27272a', opacity: 0.4 }} contentStyle={{ backgroundColor: '#09090b', border: '1px solid #27272a', borderRadius: '8px' }} />
              <Legend wrapperStyle={{ fontSize: '10px', paddingTop: '15px', fontWeight: '900' }} />
              <Bar dataKey="Actual" fill="#3b82f6" radius={[4, 4, 0, 0]} barSize={14} />
              <Bar dataKey="Simulated" fill="#10b981" radius={[4, 4, 0, 0]} barSize={14} />
              <Bar dataKey="Target" fill="#3f3f46" radius={[4, 4, 0, 0]} barSize={14} fillOpacity={0.5} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* 📝 ROADMAP */}
      <div className="flex flex-col xl:flex-row gap-6">
        <div className="flex-1 space-y-2">
          <h3 className="text-[10px] font-black text-zinc-500 uppercase tracking-widest mb-4 flex items-center gap-2">
            <ArrowRight size={12}/> Execution Roadmap
          </h3>
          {scaledSignals.map((signal: any) => (
            <div 
              key={signal.schemeName}
              onClick={() => setSelectedSchemeName(signal.schemeName)}
              className={`flex items-center justify-between p-4 rounded-xl border transition-all cursor-pointer group
                ${selectedSchemeName === signal.schemeName ? 'bg-zinc-800 border-blue-500/50 shadow-lg' : 'bg-zinc-900 border-zinc-800 hover:border-zinc-700'}
                ${signal.isOnCooldown ? 'opacity-50 grayscale-[0.5]' : ''}`}
            >
              <div className="flex items-center gap-4">
                <div className={`p-2 rounded-lg ${
                  signal.action === 'BUY' ? 'bg-emerald-500/10 text-emerald-500' : 
                  signal.action === 'EXIT' ? 'bg-rose-500/10 text-rose-500' : 'bg-zinc-800 text-zinc-500'
                }`}>
                  {signal.isOnCooldown ? <Snowflake size={18}/> : (signal.action === 'BUY' ? <TrendingUp size={18}/> : <ShieldAlert size={18}/>)}
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <p className="text-xs font-black text-zinc-100 group-hover:text-white transition-colors">{signal.schemeName.split(' - ')[0]}</p>
                    {signal.isOnCooldown && <span className="text-[8px] font-black text-blue-400 uppercase tracking-tighter bg-blue-500/10 px-1 rounded">Cooldown</span>}
                  </div>
                  <p className="text-[9px] text-zinc-500 font-bold uppercase tracking-widest mt-0.5">SCORE: {signal.convictionScore}</p>
                </div>
              </div>
              <div className="flex items-center gap-4">
               <div className="text-right">
  <p className={`text-sm font-black ${
    signal.action === 'BUY' ? 'text-emerald-400' : 
    signal.action === 'EXIT' ? 'text-rose-400' : 'text-white'
  }`}>
    {signal.action === 'BUY' ? '+ ' : signal.action === 'EXIT' ? '- ' : ''}
    {signal.action === 'HOLD' ? '—' : formatCurrency(Math.abs(signal.displayAmount))}
  </p>
  <p className={`text-[9px] font-bold uppercase tracking-widest ${
    signal.action === 'BUY' ? 'text-emerald-500' : 
    signal.action === 'EXIT' ? 'text-rose-500' : 'text-zinc-500'
  }`}>
    {signal.action}
  </p>
</div>
                <ChevronRight size={16} className="text-zinc-700 group-hover:text-zinc-400" />
              </div>
            </div>
          ))}
        </div>

        {/* 🔍 SIDEBAR */}
        <div className="w-full xl:w-[400px]">
          <AnimatePresence mode="wait">
            {activeScheme ? (
              <motion.div 
                key={activeScheme.schemeName}
                initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}
                className="sticky top-6 p-6 bg-zinc-900 border border-zinc-800 rounded-2xl shadow-2xl"
              >
                <h4 className="text-[12px] font-black text-white uppercase tracking-widest leading-relaxed mb-6">{activeScheme.schemeName}</h4>
                <div className="flex items-center gap-3 mb-8">
                  <div className="px-3 py-1.5 bg-zinc-950 rounded border border-zinc-800">
                    <p className="text-[8px] text-zinc-500 font-black uppercase tracking-widest">Quality</p>
                    <p className="text-sm font-black text-blue-400">{(activeScheme.sortinoRatio || 0).toFixed(2)}</p>
                  </div>
                  <div className="px-3 py-1.5 bg-zinc-950 rounded border border-zinc-800">
                    <p className="text-[8px] text-zinc-500 font-black uppercase tracking-widest">Drawdown</p>
                    <p className="text-sm font-black text-rose-400">{Math.abs(activeScheme.maxDrawdown || 0).toFixed(1)}%</p>
                  </div>
                  <div className="px-3 py-1.5 bg-zinc-950 rounded border border-zinc-800">
                    <p className="text-[8px] text-zinc-500 font-black uppercase tracking-widest">NAV PCTL</p>
                    <p className="text-sm font-black text-amber-400">
                      {((activeScheme.navPercentile3yr || 0) * 100).toFixed(0)}%
                    </p>
                  </div>
                  <div className="px-3 py-1.5 bg-zinc-950 rounded border border-zinc-800">
                    <p className="text-[8px] text-zinc-500 font-black uppercase tracking-widest">Z-Score</p>
                    <p className={`text-sm font-black ${
                      (activeScheme.returnZScore || 0) < -1 ? 'text-emerald-400' : 
                      (activeScheme.returnZScore || 0) > 1 ? 'text-rose-400' : 'text-zinc-400'
                    }`}>
                      {(activeScheme.returnZScore || 0).toFixed(1)}
                    </p>
                  </div>
                </div>

                <div className="flex flex-wrap gap-2 mb-8">
                  <div className="flex items-center gap-1.5 text-[10px] text-zinc-400 font-bold bg-zinc-950 px-2 py-1 rounded border border-zinc-800">
                    <Clock size={10} />
                    Last Buy: {activeScheme.lastBuyDate !== '1970-01-01' ? new Date(activeScheme.lastBuyDate).toLocaleDateString() : 'Never'}
                  </div>
                  <div className="flex items-center gap-1.5 text-[10px] text-zinc-400 font-bold bg-zinc-950 px-2 py-1 rounded border border-zinc-800">
                    <TrendingUp size={10} />
                    ATH Drop: {(Math.abs(activeScheme.drawdownFromAth || 0) * 100).toFixed(1)}%
                  </div>
                </div>
                <div className="space-y-4">
                  {activeScheme.justifications?.map((j, i) => (
                    <div key={i} className="flex gap-3 items-start group">
                      <CheckCircle2 size={14} className="text-emerald-500 shrink-0 mt-0.5" />
                      <p className="text-[11px] text-zinc-300 leading-relaxed group-hover:text-white">{j}</p>
                    </div>
                  ))}
                </div>
              </motion.div>
            ) : (
              <div className="h-[300px] flex flex-col items-center justify-center border-2 border-dashed border-zinc-800 rounded-2xl text-zinc-600">
                <Info size={32} className="mb-3 opacity-20"/>
                <p className="text-[10px] font-black uppercase tracking-widest">Inspection Mode</p>
              </div>
            )}
          </AnimatePresence>
        </div>
      </div>
    </div>
  );
};

export default TacticalPanel;