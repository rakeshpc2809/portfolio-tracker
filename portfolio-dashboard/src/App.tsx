import { useState, useEffect } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { fetchMasterPortfolio } from "./services/api";
import Dashboard from "./components/layout/Dashboard";

export default function App() {
  const [portfolioData, setPortfolioData] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const investorPan = "CFXPR4533R"; 

  useEffect(() => {
    const timer = setTimeout(() => {
      fetchMasterPortfolio(investorPan)
        .then(data => {
          setPortfolioData(data);
          setLoading(false);
        })
        .catch((err) => {
          console.error("Architectural Sync Failed:", err);
          setError("Failed to establish secure uplink to backend.");
          setLoading(false);
        });
    }, 800);

    return () => clearTimeout(timer);
  }, []);

  return (
    <AnimatePresence mode="wait">
      {loading ? (
        <motion.div 
          key="loader"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0, scale: 0.95 }}
          className="h-screen bg-[#020202] flex flex-col items-center justify-center gap-6"
        >
          <div className="relative w-20 h-20">
            <div className="absolute inset-0 border-2 border-blue-500/20 rounded-full" />
            <motion.div 
              className="absolute inset-0 border-t-2 border-blue-500 rounded-full"
              animate={{ rotate: 360 }}
              transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
            />
            <div className="absolute inset-4 border border-purple-500/20 rounded-full animate-pulse" />
          </div>
          <div className="flex flex-col items-center gap-2">
            <p className="text-[10px] font-black text-blue-500 uppercase tracking-[0.5em] animate-pulse">Syncing Architectures</p>
            <p className="text-[8px] font-bold text-zinc-600 uppercase tracking-widest">Establishing Secure Uplink...</p>
          </div>
        </motion.div>
      ) : error ? (
        <div className="h-screen bg-[#020202] flex items-center justify-center text-rose-500 font-mono text-xs uppercase tracking-widest">
          {error}
        </div>
      ) : (
        <motion.div 
          key="dashboard"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.8 }}
        >
          <Dashboard portfolioData={portfolioData} />
        </motion.div>
      )}
    </AnimatePresence>
  );
}