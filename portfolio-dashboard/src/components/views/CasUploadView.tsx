import React, { useState, useEffect } from 'react';
import { uploadCas, triggerBackfill, triggerForceSync, fetchAdminStatus, triggerSnapshotBackfill } from "@/services/api";
import { Upload, ShieldCheck, FileText, AlertCircle, CheckCircle2, Database, Zap, Loader2, TrendingUp, Activity } from "lucide-react";
import { useEngineWebsocket } from '@/hooks/useEngineWebsocket';

const CasUploadView: React.FC<{ pan: string, portfolioData?: any }> = ({ pan, portfolioData }) => {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<{ type: 'success' | 'error' | null, message: string }>({ type: null, message: '' });
  
  const [adminStatus, setAdminStatus] = useState<any>(null);
  const [syncing, setSyncing] = useState(false);
  const [backfilling, setBackfilling] = useState(false);
  const [snapshotBackfilling, setSnapshotBackfilling] = useState(false);
  const [rescoring, setRescoring] = useState(false);
  const [rescoreStatus, setRescoreStatus] = useState('');

  const engineProgress = useEngineWebsocket();

  // Detect mode: No transactions = Setup Mode
  const isSetupMode = !portfolioData || (portfolioData.totalTransactions || 0) === 0;

  // Polling for backfill status
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
        message: `✓ Successfully processed CAS for ${result.investor}. PAN: ${result.pan}` 
      });
      setFile(null);
      setPassword('');
      const input = document.getElementById('cas-file-input') as HTMLInputElement;
      if (input) input.value = '';
    } catch (err: any) {
      const detail = err.message || 'Unknown error';
      let friendly = detail;
      if (detail.includes('password')) friendly = 'Incorrect PDF password. Try the CAMS/Karvy password from your statement email.';
      else if (detail.includes('parse')) friendly = 'Unable to parse PDF. Ensure it is a Consolidated Account Statement (CAS).';
      
      setStatus({ type: 'error', message: friendly });
    } finally {
      setLoading(false);
    }
  };

  const handleBackfill = async () => {
    setBackfilling(true);
    try {
      await triggerBackfill();
    } catch (err: any) {
      setStatus({ type: 'error', message: `History refresh failed: ${err.message}` });
    } finally {
      setBackfilling(false);
    }
  };

  const handleSnapshotBackfill = async () => {
    setSnapshotBackfilling(true);
    try {
      await triggerSnapshotBackfill(pan);
      setStatus({ type: 'success', message: '✓ Performance history backfill started. Trajectory chart is being rebuilt.' });
    } catch (err: any) {
      setStatus({ type: 'error', message: `Performance rebuild failed: ${err.message}` });
    } finally {
      setSnapshotBackfilling(false);
    }
  };

  const handleSync = async () => {
    setSyncing(true);
    try {
      await triggerForceSync(pan);
      setStatus({ type: 'success', message: '✓ Quantitative engine sync started.' });
    } catch (err: any) {
      setStatus({ type: 'error', message: `Engine sync failed: ${err.message}` });
    } finally {
      setSyncing(false);
    }
  };

  const handleRescore = async () => {
    setRescoring(true);
    setRescoreStatus('');
    try {
      await triggerForceSync(pan);
      setRescoreStatus('✓ Scores updated. Refresh dashboard.');
    } catch {
      setRescoreStatus('❌ Scoring failed.');
    } finally {
      setRescoring(false);
    }
  };

  return (
    <div className="max-w-5xl mx-auto space-y-12 pb-20 font-sans">
      <div className="flex flex-col space-y-2">
        <h2 className="text-3xl font-black tracking-tight text-white uppercase italic">System Nucleus</h2>
        <p className="text-muted text-xs font-bold uppercase tracking-widest">
          {isSetupMode ? "Complete the sequence below to initialize your portfolio" : "Maintain and synchronize your portfolio data"}
        </p>
      </div>

      {isSetupMode ? (
        <div className="space-y-12">
          {/* STEP 1: UPLOAD */}
          <section className="relative">
            <div className="absolute -left-12 top-0 h-full w-px bg-accent/20 hidden lg:block" />
            <div className="absolute -left-[53px] top-0 w-2.5 h-2.5 rounded-full bg-accent hidden lg:block shadow-[0_0_10px_rgba(129,140,248,0.8)]" />
            <div className="space-y-6">
              <div className="flex items-center gap-4">
                <span className="text-[10px] font-black text-accent bg-accent/10 px-3 py-1 rounded-full border border-accent/20 uppercase tracking-widest">Step 01</span>
                <h3 className="text-sm font-black uppercase tracking-[0.3em] text-primary">Initialize Holdings</h3>
              </div>
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-10">
                <div className="bg-surface border border-white/5 rounded-3xl p-8 shadow-2xl space-y-6">
                  <p className="text-xs text-muted leading-relaxed font-medium">
                    Upload your <b>CAMS/Karvy Consolidated Account Statement (CAS)</b>. This imports all transactions and tax lots.
                  </p>
                  <form onSubmit={handleSubmit} className="space-y-6">
                    <input
                      id="cas-file-input"
                      type="file"
                      accept=".pdf"
                      onChange={handleFileChange}
                      className="block w-full text-[10px] text-muted
                        file:mr-4 file:py-2 file:px-6
                        file:rounded-xl file:border file:border-white/10
                        file:text-[10px] file:font-black file:uppercase file:tracking-[0.15em]
                        file:bg-white/5 file:text-secondary
                        hover:file:bg-white/10 cursor-pointer transition-all"
                    />
                    <input
                      type="password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="PDF Password"
                      className="flex h-12 w-full rounded-xl border border-white/5 bg-white/[0.02] px-4 py-2 text-sm text-white placeholder:text-muted/20 focus:outline-none focus:ring-1 focus:ring-accent/40 transition-all"
                    />
                    <button 
                      type="submit" 
                      disabled={!file || loading}
                      className="w-full h-14 bg-accent/10 text-accent hover:bg-accent hover:text-white border border-accent/20 rounded-2xl font-black uppercase tracking-[0.2em] text-[10px] transition-all flex items-center justify-center gap-3 disabled:opacity-20"
                    >
                      {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Zap size={14} className="fill-current" />}
                      Inject Portfolio
                    </button>
                  </form>
                </div>
                {status.type && (
                  <div className={`p-8 rounded-3xl border flex flex-col justify-center gap-4 ${status.type === 'success' ? 'bg-buy/5 border-buy/20' : 'bg-exit/5 border-exit/20'}`}>
                    <div className="flex items-center gap-3">
                      {status.type === 'success' ? <CheckCircle2 className="text-buy" size={24} /> : <AlertCircle className="text-exit" size={24} />}
                      <p className={`text-sm font-black uppercase tracking-widest ${status.type === 'success' ? 'text-buy' : 'text-exit'}`}>
                        {status.type === 'success' ? 'Success' : 'Attention Required'}
                      </p>
                    </div>
                    <p className="text-xs font-medium text-secondary leading-relaxed">{status.message}</p>
                  </div>
                )}
              </div>
            </div>
          </section>

          {/* STEP 2: NAV DATA */}
          <section className="relative">
            <div className="absolute -left-12 top-0 h-full w-px bg-white/5 hidden lg:block" />
            <div className="absolute -left-[51px] top-0 w-1.5 h-1.5 rounded-full bg-white/20 hidden lg:block" />
            <div className="space-y-6">
              <div className="flex items-center gap-4">
                <span className="text-[10px] font-black text-muted bg-white/5 px-3 py-1 rounded-full border border-white/5 uppercase tracking-widest">Step 02</span>
                <h3 className="text-sm font-black uppercase tracking-[0.3em] text-primary">Acquire NAV History</h3>
              </div>
              <div className="bg-surface border border-white/5 rounded-3xl p-8 max-w-2xl space-y-6">
                <p className="text-xs text-muted leading-relaxed font-medium">
                  Downloads 3 years of daily price data for every fund in your portfolio. This takes 2–5 minutes.
                </p>
                <button 
                  onClick={handleBackfill}
                  disabled={adminStatus?.backfill?.isRunning}
                  className="px-8 h-12 bg-white/5 hover:bg-white/10 border border-white/10 rounded-xl text-[10px] font-black uppercase tracking-[0.2em] text-primary flex items-center gap-3 transition-all disabled:opacity-20"
                >
                  <TrendingUp size={16} />
                  Start Full History Refresh
                </button>
                {adminStatus?.backfill?.isRunning && (
                  <div className="space-y-3 pt-4 border-t border-white/5">
                    <div className="flex justify-between text-[10px] font-black uppercase tracking-widest text-muted">
                      <span>Syncing {adminStatus.backfill.progress} / {adminStatus.backfill.total}</span>
                      <span className="text-accent">{Math.round((adminStatus.backfill.progress/adminStatus.backfill.total)*100)}%</span>
                    </div>
                    <div className="h-1 w-full bg-white/5 rounded-full overflow-hidden">
                      <div className="h-full bg-accent shadow-[0_0_10px_rgba(129,140,248,0.6)]" style={{ width: `${(adminStatus.backfill.progress/adminStatus.backfill.total)*100}%` }} />
                    </div>
                  </div>
                )}
              </div>
            </div>
          </section>

          {/* STEP 3: QUANT ENGINE */}
          <section className="relative">
            <div className="absolute -left-[51px] top-0 w-1.5 h-1.5 rounded-full bg-white/20 hidden lg:block" />
            <div className="space-y-6">
              <div className="flex items-center gap-4">
                <span className="text-[10px] font-black text-muted bg-white/5 px-3 py-1 rounded-full border border-white/5 uppercase tracking-widest">Step 03</span>
                <h3 className="text-sm font-black uppercase tracking-[0.3em] text-primary">Initialize Analytics</h3>
              </div>
              <div className="bg-surface border border-white/5 rounded-3xl p-8 max-w-2xl space-y-6">
                <p className="text-xs text-muted leading-relaxed font-medium">
                  Runs the 7-phase quantitative engine: Z-Scores, Conviction, Hurst, and Market Regimes.
                </p>
                <button 
                  onClick={handleSync}
                  className="px-8 h-12 bg-white/5 hover:bg-white/10 border border-white/10 rounded-xl text-[10px] font-black uppercase tracking-[0.2em] text-primary flex items-center gap-3 transition-all"
                >
                  <Zap size={16} className="fill-current" />
                  Run Math Engine
                </button>
              </div>
            </div>
          </section>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          {/* Stats Bar */}
          <div className="lg:col-span-3 grid grid-cols-2 md:grid-cols-4 gap-4">
             <div className="p-6 bg-surface border border-white/5 rounded-2xl space-y-1">
                <p className="text-[9px] font-black text-muted uppercase tracking-widest">NAV Coverage</p>
                <p className="text-sm font-black text-buy">100% Verified</p>
             </div>
             <div className="p-6 bg-surface border border-white/5 rounded-2xl space-y-1">
                <p className="text-[9px] font-black text-muted uppercase tracking-widest">Total Assets</p>
                <p className="text-sm font-black text-primary">{portfolioData?.totalSchemes || 0}</p>
             </div>
             <div className="p-6 bg-surface border border-white/5 rounded-2xl space-y-1">
                <p className="text-[9px] font-black text-muted uppercase tracking-widest">Data Stability</p>
                <p className="text-sm font-black text-accent">High</p>
             </div>
             <div className="p-6 bg-surface border border-white/5 rounded-2xl space-y-1">
                <p className="text-[9px] font-black text-muted uppercase tracking-widest">Sync Health</p>
                <p className="text-sm font-black text-primary">Active</p>
             </div>
          </div>

          {/* Main Controls */}
          <div className="lg:col-span-2 space-y-6">
            <div className="bg-surface border border-white/5 rounded-3xl overflow-hidden shadow-2xl">
              <div className="p-8 border-b border-white/5 bg-white/[0.01] flex justify-between items-center">
                <div className="flex items-center gap-3">
                  <Activity className="text-accent" size={20} />
                  <h3 className="text-sm font-black uppercase tracking-widest">Quick Rescore</h3>
                </div>
                {rescoring && <Loader2 className="animate-spin text-accent" size={16} />}
              </div>
              <div className="p-8 space-y-6">
                <p className="text-xs text-secondary leading-relaxed font-medium">
                  Update your conviction scores based on latest NAVs and personal CAGR. Run this after any new trade or significant market move.
                </p>
                <button
                  onClick={handleRescore}
                  disabled={rescoring}
                  className="px-10 h-14 bg-accent text-primary rounded-2xl text-[10px] font-black uppercase tracking-[0.3em] hover:brightness-110 transition-all shadow-[0_0_30px_rgba(129,140,248,0.3)] disabled:opacity-50"
                >
                  Recalculate My Scores
                </button>
                {rescoreStatus && (
                  <p className="text-[10px] font-black uppercase text-center text-accent animate-pulse">{rescoreStatus}</p>
                )}
              </div>
            </div>

            <div className="bg-surface border border-white/5 rounded-3xl p-8 space-y-6">
              <div className="flex items-center gap-3">
                <Upload className="text-muted" size={18} />
                <h3 className="text-xs font-black uppercase tracking-widest text-muted">Incremental Update</h3>
              </div>
              <form onSubmit={handleSubmit} className="flex flex-col md:flex-row gap-4">
                <input
                  type="file"
                  onChange={handleFileChange}
                  className="flex-1 text-[10px] text-muted file:mr-4 file:py-2 file:px-6 file:rounded-xl file:border-white/10 file:bg-white/5 file:text-secondary"
                />
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Password"
                  className="w-32 rounded-xl border border-white/5 bg-white/[0.02] px-4 text-sm"
                />
                <button type="submit" disabled={!file || loading} className="px-6 py-2 bg-white/5 hover:bg-white/10 border border-white/10 rounded-xl text-[9px] font-black uppercase tracking-widest">
                  Update
                </button>
              </form>
            </div>
          </div>

          {/* Advanced Panel */}
          <div className="space-y-6">
             <div className="bg-surface border border-white/5 rounded-3xl p-8 space-y-8">
                <h4 className="text-[9px] font-black uppercase tracking-[0.4em] text-muted">Advanced Ops</h4>
                
                <div className="space-y-4">
                  <span className="text-[10px] font-bold text-primary">Full NAV Backfill</span>
                  <button onClick={handleBackfill} className="w-full h-10 bg-white/5 border border-white/5 rounded-xl text-[9px] font-black uppercase tracking-widest hover:bg-white/10 transition-all">Execute</button>
                </div>

                <div className="space-y-4">
                  <span className="text-[10px] font-bold text-primary">Performance Rebuild</span>
                  <button onClick={handleSnapshotBackfill} className="w-full h-10 bg-white/5 border border-white/5 rounded-xl text-[9px] font-black uppercase tracking-widest hover:bg-white/10 transition-all">Execute</button>
                </div>

                <div className="space-y-4">
                  <span className="text-[10px] font-bold text-primary">Engine Force Sync</span>
                  <button onClick={handleSync} className="w-full h-10 bg-white/5 border border-white/5 rounded-xl text-[9px] font-black uppercase tracking-widest hover:bg-white/10 transition-all">Execute</button>
                </div>
             </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default CasUploadView;
