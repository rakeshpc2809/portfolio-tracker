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
    fetchMasterPortfolio(investorPan)
      .then(data => {
        setPortfolioData(data);
        setLoading(false);
      })
      .catch((err) => {
        console.error("System Failure:", err);
        setError("Unable to load portfolio data. Please check connection.");
        setLoading(false);
      });
  }, []);

  return (
    <AnimatePresence mode="wait">
      {loading ? (
        <motion.div 
          key="loader"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 bg-[#09090f] flex flex-col items-center justify-center z-[200]"
        >
          <div className="w-10 h-10 border-2 border-indigo-500/20 border-t-indigo-500 rounded-full animate-spin mb-4" />
          <p className="text-[10px] font-bold uppercase tracking-[0.3em] text-muted animate-pulse">Loading your portfolio...</p>
        </motion.div>
      ) : error ? (
        <div className="fixed inset-0 bg-[#09090f] flex items-center justify-center text-red-400 font-medium text-xs uppercase tracking-widest px-8 text-center">
          {error}
        </div>
      ) : (
        <motion.div 
          key="dashboard"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.4 }}
        >
          <Dashboard portfolioData={portfolioData} />
        </motion.div>
      )}
    </AnimatePresence>
  );
}
