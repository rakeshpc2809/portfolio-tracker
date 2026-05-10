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
  Upload,
  Settings,
  Target,
  BarChart3
} from 'lucide-react';
import * as Switch from '@radix-ui/react-switch';
import * as Dialog from '@radix-ui/react-dialog';
import { Link, Outlet, useLocation } from '@tanstack/react-router';
import { formatCurrencyShort } from '../../utils/formatters';
import StatusRing from '../ui/StatusRing';
import PanSwitcher from '../ui/PanSwitcher';
import { DashboardProvider } from '../../context/DashboardContext';

// Views
import FundDetailView from '../views/FundDetailView';
import StrategyManagerView from '../views/StrategyManagerView';

export default function Dashboard({ 
  portfolioData,
  sipAmount,
  setSipAmount,
  lumpsum,
  setLumpsum,
  pan,
  onLogout,
  children
}: { 
  portfolioData: any;
  sipAmount: number;
  setSipAmount: (val: number) => void;
  lumpsum: number;
  setLumpsum: (val: number) => void;
  pan: string;
  onLogout: () => void;
  children?: React.ReactNode;
}) {
  const [isPrivate, setIsPrivate] = useState(false);
  const [selectedFundName, setSelectedFundName] = useState<string | null>(null);
  const [isStrategyOpen, setIsStrategyOpen] = useState(false);
  const location = useLocation();

  const tabs = [
    { id: 'overview', label: 'Overview', icon: <Zap size={14}/>, path: '/dashboard/overview' },
    { id: 'portfolio', label: 'Portfolio', icon: <LayoutDashboard size={14}/>, path: '/dashboard/portfolio' },
    { id: 'performance', label: 'Performance', icon: <TrendingUp size={14}/>, path: '/dashboard/performance' },
    { id: 'sip-allocation', label: 'SIP Allocation', icon: <Target size={14}/>, path: '/dashboard/sip-allocation' },
    { id: 'rebalance', label: 'Rebalance', icon: <ArrowLeftRight size={14}/>, path: '/dashboard/rebalance' },
    { id: 'tax', label: 'Tax', icon: <ShieldCheck size={14}/>, path: '/dashboard/tax' },
    { id: 'ledger', label: 'Ledger', icon: <Receipt size={14}/>, path: '/dashboard/ledger' },
    { id: 'goals', label: 'Goals', icon: <Target size={14}/>, path: '/dashboard/goals' },
    { id: 'stocks', label: 'Stocks', icon: <BarChart3 size={14}/>, path: '/dashboard/stocks' },
    { id: 'upload', label: 'Data', icon: <Upload size={14}/>, path: '/dashboard/upload' },
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
            <div className="flex flex-col">
              <h1 className="text-[10px] font-black uppercase tracking-[0.2em] text-accent">Portfolio OS</h1>
              <div className="flex items-center gap-1.5">
                <div className="w-1 h-1 rounded-full bg-buy animate-pulse shadow-[0_0_5px_rgba(166,227,161,0.8)]" />
                <span className="text-[7px] font-bold text-muted uppercase tracking-widest">Live Pulse</span>
              </div>
            </div>
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

          <PanSwitcher 
            currentPan={pan} 
            onSwitch={(newPan) => {
              localStorage.setItem('portfolio_pan', newPan);
              window.location.reload(); // Refresh to clear all states and refetch
            }} 
            onLogout={onLogout}
          />

          <div className="h-6 w-px bg-border mx-2" />

          <button 
            onClick={() => setIsStrategyOpen(true)}
            className="flex items-center gap-2 px-3 py-1.5 rounded-lg hover:bg-white/[0.05] text-muted hover:text-primary transition-all"
            title="Strategy Settings"
          >
            <Settings size={14} className="hover:rotate-45 transition-transform duration-300" />
          </button>
        </div>
      </header>

      <main className="relative z-10 p-8 max-w-7xl mx-auto space-y-12">
        <nav className="flex items-center gap-0.5 p-1 bg-surface border border-border rounded-xl w-full md:w-fit overflow-x-auto scrollbar-none backdrop-blur-sm">
          {tabs.map((tab) => {
            const isActive = location.pathname === tab.path;
            return (
              <Link
                key={tab.id}
                to={tab.path}
                className={`relative flex items-center gap-1.5 px-4 py-2 rounded-lg text-[10px] font-bold uppercase tracking-wider transition-all duration-200 whitespace-nowrap outline-none cursor-pointer
                  ${isActive 
                    ? 'text-accent bg-accent/10' 
                    : 'text-muted hover:text-secondary hover:bg-white/[0.03]'}`}
              >
                {tab.icon}
                <span className="hidden sm:inline">{tab.label}</span>
                {isActive && (
                  <span className="absolute bottom-0.5 left-3 right-3 h-px bg-accent/60 rounded-full" />
                )}
              </Link>
            );
          })}
        </nav>

        <div className="outline-none focus:ring-0">
          <DashboardProvider value={{ 
            portfolioData, 
            sipAmount, 
            setSipAmount, 
            lumpsum, 
            setLumpsum, 
            isPrivate, 
            setSelectedFundName,
            pan 
          }}>
            {children || <Outlet />}
          </DashboardProvider>
        </div>
      </main>

      {selectedFundName && (
        <FundDetailView 
          fund={selectedFund}
          isOpen={!!selectedFundName}
          onClose={() => setSelectedFundName(null)}
          isPrivate={isPrivate}
        />
      )}

      <Dialog.Root open={isStrategyOpen} onOpenChange={setIsStrategyOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[100] data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
          <Dialog.Content className="fixed right-0 top-0 bottom-0 w-full sm:w-[600px] bg-background border-l border-border z-[101] flex flex-col data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:slide-out-to-right data-[state=open]:slide-in-from-right duration-300 shadow-2xl">
            <div className="flex justify-between items-center px-6 py-4 border-b border-border bg-surface/50">
              <Dialog.Title className="text-sm font-bold uppercase tracking-wider text-primary">Strategy Settings</Dialog.Title>
              <Dialog.Close asChild>
                <button className="text-muted hover:text-exit transition-colors">
                  &times;
                </button>
              </Dialog.Close>
            </div>
            <div className="flex-1 overflow-y-auto custom-scrollbar p-6">
              <StrategyManagerView pan={pan} schemes={portfolioData?.schemeBreakdown || []} />
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}
