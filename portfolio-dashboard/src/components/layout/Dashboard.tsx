import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { 
  LayoutDashboard, 
  Target, 
  Zap, 
  Database, 
  Activity, 
  Lock, 
  Unlock,
  Receipt
} from 'lucide-react';
import { formatCurrency } from '../../utils/formatters';

// Views
import OverviewTab from '../views/OverviewTab';
import DeviationTab from '../views/DeviationTab';
import HoldingsTab from '../views/HoldingsTab';
import TacticalPanel from '../views/TacticalPanel';
import TransactionsTab from '../views/TransactionTab';
import ErrorBoundary from '../ui/ErrorBoundary';
import { OverviewSkeleton } from '../ui/Skeleton';

export default function Dashboard({ portfolioData }: { portfolioData: any }) {
  const [activeTab, setActiveTab] = useState('overview');
  const [isPrivate, setIsPrivate] = useState(false);
  const investorPan = "CFXPR4533R";

  const tabs = [
    { id: 'overview', label: 'Intelligence', icon: <LayoutDashboard size={14}/> },
    { id: 'ledger', label: 'Ledger', icon: <Receipt size={14}/> },
    { id: 'deviation', label: 'Alignment', icon: <Target size={14}/> },
    { id: 'holdings', label: 'Matrix', icon: <Database size={14}/> },
    { id: 'tactical', label: 'Tactical', icon: <Zap size={14}/> },
  ];

  // Helper to mask sensitive data
  const maskValue = (value: string | number) => isPrivate ? "••••••••" : value;

  if (!portfolioData) {
    return (
      <div className="min-h-screen bg-[#020202] text-zinc-100 font-sans p-6 max-w-7xl mx-auto">
        <OverviewSkeleton />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#020202] text-zinc-100 font-sans selection:bg-blue-500/30">
      {/* 🌌 ATMOSPHERIC BACKGROUND */}
      <div className="fixed inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-[25%] -left-[10%] w-[70%] h-[70%] bg-blue-500/10 blur-[120px] rounded-full" />
        <div className="absolute -bottom-[25%] -right-[10%] w-[60%] h-[60%] bg-purple-500/5 blur-[120px] rounded-full" />
      </div>

      {/* 📟 TELEMETRY HEADER */}
      <header className="sticky top-0 z-50 border-b border-white/5 bg-black/20 backdrop-blur-md px-8 py-4 flex justify-between items-center">
        <div className="flex items-center gap-6">
          <div className="flex flex-col">
            <h1 className="text-[10px] font-black uppercase tracking-[0.4em] text-zinc-500">Quantum Portfolio OS</h1>
            <p className="text-sm font-black italic tracking-tighter text-white">
              {isPrivate ? "SENSITIVE_REDACTED" : (portfolioData.investorName || "RAKESH P C")}
            </p>
          </div>
          <div className="h-8 w-px bg-zinc-800 hidden sm:block" />
          <div className="hidden sm:flex flex-col">
            <p className="text-[8px] font-black text-zinc-500 uppercase tracking-widest">System XIRR</p>
            <p className={`text-xs font-black ${parseFloat(portfolioData.overallXirr) < 0 ? 'text-rose-500' : 'text-emerald-500'}`}>
              {maskValue(portfolioData.overallXirr)}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-8">
          <div className="text-right">
             <p className="text-[8px] font-black text-zinc-600 uppercase tracking-widest">Total Capital Value</p>
             <p className="text-xl font-black text-white tracking-tighter">
               {isPrivate ? "₹ ••••••••" : formatCurrency(portfolioData.currentValueAmount)}
             </p>
          </div>
          <div className="flex items-center gap-3 bg-zinc-900/50 border border-zinc-800 px-4 py-2 rounded-2xl">
             <div className="flex flex-col items-end">
               <span className="text-[7px] font-black uppercase text-zinc-500">Tax Lots</span>
               <span className="text-xs font-black text-blue-400">{portfolioData.openTaxLots}</span>
             </div>
             <Activity size={14} className="text-blue-500 animate-pulse"/>
          </div>
        </div>
      </header>

      {/* 🏗️ MAIN WORKSPACE */}
      <main className="relative z-10 p-6 pb-32 max-w-7xl mx-auto">
        <ErrorBoundary>
          <AnimatePresence mode="wait">
            <motion.div
              key={activeTab}
              initial={{ opacity: 0, y: 20, filter: "blur(10px)" }}
              animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
              exit={{ opacity: 0, y: -20, filter: "blur(10px)" }}
              transition={{ duration: 0.4, ease: [0.23, 1, 0.32, 1] }}
            >
              {activeTab === 'overview' && (
                <OverviewTab 
                  data={portfolioData.schemeBreakdown} 
                  portfolioSummary={portfolioData} 
                />
              )}
              
              {activeTab === 'ledger' && (
                <TransactionsTab investorPan={investorPan} />
              )}

              {activeTab === 'deviation' && <DeviationTab data={portfolioData.schemeBreakdown} />}
              
              {activeTab === 'holdings' && <HoldingsTab data={portfolioData.schemeBreakdown} />}
              
              {activeTab === 'tactical' && (
                <TacticalPanel 
                  signals={portfolioData.schemeBreakdown} 
                  totalPortfolioValue={portfolioData.currentValueAmount} 
                />
              )}
            </motion.div>
          </AnimatePresence>
        </ErrorBoundary>
      </main>

      {/* 🕹️ FLOATING COMMAND DOCK */}
      <nav className="fixed bottom-8 left-1/2 -translate-x-1/2 z-[100]">
        <div className="flex items-center gap-1 p-1.5 bg-zinc-900/80 backdrop-blur-2xl border border-white/10 rounded-[2rem] shadow-[0_20px_50px_rgba(0,0,0,0.5)]">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className="relative px-6 py-2.5 rounded-full transition-all duration-500 group"
            >
              {activeTab === tab.id && (
                <motion.div 
                  layoutId="activeTab"
                  className="absolute inset-0 bg-white shadow-[0_0_20px_rgba(255,255,255,0.2)] rounded-full"
                  transition={{ type: "spring", bounce: 0.2, duration: 0.6 }}
                />
              )}
              <div className={`relative z-10 flex items-center gap-2 text-[10px] font-black uppercase tracking-widest transition-colors duration-300 ${
                activeTab === tab.id ? 'text-black' : 'text-zinc-500 group-hover:text-zinc-300'
              }`}>
                {tab.icon}
                <span className="hidden sm:inline">{tab.label}</span>
              </div>
            </button>
          ))}
        </div>
      </nav>

      {/* 🔒 PRIVACY OVERLAY TOGGLE */}
      <button 
        onClick={() => setIsPrivate(!isPrivate)}
        className="fixed bottom-8 right-8 p-4 bg-zinc-900/50 border border-zinc-800 rounded-full hover:bg-zinc-800 transition-all group z-[101]"
      >
         {isPrivate ? (
           <Lock size={16} className="text-blue-500" />
         ) : (
           <Unlock size={16} className="text-zinc-600 group-hover:text-white transition-colors" />
         )}
      </button>
    </div>
  );
}