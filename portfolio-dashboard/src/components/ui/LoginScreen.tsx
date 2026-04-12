import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { ShieldCheck, ArrowRight, Loader2, Lock, Zap, Upload } from 'lucide-react';
import { checkInvestorExistence } from '../../services/api';

interface LoginScreenProps {
  onLogin: (pan: string) => void;
  onSetup: () => void;
}

export default function LoginScreen({ onLogin, onSetup }: LoginScreenProps) {
  const [pan, setPan] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const cleanPan = pan.trim().toUpperCase();
    
    if (!/^[A-Z]{5}[0-9]{4}[A-Z]{1}$/.test(cleanPan)) {
      setError('Invalid PAN format (e.g., ABCDE1234F)');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const result = await checkInvestorExistence(cleanPan);
      if (result && result.pan) {
        onLogin(cleanPan);
      } else {
        setError('Investor account not found. Please upload a CAS file first.');
      }
    } catch (err) {
      console.error("Login verification failed:", err);
      setError('System connection failure. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-background flex items-center justify-center p-6 overflow-hidden">
      {/* Background Decorative Blobs */}
      <div className="absolute inset-0 pointer-events-none opacity-30">
        <div className="absolute top-[-10%] left-[-10%] w-[40%] h-[40%] bg-accent/10 rounded-full blur-[120px]" />
        <div className="absolute bottom-[-10%] right-[-10%] w-[40%] h-[40%] bg-buy/5 rounded-full blur-[120px]" />
      </div>

      <motion.div 
        initial={{ opacity: 0, y: 20, scale: 0.95 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        className="w-full max-w-md space-y-10 relative z-10"
      >
        <div className="text-center space-y-4">
          <motion.div 
            initial={{ scale: 0 }}
            animate={{ scale: 1 }}
            transition={{ type: 'spring', damping: 12, stiffness: 200 }}
            className="w-16 h-16 bg-accent/10 border border-accent/20 rounded-3xl flex items-center justify-center mx-auto shadow-[0_0_30px_rgba(203,166,247,0.2)]"
          >
            <Lock className="text-accent" size={28} />
          </motion.div>
          <div className="space-y-1">
            <h1 className="text-3xl font-black text-primary tracking-tighter">Portfolio OS</h1>
            <p className="text-muted text-[10px] font-black uppercase tracking-[0.4em] opacity-60">Quant-Driven Wealth Intelligence</p>
          </div>
        </div>

        <div className="bg-surface/40 backdrop-blur-3xl border border-white/5 p-10 rounded-[2.5rem] shadow-2xl space-y-8">
          <form onSubmit={handleSubmit} className="space-y-6">
            <div className="space-y-3">
              <label className="text-[10px] font-black uppercase tracking-widest text-muted ml-1 opacity-60">Identity Verification</label>
              <div className="relative">
                <input
                  type="text"
                  value={pan}
                  onChange={(e) => {
                    setPan(e.target.value.toUpperCase());
                    setError(null);
                  }}
                  placeholder="ENTER PERMANENT ACCOUNT NUMBER"
                  className="w-full bg-black/20 border border-white/5 rounded-2xl px-6 py-4 text-sm font-black tracking-[0.1em] text-primary focus:outline-none focus:border-accent/40 focus:ring-1 focus:ring-accent/20 transition-all placeholder:text-muted/20 placeholder:font-bold tabular-nums"
                  maxLength={10}
                  autoFocus
                />
                <ShieldCheck className={`absolute right-5 top-1/2 -translate-y-1/2 transition-colors ${pan.length === 10 ? 'text-buy' : 'text-muted/10'}`} size={18} />
              </div>
              <AnimatePresence mode="wait">
                {error && (
                  <motion.p 
                    initial={{ opacity: 0, x: -10 }}
                    animate={{ opacity: 1, x: 0 }}
                    exit={{ opacity: 0, x: 10 }}
                    className="text-[10px] font-bold text-exit uppercase tracking-wider ml-1"
                  >
                    ⚠️ {error}
                  </motion.p>
                )}
              </AnimatePresence>
            </div>

            <button
              type="submit"
              disabled={loading || pan.length < 10}
              className="w-full h-14 bg-accent/10 border border-accent/20 rounded-2xl flex items-center justify-center gap-3 group hover:bg-accent hover:text-white transition-all duration-300 disabled:opacity-20 disabled:cursor-not-allowed active:scale-[0.98] shadow-xl overflow-hidden relative"
            >
              {loading ? (
                <Loader2 className="animate-spin" size={20} />
              ) : (
                <>
                  <span className="text-[11px] font-black uppercase tracking-[0.2em] relative z-10">Access Terminal</span>
                  <ArrowRight size={16} className="group-hover:translate-x-1 transition-transform relative z-10" />
                </>
              )}
              {/* Shine effect */}
              <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/10 to-transparent translate-x-[-100%] group-hover:translate-x-[100%] transition-transform duration-1000" />
            </button>
          </form>

          <div className="pt-4 border-t border-white/5 space-y-4">
            <button
              onClick={onSetup}
              type="button"
              className="w-full h-12 bg-white/5 border border-white/10 rounded-2xl flex items-center justify-center gap-3 group hover:bg-white/10 transition-all duration-300"
            >
              <Upload size={14} className="text-muted group-hover:text-primary transition-colors" />
              <span className="text-[10px] font-black uppercase tracking-[0.2em] text-muted group-hover:text-primary">New User? Upload Statement</span>
            </button>

            <div className="flex items-center gap-4 text-muted/40 px-2">
              <Zap size={14} className="shrink-0" />
              <p className="text-[9px] font-bold uppercase tracking-widest leading-relaxed">
                Requires pre-injected CAS data to initialize quantitative models.
              </p>
            </div>
          </div>
        </div>

        <p className="text-center text-[9px] font-bold text-muted/20 uppercase tracking-[0.3em]">
          End-to-End Encrypted Terminal · Restricted Access
        </p>
      </motion.div>
    </div>
  );
}
