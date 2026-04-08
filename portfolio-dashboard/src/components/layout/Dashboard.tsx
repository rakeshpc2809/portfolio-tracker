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
  PieChart,
  Upload
} from 'lucide-react';
import * as Switch from '@radix-ui/react-switch';
import * as Tabs from '@radix-ui/react-tabs';
import { formatCurrencyShort } from '../../utils/formatters';

// Views
import TodayBriefView from '../views/TodayBriefView';
import PortfolioView from '../views/PortfolioView';
import RebalanceView from '../views/RebalanceView';
import TaxView from '../views/TaxView';
import LedgerView from '../views/LedgerView';
import FundDetailView from '../views/FundDetailView';
import FundsListView from '../views/FundsListView';
import CasUploadView from '../views/CasUploadView';

export default function Dashboard({ 
  portfolioData,
  sipAmount,
  setSipAmount,
  lumpsum,
  setLumpsum 
}: { 
  portfolioData: any;
  sipAmount: number;
  setSipAmount: (val: number) => void;
  lumpsum: number;
  setLumpsum: (val: number) => void;
}) {
  const [activeTab, setActiveTab] = useState('today');
  const [isPrivate, setIsPrivate] = useState(false);
  const [selectedFundName, setSelectedFundName] = useState<string | null>(null);

  const investorPan = "CFXPR4533R";

  const tabs = [
    { id: 'today', label: 'Today', icon: <Zap size={14}/> },
    { id: 'portfolio', label: 'Portfolio', icon: <LayoutDashboard size={14}/> },
    { id: 'funds', label: 'Each Fund', icon: <PieChart size={14}/> },
    { id: 'rebalance', label: 'Rebalance', icon: <ArrowLeftRight size={14}/> },
    { id: 'tax', label: 'Tax', icon: <ShieldCheck size={14}/> },
    { id: 'ledger', label: 'Ledger', icon: <Receipt size={14}/> },
    { id: 'upload', label: 'Data', icon: <Upload size={14}/> },
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
            <div className="w-6 h-6 bg-accent rounded-md flex items-center justify-center shadow-[0_0_15px_rgba(129,140,248,0.4)]">
              <TrendingUp size={14} className="text-white" />
            </div>
            <h1 className="text-[10px] font-bold uppercase tracking-[0.2em] text-accent">Portfolio OS</h1>
          </div>

          <div className="hidden lg:flex items-center gap-8 border-l border-white/5 pl-8">
            {stats.map(s => (
              <div key={s.label} className="space-y-0.5 group cursor-default">
                <p className="text-[9px] uppercase tracking-widest text-muted transition-colors group-hover:text-secondary">{s.label}</p>
                <p className={`text-sm font-medium tabular-nums ${s.color || 'text-primary'}`}>{mask(s.value)}</p>
              </div>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-6">
          <div className="flex items-center gap-3 bg-white/[0.03] border border-white/5 px-3 py-1.5 rounded-lg hover:bg-white/[0.05] transition-colors">
            <span className="text-[9px] font-bold uppercase tracking-widest text-muted">{isPrivate ? 'Hidden' : 'Visible'}</span>
            <Switch.Root 
              checked={isPrivate} 
              onCheckedChange={setIsPrivate}
              className="w-8 h-4 bg-white/10 rounded-full relative data-[state=checked]:bg-accent outline-none cursor-pointer transition-colors shadow-inner"
            >
              <Switch.Thumb className="block w-3 h-3 bg-white rounded-full transition-transform duration-150 translate-x-0.5 will-change-transform data-[state=checked]:translate-x-[18px] shadow-sm" />
            </Switch.Root>
            {isPrivate ? <Lock size={12} className="text-muted" /> : <Unlock size={12} className="text-muted" />}
          </div>
        </div>
      </header>

      <main className="relative z-10 p-8 max-w-7xl mx-auto">
        <Tabs.Root value={activeTab} onValueChange={setActiveTab} className="space-y-12">
          <Tabs.List className="flex flex-wrap items-center gap-1 p-1 bg-white/[0.02] border border-white/5 rounded-xl w-fit backdrop-blur-sm">
            {tabs.map((tab) => (
              <Tabs.Trigger
                key={tab.id}
                value={tab.id}
                className="flex items-center gap-2 px-5 py-2 rounded-lg text-[11px] font-bold uppercase tracking-wider transition-all duration-200 data-[state=active]:bg-accent/10 data-[state=active]:text-accent data-[state=inactive]:text-muted data-[state=inactive]:hover:text-secondary outline-none cursor-pointer"
              >
                {tab.icon}
                {tab.label}
              </Tabs.Trigger>
            ))}
          </Tabs.List>

          <AnimatePresence mode="wait">
            <motion.div
              key={activeTab}
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ duration: 0.2, ease: "easeOut" }}
            >
              <Tabs.Content value="today" className="outline-none focus:ring-0">
                <TodayBriefView 
                  portfolioData={portfolioData}
                  sipAmount={sipAmount}
                  setSipAmount={setSipAmount}
                  lumpsum={lumpsum}
                  setLumpsum={setLumpsum}
                  onFundClick={setSelectedFundName}
                  isPrivate={isPrivate}
                />
              </Tabs.Content>
              
              <Tabs.Content value="portfolio" className="outline-none focus:ring-0">
                <PortfolioView portfolioData={portfolioData} isPrivate={isPrivate} />
              </Tabs.Content>

              <Tabs.Content value="funds" className="outline-none focus:ring-0">
                <FundsListView 
                  portfolioData={portfolioData} 
                  onFundClick={setSelectedFundName}
                  isPrivate={isPrivate}
                />
              </Tabs.Content>

              <Tabs.Content value="rebalance" className="outline-none focus:ring-0">
                <RebalanceView 
                  portfolioData={portfolioData}
                  sipAmount={sipAmount}
                  setSipAmount={setSipAmount}
                  isPrivate={isPrivate}
                />
              </Tabs.Content>
              
              <Tabs.Content value="tax" className="outline-none focus:ring-0">
                <TaxView portfolioData={portfolioData} isPrivate={isPrivate} pan={investorPan} />
              </Tabs.Content>
              
              <Tabs.Content value="ledger" className="outline-none focus:ring-0">
                <LedgerView investorPan={investorPan} isPrivate={isPrivate} />
              </Tabs.Content>

              <Tabs.Content value="upload" className="outline-none focus:ring-0">
                <CasUploadView />
              </Tabs.Content>
            </motion.div>
          </AnimatePresence>
        </Tabs.Root>
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
