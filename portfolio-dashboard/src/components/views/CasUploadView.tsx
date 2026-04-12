import React, { useState, useEffect } from 'react';
import { uploadCas, triggerBackfill, triggerForceSync, fetchAdminStatus } from "@/services/api";
import { Upload, ShieldCheck, FileText, AlertCircle, CheckCircle2, Database, Zap, Loader2, Info, TrendingUp } from "lucide-react";
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

const CasUploadView: React.FC<{ pan: string }> = ({ pan }) => {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<{ type: 'success' | 'error' | null, message: string }>({ type: null, message: '' });
  
  const [adminStatus, setAdminStatus] = useState<any>(null);
  const [syncing, setSyncing] = useState(false);
  const [backfilling, setBackfilling] = useState(false);
  const [engineProgress, setEngineProgress] = useState<any>(null);

  // Polling for backfill status (WebSocket only for engine for now)
  useEffect(() => {
    const interval = setInterval(async () => {
      try {
        const data = await fetchAdminStatus();
        setAdminStatus(data);
      } catch (err) {
        console.error("Failed to poll status", err);
      }
    }, 3000);
    return () => clearInterval(interval);
  }, []);

  // WebSocket Connection for Engine Progress
  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/api/ws'),
      onConnect: () => {
        client.subscribe('/topic/engine-progress', (msg) => {
          const data = JSON.parse(msg.body);
          setEngineProgress(data);
        });
      },
      debug: (str) => console.log('STOMP: ' + str),
    });

    client.activate();
    return () => { client.deactivate(); };
  }, []);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      setFile(e.target.files[0]);
      setStatus({ type: null, message: '' });
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;

    setLoading(true);
    setStatus({ type: null, message: '' });

    try {
      const result = await uploadCas(file, password);
      setStatus({ 
        type: 'success', 
        message: `Successfully processed CAS for ${result.investor}. PAN: ${result.pan}` 
      });
      setFile(null);
      setPassword('');
      const input = document.getElementById('cas-file-input') as HTMLInputElement;
      if (input) input.value = '';
    } catch (err: any) {
      setStatus({ 
        type: 'error', 
        message: err.message || 'Failed to process CAS. Please check your password and file.' 
      });
    } finally {
      setLoading(false);
    }
  };

  const handleBackfill = async () => {
    setBackfilling(true);
    try {
      await triggerBackfill();
    } catch (err: any) {
      alert(err.message);
    } finally {
      setBackfilling(false);
    }
  };

  const handleSync = async () => {
    setSyncing(true);
    try {
      await triggerForceSync(pan);
    } catch (err: any) {
      alert(err.message);
    } finally {
      setSyncing(false);
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-8 pb-20 font-sans">
      <div className="flex flex-col space-y-2">
        <h2 className="text-2xl font-bold tracking-tight text-white">Data Management</h2>
        <p className="text-muted text-sm font-medium">
          Import holdings via CAS or manage your fund metrics and historical data.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {/* Left Column: CAS Upload */}
        <div className="space-y-6">
          <div className="bg-surface border border-border rounded-xl shadow-2xl overflow-hidden group hover:border-accent/20 transition-all duration-300">
            <div className="px-6 pt-6 pb-4 border-b border-border bg-white/[0.01]">
              <div className="flex items-center gap-2 mb-1">
                <Upload className="h-5 w-5 text-accent" />
                <h3 className="text-sm font-bold text-primary uppercase tracking-widest">Consolidated PDF Statement</h3>
              </div>
              <p className="text-[10px] text-muted mt-1 uppercase tracking-wider font-semibold">
                Processes CAS PDF locally and injects into database.
              </p>
            </div>
            <div className="p-6">
              <form onSubmit={handleSubmit} className="space-y-6">
                <div className="space-y-4">
                  <div className="flex flex-col items-center justify-center p-8 border border-border rounded-xl bg-white/[0.01] hover:bg-white/[0.03] transition-all cursor-pointer group/upload relative overflow-hidden">
                    <div className="absolute inset-0 bg-accent/5 opacity-0 group-hover/upload:opacity-100 transition-opacity" />
                    <FileText className="h-12 w-12 text-muted/20 mb-4 group-hover/upload:text-accent/40 group-hover/upload:scale-110 transition-all duration-500" />
                    <label className="text-[10px] font-black uppercase tracking-[0.2em] text-muted mb-4 group-hover/upload:text-secondary transition-colors">Select Statement</label>
                    <input
                      id="cas-file-input"
                      type="file"
                      accept=".pdf"
                      onChange={handleFileChange}
                      className="block w-full text-[10px] text-muted relative z-10
                        file:mr-4 file:py-2 file:px-6
                        file:rounded-lg file:border file:border-border
                        file:text-[10px] file:font-black file:uppercase file:tracking-[0.15em]
                        file:bg-white/5 file:text-secondary
                        hover:file:bg-white/10 cursor-pointer transition-all"
                      required
                    />
                  </div>

                  <div className="space-y-2">
                    <label className="text-[10px] font-black uppercase tracking-[0.2em] text-muted flex items-center gap-2">
                      <ShieldCheck className="h-3 w-3" />
                      PDF Password
                    </label>
                    <input
                      type="password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="Enter PDF password"
                      className="flex h-11 w-full rounded-xl border border-border bg-white/[0.02] px-4 py-2 text-sm text-white placeholder:text-muted/20 focus:outline-none focus:ring-1 focus:ring-accent/40 transition-all font-medium"
                      required
                    />
                  </div>
                </div>

                {status.type && (
                  <div className={`p-4 rounded-xl flex items-start gap-3 border animate-in fade-in slide-in-from-top-2 duration-300 ${
                    status.type === 'success' 
                      ? 'bg-buy/10 text-buy border-buy/20' 
                      : 'bg-exit/10 text-exit border-exit/20'
                  }`}>
                    {status.type === 'success' ? <CheckCircle2 className="h-4 w-4 mt-0.5 shrink-0" /> : <AlertCircle className="h-4 w-4 mt-0.5 shrink-0" />}
                    <p className="text-[11px] font-bold leading-relaxed">{status.message}</p>
                  </div>
                )}

                <button 
                  type="submit" 
                  className={`w-full h-12 text-[10px] font-black uppercase tracking-[0.2em] transition-all px-4 py-2 rounded-xl border border-border flex items-center justify-center gap-3 ${
                    !file || loading ? 'opacity-30 cursor-not-allowed bg-white/5 text-muted' : 'bg-accent/10 text-accent hover:bg-accent hover:text-white border-accent/20 shadow-[0_0_20px_rgba(129,140,248,0.15)] active:scale-[0.98]'
                  }`}
                  disabled={!file || loading}
                >
                  {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Zap size={14} className="fill-current" />}
                  {loading ? "Decrypting..." : "Inject Portfolio"}
                </button>
              </form>
            </div>
          </div>
        </div>

        {/* Right Column: Admin Actions */}
        <div className="space-y-6">
          <div className="bg-surface border border-border rounded-xl shadow-2xl overflow-hidden hover:border-white/10 transition-colors duration-300">
            <div className="px-6 pt-6 pb-4 border-b border-border bg-white/[0.01]">
              <div className="flex items-center gap-2 mb-1">
                <Database className="h-5 w-5 text-accent" />
                <h3 className="text-sm font-bold text-primary uppercase tracking-widest">Maintenance Hub</h3>
              </div>
              <p className="text-[10px] text-muted mt-1 uppercase tracking-wider font-semibold">
                Manual override for background sync cycles.
              </p>
            </div>
            <div className="p-6 space-y-8">
              {/* Backfill Section */}
              <div className="space-y-4">
                <div className="flex justify-between items-center">
                  <span className="text-[10px] font-black uppercase tracking-[0.2em] text-primary">Price Backfiller</span>
                  {adminStatus?.backfill?.isRunning && (
                    <span className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-buy/10 text-buy text-[9px] font-black uppercase border border-buy/20 shadow-[0_0_10px_rgba(52,211,153,0.2)]">
                      <Loader2 className="h-2.5 w-2.5 animate-spin" />
                      In Progress
                    </span>
                  )}
                </div>
                <p className="text-[11px] text-secondary leading-relaxed font-medium">
                  Re-scans full NAV history for all active funds. Throttled to 10s intervals to maintain API stability.
                </p>
                {adminStatus?.backfill?.isRunning && (
                  <div className="space-y-2.5 bg-white/[0.02] p-4 rounded-xl border border-border">
                    <div className="flex justify-between text-[9px] font-black uppercase tracking-widest text-muted">
                      <span>Queue Progress</span>
                      <span className="text-secondary">{adminStatus.backfill.progress} / {adminStatus.backfill.total}</span>
                    </div>
                    <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
                      <div 
                        className="h-full bg-accent transition-all duration-700 shadow-[0_0_12px_rgba(129,140,248,0.5)]" 
                        style={{ width: `${(adminStatus.backfill.progress / adminStatus.backfill.total) * 100}%` }}
                      />
                    </div>
                    <p className="text-[9px] text-accent/80 font-bold uppercase tracking-tighter truncate italic">
                      {adminStatus.backfill.message}
                    </p>
                  </div>
                )}
                <button 
                  onClick={handleBackfill}
                  disabled={adminStatus?.backfill?.isRunning || backfilling}
                  className="w-full h-10 px-4 py-2 bg-white/5 border border-border rounded-xl text-[10px] font-black uppercase tracking-[0.2em] text-secondary hover:text-primary hover:bg-white/[0.08] transition-all disabled:opacity-30 disabled:cursor-not-allowed flex items-center justify-center gap-2 group"
                >
                  <TrendingUp size={14} className="group-hover:translate-y-[-1px] transition-transform" />
                  Full History Refresh
                </button>
              </div>

              <div className="h-px bg-border mx-2" />

              {/* Engine Sync Section */}
              <div className="space-y-4">
                <div className="flex justify-between items-center">
                  <span className="text-[10px] font-black uppercase tracking-[0.2em] text-primary">Quantitative Engine</span>
                  {(adminStatus?.engine?.isRunning || (engineProgress && engineProgress.step < 7 && engineProgress.step > 0)) && (
                    <span className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-buy/10 text-buy text-[9px] font-black uppercase border border-buy/20 shadow-[0_0_10px_rgba(52,211,153,0.2)]">
                      <Loader2 className="h-2.5 w-2.5 animate-spin" />
                      Computing
                    </span>
                  )}
                </div>
                <p className="text-[11px] text-secondary leading-relaxed font-medium">
                  Triggers 7-phase calculation: Risk indexing, NAV signals, Peer-relative Z-Scoring, Conviction, Hurst, OU Reversion, and HMM Regimes.
                </p>
                {(adminStatus?.engine?.isRunning || (engineProgress && engineProgress.step < 7 && engineProgress.step > 0)) && (
                  <div className="space-y-2.5 bg-white/[0.02] p-4 rounded-xl border border-border">
                    <div className="flex justify-between text-[9px] font-black uppercase tracking-widest text-muted">
                      <span>Execution Phase</span>
                      <span className="text-secondary">{engineProgress?.step || adminStatus?.engine?.step} / 7</span>
                    </div>
                    <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
                      <div 
                        className="h-full bg-buy transition-all duration-700 shadow-[0_0_12px_rgba(52,211,153,0.5)]" 
                        style={{ width: `${((engineProgress?.step || adminStatus?.engine?.step) / 7) * 100}%` }}
                      />
                    </div>
                    <p className="text-[9px] text-buy/80 font-bold uppercase tracking-tighter italic">
                      {engineProgress?.message || adminStatus?.engine?.message}
                    </p>
                  </div>
                )}
                {engineProgress?.step === 7 && (
                  <div className="flex items-center gap-2 text-buy bg-buy/10 p-3 rounded-xl border border-buy/20 animate-in fade-in zoom-in duration-500">
                    <CheckCircle2 size={14} />
                    <span className="text-[9px] font-black uppercase tracking-widest">Engine Cycle Complete ✓</span>
                  </div>
                )}
                <button 
                  onClick={handleSync}
                  disabled={adminStatus?.engine?.isRunning || syncing}
                  className="w-full h-10 px-4 py-2 bg-white/5 border border-border rounded-xl text-[10px] font-black uppercase tracking-[0.2em] text-secondary hover:text-primary hover:bg-white/[0.08] transition-all disabled:opacity-30 disabled:cursor-not-allowed flex items-center justify-center gap-2 group"
                >
                  <Zap size={14} className="group-hover:scale-110 transition-transform fill-current" />
                  Sync Metrics Engine
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="p-6 bg-white/[0.02] rounded-2xl border border-border flex gap-5 hover:bg-white/[0.04] transition-all group duration-500">
          <div className="h-10 w-10 rounded-xl bg-accent/10 flex items-center justify-center shrink-0 group-hover:bg-accent/20 transition-colors">
            <Info className="h-5 w-5 text-accent group-hover:scale-110 transition-transform" />
          </div>
          <div className="space-y-2">
            <h3 className="text-[10px] font-black uppercase tracking-[0.2em] text-white">First-Time Setup</h3>
            <p className="text-[11px] leading-relaxed text-muted font-medium">
              After uploading CAS, run <b>History Refresh</b> followed by <b>Metrics Engine</b> to generate your risk index.
            </p>
          </div>
        </div>
        <div className="p-6 bg-white/[0.02] rounded-2xl border border-border flex gap-5 hover:bg-white/[0.04] transition-all group duration-500">
          <div className="h-10 w-10 rounded-xl bg-buy/10 flex items-center justify-center shrink-0 group-hover:bg-buy/20 transition-colors">
            <Zap className="h-5 w-5 text-buy group-hover:scale-110 transition-transform fill-current" />
          </div>
          <div className="space-y-2">
            <h3 className="text-[10px] font-black uppercase tracking-[0.2em] text-white">Nightly Sync</h3>
            <p className="text-[11px] leading-relaxed text-muted font-medium">
              Automated AMFI updates and re-scoring happens every weekday at 11:30 PM IST.
            </p>
          </div>
        </div>
        <div className="p-6 bg-white/[0.02] rounded-2xl border border-border flex gap-5 hover:bg-white/[0.04] transition-all group duration-500">
          <div className="h-10 w-10 rounded-xl bg-secondary/10 flex items-center justify-center shrink-0 group-hover:bg-secondary/20 transition-colors">
            <Database className="h-5 w-5 text-secondary group-hover:scale-110 transition-transform" />
          </div>
          <div className="space-y-2">
            <h3 className="text-[10px] font-black uppercase tracking-[0.2em] text-white">Data Privacy</h3>
            <p className="text-[11px] leading-relaxed text-muted font-medium">
              Processing is local. We never store or transmit your sensitive financial files outside your environment.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default CasUploadView;
