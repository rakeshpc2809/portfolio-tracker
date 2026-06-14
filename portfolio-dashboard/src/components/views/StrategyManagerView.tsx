import React, { useState, useEffect } from 'react';
import { fetchStrategyTargets, updateStrategyTarget } from "@/services/api";
import { AlertTriangle, CheckCircle2, Loader2 } from "lucide-react";

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
          strategyType: t && t.strategyType && t.strategyType !== 'PROPORTIONAL' ? t.strategyType : 'CORE'
        };
      });
      setTargets(merged);
    } catch (err) {
      console.error("Failed to load targets", err);
    } finally {
      setLoading(false);
    }
  };

  const handleAllocationChange = (amfiCode: string, value: any) => {
    setTargets(prev => prev.map(t => 
      t.amfiCode === amfiCode ? { ...t, targetAllocation: value } : t
    ));
  };

  const handleStrategyTypeChange = (amfiCode: string, value: string) => {
    setTargets(prev => prev.map(t => 
      t.amfiCode === amfiCode ? { ...t, strategyType: value } : t
    ));
  };

  const totalAllocation = targets.reduce((sum, t) => sum + (parseFloat(t.targetAllocation) || 0), 0);

  const handleSave = async (target: any) => {
    setSaving(true);
    try {
      await updateStrategyTarget({
        pan,
        amfiCode: target.amfiCode,
        allocation: parseFloat(target.targetAllocation) || 0,
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
    <div className="space-y-6 pb-20 font-sans text-slate-100">
      
      {/* Compact Header Widget */}
      <div className="bg-[#181825]/50 border border-white/5 rounded-2xl p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-4 shadow-md">
        <div className="space-y-1">
          <p className="text-[9px] font-black text-slate-400 uppercase tracking-widest">Total Committed Target Weight</p>
          <p className={`text-2xl font-black tabular-nums ${totalAllocation > 100 ? 'text-exit' : 'text-buy'}`}>
            {totalAllocation.toFixed(1)}% / 100%
          </p>
        </div>
        
        <div className="flex-1 max-w-xs space-y-1.5 w-full">
          <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden border border-white/10">
            <div 
              style={{width: `${Math.min(totalAllocation, 100)}%` }}
              className={`h-full ${totalAllocation > 100 ? 'bg-exit' : 'bg-buy'} shadow-[0_0_10px_rgba(203,166,247,0.3)] transition-all`}
            />
          </div>
          {totalAllocation !== 100 && (
            <div className="flex items-center gap-1.5 text-exit">
              <AlertTriangle size={10} />
              <p className="text-[8px] font-bold uppercase tracking-wider">
                Mismatch: {Math.abs(100 - totalAllocation).toFixed(1)}% {totalAllocation > 100 ? 'over' : 'remaining'}
              </p>
            </div>
          )}
        </div>
      </div>

      {/* Funds List */}
      <div className="space-y-3">
        {targets.map((target) => (
          <div
            key={target.amfiCode}
            className="bg-[#181825]/30 border border-white/5 p-4 rounded-2xl hover:border-white/10 transition-all space-y-3"
          >
            {/* Header: Fund Name & AMFI code */}
            <div className="flex justify-between items-start gap-2">
              <div className="flex-1 min-w-0">
                <h4 className="text-xs font-black text-slate-200 uppercase truncate" title={target.schemeName}>
                  {target.schemeName}
                </h4>
                <div className="flex items-center flex-wrap gap-2 mt-0.5">
                  <span className="text-[8px] font-bold text-slate-500 uppercase tracking-widest">
                    AMFI: {target.amfiCode}
                  </span>
                  <span className="text-slate-700 text-[8px]">•</span>
                  <div className="flex items-center gap-1">
                    <span className="text-[8px] font-bold text-slate-500 uppercase tracking-widest">Type:</span>
                    <select
                      value={target.strategyType || "CORE"}
                      onChange={(e) => handleStrategyTypeChange(target.amfiCode, e.target.value)}
                      className="bg-[#181825]/80 border border-white/10 rounded px-1.5 py-0.5 font-bold text-accent text-[8px] uppercase tracking-wider focus:border-accent focus:outline-none cursor-pointer"
                    >
                      <option value="CORE" className="bg-[#11111b] text-primary">CORE</option>
                      <option value="SATELLITE" className="bg-[#11111b] text-primary">SATELLITE</option>
                      <option value="TACTICAL" className="bg-[#11111b] text-primary">TACTICAL</option>
                      <option value="DROPPED" className="bg-[#11111b] text-primary">DROPPED</option>
                      <option value="EXIT" className="bg-[#11111b] text-primary">EXIT</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>

            {/* Target Weight Adjustment & Save Button */}
            <div className="flex items-center gap-4 justify-between">
              {/* Slider */}
              <div className="flex-1">
                <input 
                  type="range"
                  min="0"
                  max="100"
                  step="0.5"
                  value={parseFloat(target.targetAllocation) || 0}
                  onChange={(e) => handleAllocationChange(target.amfiCode, parseFloat(e.target.value))}
                  className="w-full h-1.5 bg-white/5 rounded-full appearance-none cursor-pointer accent-accent"
                />
              </div>

              {/* Numeric Input % */}
              <div className="flex items-center gap-1">
                <input 
                  type="number"
                  min="0"
                  max="100"
                  step="0.1"
                  value={target.targetAllocation}
                  onChange={(e) => handleAllocationChange(target.amfiCode, e.target.value)}
                  className="w-14 bg-white/5 border border-white/10 rounded px-1.5 py-0.5 text-right font-black text-accent text-xs focus:border-accent focus:outline-none"
                />
                <span className="text-accent text-xs font-bold">%</span>
              </div>

              {/* Save Button */}
              <button 
                onClick={() => handleSave(target)}
                disabled={saving}
                className="px-3 py-1 bg-accent hover:bg-accent-bright disabled:bg-white/5 text-slate-950 disabled:text-slate-500 rounded font-black text-[9px] uppercase tracking-wider transition-all cursor-pointer"
              >
                Save
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Global Status Toast */}
      {status.message && (
        <div 
          className={`fixed bottom-10 left-1/2 -translate-x-1/2 px-8 py-4 rounded-full border shadow-2xl flex items-center gap-3 ${
            status.type === 'success' ? 'bg-buy/10 border-buy/20 text-buy' : 'bg-exit/10 border-exit/20 text-exit'
          }`}
        >
          {status.type === 'success' ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}
          <p className="text-[10px] font-black uppercase tracking-[0.2em]">{status.message}</p>
        </div>
      )}
    </div>
  );
};

export default StrategyManagerView;
