import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { uploadCas, triggerBackfill, triggerForceSync, fetchAdminStatus } from "@/services/api";
import { Upload, ShieldCheck, FileText, AlertCircle, CheckCircle2, Database, Zap, Loader2, Info } from "lucide-react";

const CasUploadView: React.FC = () => {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<{ type: 'success' | 'error' | null, message: string }>({ type: null, message: '' });
  
  const [adminStatus, setAdminStatus] = useState<any>(null);
  const [syncing, setSyncing] = useState(false);
  const [backfilling, setBackfilling] = useState(false);

  // Polling for admin status
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
      await triggerForceSync();
    } catch (err: any) {
      alert(err.message);
    } finally {
      setSyncing(false);
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-8 pb-20">
      <div className="flex flex-col space-y-2">
        <h2 className="text-2xl font-bold tracking-tight text-white">Data Management</h2>
        <p className="text-muted text-sm">
          Import holdings via CAS or manage your fund metrics and historical data.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {/* Left Column: CAS Upload */}
        <div className="space-y-6">
          <Card className="bg-white/[0.02] border-white/5 border-2 border-dashed">
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-white">
                <Upload className="h-5 w-5 text-accent" />
                CAS PDF Upload
              </CardTitle>
              <CardDescription className="text-muted text-xs">
                Processes CAS PDF locally and injects into database.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleSubmit} className="space-y-6">
                <div className="space-y-4">
                  <div className="flex flex-col items-center justify-center p-6 border border-white/5 rounded-xl bg-white/[0.01]">
                    <FileText className="h-10 w-10 text-muted/30 mb-4" />
                    <input
                      id="cas-file-input"
                      type="file"
                      accept=".pdf"
                      onChange={handleFileChange}
                      className="block w-full text-[10px] text-muted
                        file:mr-4 file:py-1.5 file:px-4
                        file:rounded-lg file:border file:border-white/10
                        file:text-[10px] file:font-bold file:uppercase file:tracking-widest
                        file:bg-white/5 file:text-secondary
                        hover:file:bg-white/10 cursor-pointer transition-all"
                      required
                    />
                  </div>

                  <div className="space-y-2">
                    <label className="text-[10px] font-bold uppercase tracking-widest text-muted flex items-center gap-2">
                      <ShieldCheck className="h-3 w-3" />
                      PDF Password
                    </label>
                    <input
                      type="password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="Enter PDF password"
                      className="flex h-10 w-full rounded-lg border border-white/5 bg-white/[0.02] px-4 py-2 text-sm text-white placeholder:text-muted/30 focus:outline-none focus:ring-1 focus:ring-accent/50 transition-all"
                      required
                    />
                  </div>
                </div>

                {status.type && (
                  <div className={`p-3 rounded-lg flex items-start gap-3 border ${
                    status.type === 'success' 
                      ? 'bg-buy/10 text-buy border-buy/20' 
                      : 'bg-exit/10 text-exit border-exit/20'
                  }`}>
                    {status.type === 'success' ? <CheckCircle2 className="h-4 w-4 mt-0.5" /> : <AlertCircle className="h-4 w-4 mt-0.5" />}
                    <p className="text-[11px] font-medium leading-relaxed">{status.message}</p>
                  </div>
                )}

                <Button 
                  type="submit" 
                  className={`w-full h-10 text-[10px] font-bold uppercase tracking-widest transition-all ${
                    !file || loading ? 'bg-white/5 text-muted' : 'bg-accent text-white hover:bg-accent/90'
                  }`}
                  disabled={!file || loading}
                >
                  {loading ? <Loader2 className="h-4 w-4 animate-spin mr-2" /> : null}
                  {loading ? "Parsing..." : "Upload & Process"}
                </Button>
              </form>
            </CardContent>
          </Card>
        </div>

        {/* Right Column: Admin Actions */}
        <div className="space-y-6">
          <Card className="bg-white/[0.02] border-white/5 border-2">
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-white">
                <Database className="h-5 w-5 text-accent" />
                Advanced Controls
              </CardTitle>
              <CardDescription className="text-muted text-xs">
                Trigger background maintenance tasks.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-6">
              {/* Backfill Section */}
              <div className="space-y-3">
                <div className="flex justify-between items-center">
                  <span className="text-[10px] font-bold uppercase tracking-widest text-primary">Historical Backfill</span>
                  {adminStatus?.backfill?.isRunning && (
                    <span className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-buy/10 text-buy text-[9px] font-bold uppercase">
                      <Loader2 className="h-2.5 w-2.5 animate-spin" />
                      Running
                    </span>
                  )}
                </div>
                <p className="text-[11px] text-muted leading-relaxed">
                  Fetches full price history for all funds. Takes ~4 mins to avoid API limits.
                </p>
                {adminStatus?.backfill?.isRunning && (
                  <div className="space-y-1.5">
                    <div className="flex justify-between text-[9px] font-bold uppercase text-muted">
                      <span>Progress</span>
                      <span>{adminStatus.backfill.progress} / {adminStatus.backfill.total}</span>
                    </div>
                    <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
                      <div 
                        className="h-full bg-accent transition-all duration-500" 
                        style={{ width: `${(adminStatus.backfill.progress / adminStatus.backfill.total) * 100}%` }}
                      />
                    </div>
                    <p className="text-[9px] text-accent/70 italic truncate">
                      {adminStatus.backfill.message}
                    </p>
                  </div>
                )}
                <Button 
                  onClick={handleBackfill}
                  variant="outline"
                  disabled={adminStatus?.backfill?.isRunning || backfilling}
                  className="w-full h-9 border-white/10 hover:bg-white/5 text-[10px] font-bold uppercase tracking-widest"
                >
                  Start Historical Backfill
                </Button>
              </div>

              <div className="h-px bg-white/5" />

              {/* Engine Sync Section */}
              <div className="space-y-3">
                <div className="flex justify-between items-center">
                  <span className="text-[10px] font-bold uppercase tracking-widest text-primary">Quant Engine Sync</span>
                  {adminStatus?.engine?.isRunning && (
                    <span className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-buy/10 text-buy text-[9px] font-bold uppercase">
                      <Loader2 className="h-2.5 w-2.5 animate-spin" />
                      Syncing
                    </span>
                  )}
                </div>
                <p className="text-[11px] text-muted leading-relaxed">
                  Re-calculates Sortino, CVaR, Drawdown and Peer-relative scores.
                </p>
                {adminStatus?.engine?.isRunning && (
                  <div className="space-y-1.5">
                    <div className="flex justify-between text-[9px] font-bold uppercase text-muted">
                      <span>Phase</span>
                      <span>{adminStatus.engine.step} / 4</span>
                    </div>
                    <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
                      <div 
                        className="h-full bg-buy transition-all duration-500" 
                        style={{ width: `${(adminStatus.engine.step / 4) * 100}%` }}
                      />
                    </div>
                    <p className="text-[9px] text-buy/70 italic">
                      {adminStatus.engine.message}
                    </p>
                  </div>
                )}
                <Button 
                  onClick={handleSync}
                  variant="outline"
                  disabled={adminStatus?.engine?.isRunning || syncing}
                  className="w-full h-9 border-white/10 hover:bg-white/5 text-[10px] font-bold uppercase tracking-widest"
                >
                  Run Metrics Engine
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="p-5 bg-white/[0.02] rounded-xl border border-white/5 flex gap-4">
          <Info className="h-5 w-5 text-accent shrink-0" />
          <div className="space-y-1.5">
            <h3 className="text-[10px] font-bold uppercase tracking-widest text-white">First-Time Setup</h3>
            <p className="text-[11px] leading-relaxed text-muted">
              After uploading CAS, run <b>Historical Backfill</b> first, followed by <b>Quant Engine Sync</b> to generate your risk scores.
            </p>
          </div>
        </div>
        <div className="p-5 bg-white/[0.02] rounded-xl border border-white/5 flex gap-4">
          <Zap className="h-5 w-5 text-buy shrink-0" />
          <div className="space-y-1.5">
            <h3 className="text-[10px] font-bold uppercase tracking-widest text-white">Nightly Sync</h3>
            <p className="text-[11px] leading-relaxed text-muted">
              The system automatically updates NAVs and re-calculates metrics every weekday at 11:30 PM IST.
            </p>
          </div>
        </div>
        <div className="p-5 bg-white/[0.02] rounded-xl border border-white/5 flex gap-4">
          <Database className="h-5 w-5 text-secondary shrink-0" />
          <div className="space-y-1.5">
            <h3 className="text-[10px] font-bold uppercase tracking-widest text-white">Data Privacy</h3>
            <p className="text-[11px] leading-relaxed text-muted">
              Processing is containerized. We never store or transmit your PDF files outside your private environment.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default CasUploadView;
