import { useState, useEffect } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { fetchMasterPortfolio, fetchUnifiedDashboard } from "./services/api";
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
        const [portfolio, tactical] = await Promise.all([
          fetchMasterPortfolio(investorPan, debouncedSip, debouncedLumpsum).catch((err) => {
            console.warn("Portfolio fetch failed, using fallback:", err);
            return {
              investorName: "New Investor",
              schemeBreakdown: [],
              totalInvestedAmount: 0,
              currentValueAmount: 0,
              totalUnrealizedGain: 0,
              overallXirr: "0%",
              totalSTCG: 0,
            };
          }),
          fetchUnifiedDashboard(investorPan, debouncedSip, debouncedLumpsum).catch((err) => {
            console.warn("Tactical fetch failed, using fallback:", err);
            return {
              sipPlan: [],
              opportunisticSignals: [],
              activeSellSignals: [],
              exitQueue: [],
              harvestOpportunities: [],
              totalExitValue: 0,
              totalHarvestValue: 0,
            };
          }),
        ]);
        setPortfolioData(portfolio);
        setTacticalPayload(tactical);
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
          className="fixed inset-0 bg-[#09090f] flex flex-col items-center justify-center z-[200]"
        >
          <div className="w-10 h-10 border-2 border-indigo-500/20 border-t-indigo-500 rounded-full animate-spin mb-4" />
          <p className="text-[10px] font-bold uppercase tracking-[0.3em] text-muted animate-pulse">Initializing your dashboard...</p>
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
