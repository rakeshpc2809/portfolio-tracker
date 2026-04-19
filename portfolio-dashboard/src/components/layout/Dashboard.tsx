import { useState } from 'react';
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
  Upload,
  LogOut
} from 'lucide-react';
import * as Switch from '@radix-ui/react-switch';
import * as Tabs from '@radix-ui/react-tabs';
import { formatCurrencyShort } from '../../utils/formatters';
import StatusRing from '../ui/StatusRing';

// Views
import TodayBriefView from '../views/TodayBriefView';
import PortfolioView from '../views/PortfolioView';
import PerformanceView from '../views/PerformanceView';
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
  setLumpsum,
  pan,
  onLogout,
  initialTab = 'today'
}: { 
  portfolioData: any;
  sipAmount: number;
  setSipAmount: (val: number) => void;
  lumpsum: number;
  setLumpsum: (val: number) => void;
  pan: string;
  onLogout: () => void;
  initialTab?: string;
}) {
  const [activeTab, setActiveTab] = useState(initialTab);
  const [isPrivate, setIsPrivate] = useState(false);
  const [selectedFundName, setSelectedFundName] = useState<string | null>(null);

  const tabs = [
    { id: 'today', label: 'Today', icon: <Zap size={14}/> },
    { id: 'portfolio', label: 'Portfolio', icon: <LayoutDashboard size={14}/> },
    { id: 'performance', label: 'Performance', icon: <TrendingUp size={14}/> },
    { id: 'funds', label: 'Each Fund', icon: <PieChart size={14}/> },
    { id: 'rebalance', label: 'Rebalance', icon: <ArrowLeftRight size={14}/> },
    { id: 'tax', label: 'Tax', icon: <ShieldCheck size={14}/> },
    { id: 'ledger', label: 'Ledger', icon: <Receipt size={14}/> },
    { id: 'upload', label: 'Data', icon: <Upload size={14}/> },
  ];

  const selectedFund = portfolioData?.schemeBreakdown?.find((s: any) => s.schemeName === selectedFundName);

  const mask = (val: string) => isPrivate ? "••••" : val;

  const stats = [
    { label: 'Value', value: formatCurrencyShort(portfolioData?.currentValueAmount || 0), glow: 'glow-accent' },
    { label: 'Return', value: portfolioData?.overallReturn || '0%', color: parseFloat(portfolioData?.overallReturn || '0') >= 0 ? 'text-buy' : 'text-exit', glow: parseFloat(portfolioData?.overallReturn || '0') >= 0 ? 'glow-buy' : 'glow-exit' },
    { label: 'XIRR', value: portfolioData?.overallXirr || '0%', color: parseFloat(portfolioData?.overallXirr || '0') >= 0 ? 'text-buy' : 'text-exit', glow: parseFloat(portfolioData?.overallXirr || '0') >= 0 ? 'glow-buy' : 'glow-exit' },
    { label: 'Tax', value: formatCurrencyShort(portfolioData?.totalSTCG || 0), color: 'text-warning' },
  ];

  const avgConviction = Math.round(
    (portfolioData?.schemeBreakdown ?? [])
      .reduce((a: number, s: any) => a + (s.convictionScore ?? 0), 0) /
    Math.max(1, (portfolioData?.schemeBreakdown ?? []).length)
  );

  const headerGlow = parseFloat(portfolioData?.overallXirr || '0') > 0 
    ? 'after:bg-buy/[0.02]' 
    : 'after:bg-exit/[0.02]';

  return (
    <div className="min-h-screen bg-background text-primary font-sans selection:bg-accent/30 overflow-x-hidden">
      <div className="noise-overlay" />
      
      <div className="fixed inset-0 pointer-events-none opacity-40" 
           style={{ background: 'radial-gradient(ellipse 70% 40% at 15% 0%, rgba(129,140,248,0.07) 0%, transparent 55%), radial-gradient(ellipse 50% 30% at 85% 100%, rgba(52,211,153,0.04) 0%, transparent 50%)' }} 
      />

      <header className={`sticky top-0 z-[90] border-b border-border bg-background/80 backdrop-blur-md px-8 py-4 flex justify-between items-center overflow-hidden after:absolute after:inset-0 after:pointer-events-none ${headerGlow}`}>
        <div className="flex items-center gap-10">
          <div className="flex items-center gap-3">
            <div className="w-6 h-6 bg-accent rounded-md flex items-center justify-center shadow-[0_0_15px_rgba(129,140,248,0.4)]">
              <TrendingUp size={14} className="text-white" />
            </div>
            <StatusRing score={avgConviction} size={28} />
            <h1 className="text-[10px] font-bold uppercase tracking-[0.2em] text-accent">Portfolio OS</h1>
          </div>

          <div className="hidden lg:flex items-center gap-3 border-l border-border pl-8">
            {stats.map(s => (
              <button
                key={s.label}
                className="group flex items-center gap-2 px-3 py-1.5 rounded-xl border border-transparent transition-all duration-200 hover:border-white/20 active:scale-95"
              >
                <span className="text-[9px] uppercase tracking-widest text-muted group-hover:text-secondary transition-colors">
                  {s.label}
                </span>
                <span className={`text-sm font-semibold num-display ${s.color || 'text-primary'} ${s.glow || ''}`}>
                  {mask(s.value)}
                </span>
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-6">
          <div className="flex items-center gap-3 bg-white/[0.03] border border-border px-3 py-1.5 rounded-lg hover:bg-white/[0.05] transition-colors">
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

          <div className="h-6 w-px bg-border mx-2" />

          <button 
            onClick={onLogout}
            className="flex items-center gap-2 px-3 py-1.5 rounded-lg hover:bg-exit/10 text-muted hover:text-exit transition-all group"
            title="Switch Account"
          >
            <LogOut size={14} className="group-hover:translate-x-0.5 transition-transform" />
            <span className="text-[9px] font-black uppercase tracking-widest hidden sm:inline">Exit</span>
          </button>
        </div>
      </header>

      <main className="relative z-10 p-8 max-w-7xl mx-auto">
        <Tabs.Root value={activeTab} onValueChange={setActiveTab} className="space-y-12">
          <Tabs.List className="flex items-center gap-0.5 p-1 bg-surface border border-border rounded-xl w-full md:w-fit overflow-x-auto scrollbar-none backdrop-blur-sm">
            {tabs.map((tab) => (
              <Tabs.Trigger
                key={tab.id}
                value={tab.id}
                className="relative flex items-center gap-1.5 px-4 py-2 rounded-lg text-[10px] font-bold uppercase tracking-wider transition-all duration-200 whitespace-nowrap outline-none cursor-pointer
                  data-[state=inactive]:text-muted data-[state=inactive]:hover:text-secondary data-[state=inactive]:hover:bg-white/[0.03]
                  data-[state=active]:text-accent data-[state=active]:bg-accent/10"
              >
                {tab.icon}
                <span className="hidden sm:inline">{tab.label}</span>
                {/* active underline */}
                <span className="data-[state=inactive]:hidden absolute bottom-0.5 left-3 right-3 h-px bg-accent/60 rounded-full" />
              </Tabs.Trigger>
            ))}
          </Tabs.List>

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
            <PortfolioView portfolioData={portfolioData} isPrivate={isPrivate} onFundClick={setSelectedFundName} pan={pan} />
          </Tabs.Content>

          <Tabs.Content value="performance" className="outline-none focus:ring-0">
            <PerformanceView pan={pan} isPrivate={isPrivate} portfolioData={portfolioData} />
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
            <TaxView portfolioData={portfolioData} isPrivate={isPrivate} pan={pan} />
          </Tabs.Content>
          
          <Tabs.Content value="ledger" className="outline-none focus:ring-0">
            <LedgerView investorPan={pan} isPrivate={isPrivate} />
          </Tabs.Content>

          <Tabs.Content value="upload" className="outline-none focus:ring-0">
            <CasUploadView pan={pan} />
          </Tabs.Content>
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
