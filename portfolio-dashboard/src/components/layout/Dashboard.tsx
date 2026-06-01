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
  BarChart3,
  Menu,
  X
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
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const location = useLocation();

  const tabs = [
    { id: 'overview', label: 'Overview', icon: <Zap size={16}/>, path: '/dashboard/overview' },
    { id: 'portfolio', label: 'Portfolio', icon: <LayoutDashboard size={16}/>, path: '/dashboard/portfolio' },
    { id: 'performance', label: 'Performance', icon: <TrendingUp size={16}/>, path: '/dashboard/performance' },
    { id: 'sip-allocation', label: 'SIP Allocation', icon: <Target size={16}/>, path: '/dashboard/sip-allocation' },
    { id: 'rebalance', label: 'Rebalance', icon: <ArrowLeftRight size={16}/>, path: '/dashboard/rebalance' },
    { id: 'tax', label: 'Tax', icon: <ShieldCheck size={16}/>, path: '/dashboard/tax' },
    { id: 'ledger', label: 'Ledger', icon: <Receipt size={16}/>, path: '/dashboard/ledger' },
    { id: 'goals', label: 'Goals', icon: <Target size={16}/>, path: '/dashboard/goals' },
    { id: 'stocks', label: 'Stocks', icon: <BarChart3 size={16}/>, path: '/dashboard/stocks' },
    { id: 'upload', label: 'Data', icon: <Upload size={16}/>, path: '/dashboard/upload' },
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

  const sidebarContent = (isMobile = false) => (
    <div className="flex flex-col h-full justify-between select-none">
      <div className="space-y-8">
        {/* App Branding */}
        <div className="flex items-center gap-3 pb-4 border-b border-white/5">
          <div className="w-7 h-7 bg-accent rounded-md flex items-center justify-center shadow-[0_0_15px_rgba(129,140,248,0.4)]">
            <TrendingUp size={16} className="text-white" />
          </div>
          <StatusRing score={avgConviction} size={30} />
          <div className="flex flex-col">
            <h1 className="text-xs font-black uppercase tracking-[0.2em] text-accent">Portfolio OS</h1>
            <div className="flex items-center gap-1.5">
              <div className="w-1 h-1 rounded-full bg-buy animate-pulse shadow-[0_0_5px_rgba(166,227,161,0.8)]" />
              <span className="text-[8px] font-bold text-muted uppercase tracking-widest">Live Pulse</span>
            </div>
          </div>
        </div>

        {/* Stats Widget */}
        <div className="bg-[#181825]/50 border border-white/5 rounded-2xl p-4 space-y-3 shadow-inner">
          <p className="text-[8px] font-black text-muted uppercase tracking-widest border-b border-white/5 pb-1.5">Active Brief</p>
          <div className="grid grid-cols-2 gap-3">
            {stats.map(s => (
              <div key={s.label} className="space-y-0.5">
                <p className="text-[8px] font-bold uppercase tracking-wider text-muted/60">{s.label}</p>
                <p className={`text-[12px] font-black num-display tracking-tight tabular-nums truncate ${s.color || 'text-primary'}`}>
                  {mask(s.value)}
                </p>
              </div>
            ))}
          </div>
        </div>

        {/* Navigation Tabs */}
        <nav className="flex flex-col gap-1 pr-1 max-h-[45vh] overflow-y-auto custom-scrollbar">
          {tabs.map((tab) => {
            const isActive = location.pathname === tab.path;
            return (
              <Link
                key={tab.id}
                to={tab.path}
                onClick={() => isMobile && setIsMobileMenuOpen(false)}
                className={`relative flex items-center gap-3 px-4 py-2.5 rounded-xl text-[10px] font-bold uppercase tracking-wider transition-all duration-200 cursor-pointer outline-none
                  ${isActive 
                    ? 'text-accent bg-accent/10 border border-accent/20 shadow-sm' 
                    : 'text-muted hover:text-secondary hover:bg-white/[0.02]'}`}
              >
                {tab.icon}
                <span>{tab.label}</span>
                {isActive && (
                  <span className="absolute right-3 w-1 h-3 bg-accent rounded-full animate-pulse" />
                )}
              </Link>
            );
          })}
        </nav>
      </div>

      {/* Sidebar Footer Controls */}
      <div className="pt-6 border-t border-white/5 space-y-4">
        {/* Privacy Switch */}
        <div className="flex items-center justify-between bg-white/[0.02] border border-white/5 px-3 py-2 rounded-xl">
          <div className="flex items-center gap-2">
            {isPrivate ? <Lock size={12} className="text-muted" /> : <Unlock size={12} className="text-muted" />}
            <span className="text-[8px] font-black uppercase tracking-widest text-muted">{isPrivate ? 'Hidden' : 'Visible'}</span>
          </div>
          <Switch.Root 
            checked={isPrivate} 
            onCheckedChange={setIsPrivate}
            className="w-7 h-4 bg-white/10 rounded-full relative data-[state=checked]:bg-accent outline-none cursor-pointer transition-colors shadow-inner"
          >
            <Switch.Thumb className="block w-2.5 h-2.5 bg-white rounded-full transition-transform duration-150 translate-x-0.5 will-change-transform data-[state=checked]:translate-x-[14px] shadow-sm" />
          </Switch.Root>
        </div>

        {/* PAN Switcher & Settings */}
        <div className="flex items-center justify-between gap-3">
          <div className="flex-1">
            <PanSwitcher 
              currentPan={pan} 
              onSwitch={(newPan) => {
                localStorage.setItem('portfolio_pan', newPan);
                window.location.reload();
              }} 
              onLogout={onLogout}
            />
          </div>
          <button 
            onClick={() => setIsStrategyOpen(true)}
            className="flex items-center justify-center p-2 rounded-xl border border-white/5 bg-white/[0.02] hover:bg-white/[0.05] text-muted hover:text-primary transition-all cursor-pointer active:scale-95"
            title="Strategy Settings"
          >
            <Settings size={15} className="hover:rotate-45 transition-transform duration-300" />
          </button>
        </div>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen bg-background text-primary font-sans selection:bg-accent/30 flex flex-col md:flex-row overflow-x-hidden">
      <div className="noise-overlay" />
      
      <div className="fixed inset-0 pointer-events-none opacity-40" 
           style={{ background: 'radial-gradient(ellipse 70% 40% at 15% 0%, rgba(129,140,248,0.07) 0%, transparent 55%), radial-gradient(ellipse 50% 30% at 85% 100%, rgba(52,211,153,0.04) 0%, transparent 50%)' }} 
      />

      {/* Desktop Sidebar */}
      <aside className="hidden md:block w-64 h-screen fixed top-0 left-0 bg-[#181825]/60 border-r border-white/5 backdrop-blur-2xl p-6 z-[80] shadow-2xl">
        {sidebarContent(false)}
      </aside>

      {/* Mobile Sticky Top Header */}
      <header className="flex md:hidden sticky top-0 z-[80] border-b border-white/5 bg-[#11111b]/80 backdrop-blur-md px-6 py-4 justify-between items-center">
        <div className="flex items-center gap-2.5">
          <div className="w-6 h-6 bg-accent rounded-md flex items-center justify-center shadow-[0_0_15px_rgba(129,140,248,0.4)]">
            <TrendingUp size={13} className="text-white" />
          </div>
          <h1 className="text-[10px] font-black uppercase tracking-[0.2em] text-accent">Portfolio OS</h1>
        </div>
        <button
          onClick={() => setIsMobileMenuOpen(!isMobileMenuOpen)}
          className="p-2 rounded-lg bg-white/[0.03] border border-white/5 text-muted hover:text-primary transition-all active:scale-95 cursor-pointer"
        >
          {isMobileMenuOpen ? <X size={16} /> : <Menu size={16} />}
        </button>
      </header>

      {/* Mobile Overlay Sidebar Menu */}
      {isMobileMenuOpen && (
        <>
          <div 
            onClick={() => setIsMobileMenuOpen(false)} 
            className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[98] md:hidden transition-opacity" 
          />
          <aside className="fixed inset-y-0 left-0 w-72 bg-[#181825]/95 border-r border-white/10 backdrop-blur-3xl p-6 z-[99] md:hidden shadow-2xl flex flex-col justify-between">
            {sidebarContent(true)}
          </aside>
        </>
      )}

      {/* Main Content Area */}
      <main className="flex-1 md:pl-64 min-h-screen relative z-10 flex flex-col">
        <div className="p-6 md:p-10 max-w-7xl w-full mx-auto space-y-12 flex-1 outline-none">
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

      {/* Fund Detail Modal Drawer */}
      {selectedFundName && (
        <FundDetailView 
          fund={selectedFund}
          isOpen={!!selectedFundName}
          onClose={() => setSelectedFundName(null)}
          isPrivate={isPrivate}
        />
      )}

      {/* Strategy Manager Settings Drawer */}
      <Dialog.Root open={isStrategyOpen} onOpenChange={setIsStrategyOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[100] data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
          <Dialog.Content className="fixed right-0 top-0 bottom-0 w-full sm:w-[600px] bg-[#11111b] border-l border-white/5 z-[101] flex flex-col data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:slide-out-to-right data-[state=open]:slide-in-from-right duration-300 shadow-2xl">
            <div className="flex justify-between items-center px-6 py-4 border-b border-white/5 bg-[#181825]/50">
              <Dialog.Title className="text-xs font-black uppercase tracking-wider text-primary">Strategy Settings</Dialog.Title>
              <Dialog.Close asChild>
                <button className="text-muted hover:text-exit transition-colors cursor-pointer text-sm font-black">&times;</button>
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
