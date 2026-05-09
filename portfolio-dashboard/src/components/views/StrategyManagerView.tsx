import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { fetchStrategyTargets, updateStrategyTarget } from "@/services/api";
import { AlertTriangle, CheckCircle2, Loader2, ArrowRight } from "lucide-react";

const StrategyManagerView: React.FC<{ pan: string, schemes: any[] }> = ({ pan, schemes }) => {
  const [targets, setTargets] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState<{ type: 'success' | 'error' | null, message: string }>({ type: null, message: '' });

  useEffect(() => {
    loadTargets();
  }, [pan]);

  const loadTargets = async () => {
    try {
      const data = await fetchStrategyTargets(pan);
      // Merge current schemes with existing targets
      const merged = schemes.map(s => {
        const t = data.find((target: any) => target.amfiCode === s.amfiCode);
        return {
          ...s,
          targetAllocation: t ? t.targetAllocationPct : (s.allocationPercentage || 0),
          strategyType: t ? t.strategyType : 'CORE'
        };
      });
      setTargets(merged);
    } catch (err) {
      console.error("Failed to load targets", err);
    } finally {
      setLoading(false);
    }
  };

  const handleAllocationChange = (amfiCode: string, value: number) => {
    setTargets(prev => prev.map(t => 
      t.amfiCode === amfiCode ? { ...t, targetAllocation: value } : t
    ));
  };

  const totalAllocation = targets.reduce((sum, t) => sum + (t.targetAllocation || 0), 0);

  const handleSave = async (target: any) => {
    setSaving(true);
    try {
      await updateStrategyTarget({
        pan,
        amfiCode: target.amfiCode,
        allocation: target.targetAllocation,
        strategyType: target.strategyType
      });
      setStatus({ type: 'success', message: `Event emitted for ${target.schemeName}` });
      setTimeout(() => setStatus({ type: null, message: '' }), 3000);
    } catch (err: any) {
      setStatus({ type: 'error', message: err.message });
    } finally {
      setSaving(false);
    }
  };

  if (loading) return (
    <div className="flex flex-col items-center justify-center p-20 space-y-4">
      <Loader2 className="animate-spin text-accent" size={32} />
      <p className="text-[10px] font-black uppercase tracking-widest text-muted">Loading Architect...</p>
    </div>
  );

  if (!schemes || schemes.length === 0) return (
    <div className="flex flex-col items-center justify-center p-20 space-y-6 text-center border-2 border-dashed border-white/5 rounded-[40px]">
      <div className="w-16 h-16 rounded-full bg-white/5 flex items-center justify-center text-muted">
        <AlertTriangle size={32} />
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-black uppercase tracking-widest text-primary">No Holdings Identified</h3>
        <p className="text-[10px] font-bold text-muted uppercase tracking-widest max-w-sm">Please upload your CAS in the 'Data' tab to initialize your strategy architecture.</p>
      </div>
    </div>
  );

  return (
    <div className="max-w-6xl mx-auto space-y-10 pb-20 font-sans">
      <div className="flex flex-col space-y-2">
        <h2 className="text-3xl font-black tracking-tight text-white uppercase italic">Strategy Architect</h2>
        <p className="text-muted text-[10px] font-bold uppercase tracking-[0.3em]">
          Define your target state • Every change records a domain event
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">
        {/* Sidebar Stats */}
        <div className="space-y-6">
          <div className="bg-surface border border-white/5 rounded-3xl p-8 space-y-6">
             <div className="space-y-1">
                <p className="text-[10px] font-black text-muted uppercase tracking-widest">Total Committed</p>
                <p className={`text-4xl font-black ${totalAllocation > 100 ? 'text-exit' : 'text-buy'}`}>
                  {totalAllocation.toFixed(1)}%
                </p>
             </div>
             
             <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
                <motion.div 
                  initial={{ width: 0 }}
                  animate={{ width: `${Math.min(totalAllocation, 100)}%` }}
                  className={`h-full ${totalAllocation > 100 ? 'bg-exit' : 'bg-buy'} shadow-[0_0_15px_rgba(129,140,248,0.4)]`}
                />
             </div>

             {totalAllocation !== 100 && (
               <div className="flex items-center gap-2 p-3 bg-exit/5 border border-exit/10 rounded-xl text-exit">
                 <AlertTriangle size={14} />
                 <p className="text-[9px] font-bold uppercase tracking-wider">Allocation mismatch: {100 - totalAllocation}% remaining</p>
               </div>
             )}
          </div>

          <div className="p-6 bg-white/[0.01] border border-white/5 rounded-3xl space-y-4">
             <h4 className="text-[10px] font-black uppercase text-muted tracking-widest">System Health</h4>
             <div className="flex items-center gap-3">
                <div className="w-2 h-2 rounded-full bg-buy animate-pulse" />
                <p className="text-[10px] font-bold text-primary uppercase">Event Log Active</p>
             </div>
             <div className="flex items-center gap-3">
                <div className="w-2 h-2 rounded-full bg-buy" />
                <p className="text-[10px] font-bold text-primary uppercase">Projectors Syncing</p>
             </div>
          </div>
        </div>

        {/* Main List */}
        <div className="lg:col-span-3 space-y-4">
          <AnimatePresence>
            {targets.map((target, index) => (
              <motion.div
                key={target.amfiCode}
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: index * 0.05 }}
                className="bg-surface border border-white/5 p-6 rounded-3xl hover:border-white/10 transition-all group"
              >
                <div className="flex flex-col md:flex-row items-center gap-8">
                  <div className="flex-1 space-y-1">
                    <h4 className="text-sm font-black text-primary uppercase truncate max-w-[400px]">{target.schemeName}</h4>
                    <p className="text-[9px] font-bold text-muted uppercase tracking-[0.2em]">{target.amfiCode} • {target.strategyType}</p>
                  </div>

                  <div className="w-full md:w-64 space-y-3">
                    <div className="flex justify-between text-[10px] font-black uppercase tracking-widest text-muted">
                      <span>Target Weight</span>
                      <span className="text-accent">{target.targetAllocation}%</span>
                    </div>
                    <input 
                      type="range"
                      min="0"
                      max="100"
                      step="0.5"
                      value={target.targetAllocation}
                      onChange={(e) => handleAllocationChange(target.amfiCode, parseFloat(e.target.value))}
                      className="w-full h-1.5 bg-white/5 rounded-full appearance-none cursor-pointer accent-accent"
                    />
                  </div>

                  <button 
                    onClick={() => handleSave(target)}
                    disabled={saving}
                    className="p-4 bg-white/5 hover:bg-accent hover:text-primary border border-white/10 rounded-2xl transition-all"
                  >
                    <ArrowRight size={18} />
                  </button>
                </div>
              </motion.div>
            ))}
          </AnimatePresence>
        </div>
      </div>

      {/* Global Status Toast */}
      <AnimatePresence>
        {status.message && (
          <motion.div 
            initial={{ opacity: 0, y: 50 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 50 }}
            className={`fixed bottom-10 left-1/2 -translate-x-1/2 px-8 py-4 rounded-full border shadow-2xl flex items-center gap-3 ${
              status.type === 'success' ? 'bg-buy/10 border-buy/20 text-buy' : 'bg-exit/10 border-exit/20 text-exit'
            }`}
          >
            {status.type === 'success' ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}
            <p className="text-[10px] font-black uppercase tracking-[0.2em]">{status.message}</p>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
};

export default StrategyManagerView;
