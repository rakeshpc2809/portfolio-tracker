import { useState, useEffect } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { fetchMasterPortfolio } from "./services/api";
import Dashboard from "./components/layout/Dashboard";

function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return debounced;
}

export default function App() {
  const [portfolioData, setPortfolioData] = useState<any>(null);
  const [tacticalPayload, setTacticalPayload] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sipAmount, setSipAmount] = useState(75000);
  const [lumpsum, setLumpsum] = useState(0);

  // Debounce so slider drags don't fire API on every pixel
  const debouncedSip = useDebounce(sipAmount, 600);
  const debouncedLumpsum = useDebounce(lumpsum, 600);

  const investorPan = "CFXPR4533R";

  useEffect(() => {
    const loadData = async () => {
      setLoading(true);
      try {
        const portfolio = await fetchMasterPortfolio(investorPan, debouncedSip, debouncedLumpsum);
        
        setPortfolioData(portfolio);
        setTacticalPayload(portfolio.tacticalPayload || {
          sipPlan: [],
          opportunisticSignals: [],
          activeSellSignals: [],
          exitQueue: [],
          harvestOpportunities: [],
          totalExitValue: 0,
          totalHarvestValue: 0,
        });
        setError(null);
      } catch (err) {
        console.error("Critical System Failure:", err);
        setError("Unable to initialize dashboard. Please try again later.");
      } finally {
        setLoading(false);
      }
    };

    loadData();
  }, [debouncedSip, debouncedLumpsum]);

  return (
    <AnimatePresence mode="wait">
      {loading && !portfolioData ? (
        <motion.div
          key="loader"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 bg-[#1e1e2e] flex flex-col z-[200] overflow-hidden"
        >
          {/* Header skeleton */}
          <div className="h-14 border-b border-white/5 flex items-center px-6 gap-6">
            <div className="skeleton w-32 h-5 rounded-lg" />
            <div className="flex gap-4 ml-8">
              {[80, 60, 70, 50].map((w, i) => (
                <div key={i} className="skeleton rounded-lg h-4" style={{width: w}} />
              ))}
            </div>
          </div>
          {/* Nav skeleton */}
          <div className="flex gap-1 px-6 py-3 border-b border-white/5">
            {[64, 80, 90, 90, 48, 72, 52].map((w, i) => (
              <div key={i} className="skeleton rounded-full h-8" style={{width: w}} />
            ))}
          </div>
          {/* Content skeleton */}
          <div className="flex-1 p-6 space-y-6">
            <div className="grid grid-cols-4 gap-4">
              {[1,2,3,4].map(i => <div key={i} className="skeleton rounded-2xl h-24" />)}
            </div>
            <div className="skeleton rounded-2xl h-64" />
            <div className="grid grid-cols-2 gap-4">
              <div className="skeleton rounded-2xl h-32" />
              <div className="skeleton rounded-2xl h-32" />
            </div>
          </div>
          {/* Brand footer */}
          <div className="absolute bottom-8 left-1/2 -translate-x-1/2 flex flex-col items-center gap-2">
            <div className="w-8 h-1 rounded-full bg-accent/30 relative overflow-hidden">
              <motion.div
                className="absolute inset-y-0 left-0 bg-accent rounded-full"
                animate={{ x: ['-100%', '100%'] }}
                transition={{ duration: 1.2, repeat: Infinity, ease: 'easeInOut' }}
                style={{ width: '60%' }}
              />
            </div>
            <p className="text-[9px] uppercase tracking-[0.4em] text-hint">Loading your portfolio</p>
          </div>
        </motion.div>
      ) : error ? (
        <div className="fixed inset-0 bg-[#09090f] flex flex-col items-center justify-center gap-4 px-8 text-center">
          <p className="text-red-400 font-medium text-xs uppercase tracking-widest">{error}</p>
          <button 
            onClick={() => window.location.reload()}
            className="px-4 py-2 bg-white/5 border border-white/10 rounded text-[10px] font-bold uppercase tracking-widest text-muted hover:text-white transition-colors"
          >
            Retry Connection
          </button>
        </div>
      ) : (
        <motion.div 
          key="dashboard"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.4 }}
        >
          <Dashboard 
            portfolioData={{ ...portfolioData, tacticalPayload }} 
            sipAmount={sipAmount} 
            setSipAmount={setSipAmount}
            lumpsum={lumpsum}
            setLumpsum={setLumpsum}
          />
        </motion.div>
      )}
    </AnimatePresence>
  );
}
