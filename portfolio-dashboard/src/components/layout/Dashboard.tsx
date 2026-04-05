import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { 
  LayoutDashboard, 
  TrendingUp, 
  Zap, 
  ArrowLeftRight, 
  Receipt,
  ShieldCheck,
  Lock,
  Unlock,
  PieChart
} from 'lucide-react';
import * as Switch from '@radix-ui/react-switch';
import { formatCurrencyShort } from '../../utils/formatters';

// Views
import TodayBriefView from '../views/TodayBriefView';
import PortfolioView from '../views/PortfolioView';
import RebalanceView from '../views/RebalanceView';
import TaxView from '../views/TaxView';
import LedgerView from '../views/LedgerView';
import FundDetailView from '../views/FundDetailView';
import FundsListView from '../views/FundsListView';

export default function Dashboard({ portfolioData }: { portfolioData: any }) {
  const [activeTab, setActiveTab] = useState('today');
  const [isPrivate, setIsPrivate] = useState(false);
  const [selectedFundName, setSelectedFundName] = useState<string | null>(null);
  const [sipAmount, setSipAmount] = useState(75000);
  const [lumpsum, setLumpsum] = useState(0);

  const investorPan = "CFXPR4533R";

  const tabs = [
    { id: 'today', label: 'Today', icon: <Zap size={14}/> },
    { id: 'portfolio', label: 'Portfolio', icon: <LayoutDashboard size={14}/> },
    { id: 'funds', label: 'Each Fund', icon: <PieChart size={14}/> },
    { id: 'rebalance', label: 'Rebalance', icon: <ArrowLeftRight size={14}/> },
    { id: 'tax', label: 'Tax', icon: <ShieldCheck size={14}/> },
    { id: 'ledger', label: 'Ledger', icon: <Receipt size={14}/> },
  ];

  const selectedFund = portfolioData?.schemeBreakdown?.find((s: any) => s.schemeName === selectedFundName);

  const mask = (val: string) => isPrivate ? "••••" : val;

  const stats = [
    { label: 'Value', value: formatCurrencyShort(portfolioData.currentValueAmount || 0) },
    { label: 'XIRR', value: portfolioData.overallXirr || '0%', color: parseFloat(portfolioData.overallXirr || '0') >= 0 ? 'text-buy' : 'text-exit' },
    { label: 'P&L', value: ((portfolioData.totalUnrealizedGain || 0) >= 0 ? '+' : '') + formatCurrencyShort(portfolioData.totalUnrealizedGain || 0), color: (portfolioData.totalUnrealizedGain || 0) >= 0 ? 'text-buy' : 'text-exit' },
    { label: 'Tax', value: formatCurrencyShort(portfolioData.totalSTCG || 0), color: 'text-warning' },
  ];

  return (
    <div className="min-h-screen bg-[#09090f] text-primary font-sans selection:bg-accent/30">
      <div className="fixed inset-0 pointer-events-none opacity-40" 
           style={{ background: 'radial-gradient(ellipse 80% 50% at 20% 0%, rgba(129,140,248,0.08) 0%, transparent 60%)' }} 
      />

      <header className="sticky top-0 z-[90] border-b border-white/5 bg-[#09090f]/80 backdrop-blur-md px-8 py-4 flex justify-between items-center">
        <div className="flex items-center gap-10">
          <div className="flex items-center gap-2">
            <div className="w-6 h-6 bg-accent rounded-md flex items-center justify-center">
              <TrendingUp size={14} className="text-white" />
            </div>
            <h1 className="text-[10px] font-bold uppercase tracking-[0.2em] text-accent">Portfolio OS</h1>
          </div>

          <div className="hidden lg:flex items-center gap-8 border-l border-white/5 pl-8">
            {stats.map(s => (
              <div key={s.label} className="space-y-0.5">
                <p className="text-[9px] uppercase tracking-widest text-muted">{s.label}</p>
                <p className={`text-sm font-medium tabular-nums ${s.color || 'text-primary'}`}>{mask(s.value)}</p>
              </div>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-6">
          <div className="flex items-center gap-3 bg-white/[0.03] border border-white/5 px-3 py-1.5 rounded-lg">
            <span className="text-[9px] font-bold uppercase tracking-widest text-muted">{isPrivate ? 'Hidden' : 'Visible'}</span>
            <Switch.Root 
              checked={isPrivate} 
              onCheckedChange={setIsPrivate}
              className="w-8 h-4 bg-white/10 rounded-full relative data-[state=checked]:bg-accent outline-none cursor-pointer transition-colors"
            >
              <Switch.Thumb className="block w-3 h-3 bg-white rounded-full transition-transform duration-150 translate-x-0.5 will-change-transform data-[state=checked]:translate-x-[18px]" />
            </Switch.Root>
            {isPrivate ? <Lock size={12} className="text-muted" /> : <Unlock size={12} className="text-muted" />}
          </div>
        </div>
      </header>

      <main className="relative z-10 p-8 max-w-7xl mx-auto">
        <nav className="flex flex-wrap items-center gap-1 mb-12 p-1 bg-white/[0.02] border border-white/5 rounded-xl w-fit">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 px-5 py-2 rounded-lg text-[11px] font-bold uppercase tracking-wider transition-all duration-150 ${
                activeTab === tab.id ? 'bg-accent/10 text-accent' : 'text-muted hover:text-secondary'
              }`}
            >
              {tab.icon}
              {tab.label}
            </button>
          ))}
        </nav>

        <AnimatePresence mode="wait">
          <motion.div
            key={activeTab}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.2, ease: "easeOut" }}
          >
            {activeTab === 'today' && (
              <TodayBriefView 
                portfolioData={portfolioData}
                sipAmount={sipAmount}
                setSipAmount={setSipAmount}
                lumpsum={lumpsum}
                setLumpsum={setLumpsum}
                onFundClick={setSelectedFundName}
                isPrivate={isPrivate}
              />
            )}
            
            {activeTab === 'portfolio' && (
              <PortfolioView portfolioData={portfolioData} isPrivate={isPrivate} />
            )}

            {activeTab === 'funds' && (
              <FundsListView 
                portfolioData={portfolioData} 
                onFundClick={setSelectedFundName}
                isPrivate={isPrivate}
              />
            )}

            {activeTab === 'rebalance' && (
              <RebalanceView 
                portfolioData={portfolioData}
                sipAmount={sipAmount}
                setSipAmount={setSipAmount}
                isPrivate={isPrivate}
              />
            )}
            
            {activeTab === 'tax' && (
              <TaxView portfolioData={portfolioData} isPrivate={isPrivate} pan={investorPan} />
            )}
            
            {activeTab === 'ledger' && (
              <LedgerView investorPan={investorPan} isPrivate={isPrivate} />
            )}
          </motion.div>
        </AnimatePresence>
      </main>

      {selectedFundName && (
        <FundDetailView 
          fund={selectedFund}
          isOpen={!!selectedFundName}
          onClose={() => setSelectedFundName(null)}
          isPrivate={isPrivate}
        />
      )}
    </div>
  );
}
