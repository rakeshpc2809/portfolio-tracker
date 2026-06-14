import { 
  createRootRoute, 
  createRoute, 
  createRouter, 
  Outlet, 
  Navigate 
} from '@tanstack/react-router';
import Dashboard from './components/layout/Dashboard';
import LoginScreen from './components/ui/LoginScreen';
import { usePortfolioSummary } from './hooks/usePortfolio';
import { useState } from 'react';
import { useDashboardContext } from './context/DashboardContext';

// Views
import OverviewView from './components/views/OverviewView';
import PortfolioView from './components/views/PortfolioView';
import PerformanceView from './components/views/PerformanceView';
import RebalanceView from './components/views/RebalanceView';
import TaxView from './components/views/TaxView';
import LedgerView from './components/views/LedgerView';
import CasUploadView from './components/views/CasUploadView';
import StocksView from './components/views/StocksView';

// Root Route
const rootRoute = createRootRoute({
  component: () => <Outlet />,
});

// Auth Layout / Guard
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: () => {
    const pan = localStorage.getItem('portfolio_pan');
    const token = localStorage.getItem('portfolio_token');
    if (pan && (pan === 'SETUP' || token)) return <Navigate to="/dashboard/overview" />;
    return <LoginScreen 
      onLogin={(newPan) => {
        localStorage.setItem('portfolio_pan', newPan);
        window.location.href = '/dashboard/overview';
      }} 
      onSetup={() => {
        localStorage.setItem('portfolio_pan', 'SETUP');
        window.location.href = '/dashboard/upload';
      }} 
    />;
  },
});

// Dashboard Layout Route
const dashboardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'dashboard',
  component: DashboardLayout,
});

function DashboardLayout() {
  const pan = localStorage.getItem('portfolio_pan');
  const token = localStorage.getItem('portfolio_token');
  const [sipAmount, setSipAmount] = useState(75000);
  const [lumpsum, setLumpsum] = useState(0);

  const { data: portfolioData, isLoading } = usePortfolioSummary(pan, sipAmount, lumpsum);

  const handleLogout = () => {
    localStorage.removeItem('portfolio_pan');
    localStorage.removeItem('portfolio_token');
    window.location.href = '/';
  };

  if (!pan || (pan !== 'SETUP' && !token)) {
    localStorage.removeItem('portfolio_pan');
    localStorage.removeItem('portfolio_token');
    return <Navigate to="/" />;
  }

  // Better loader would go here
  if (isLoading && !portfolioData) return <div className="bg-background min-h-screen text-muted flex items-center justify-center font-mono text-[10px] uppercase tracking-[0.4em]">Initializing Portfolio OS...</div>;

  return (
    <Dashboard 
      portfolioData={portfolioData} 
      sipAmount={sipAmount} 
      setSipAmount={setSipAmount}
      lumpsum={lumpsum}
      setLumpsum={setLumpsum}
      pan={pan}
      onLogout={handleLogout}
    >
      <Outlet />
    </Dashboard>
  );
}

// Sub-routes for tabs
const overviewRoute = createRoute({
  getParentRoute: () => dashboardRoute,
  path: 'overview',
  component: () => {
    const { portfolioData, sipAmount, setSipAmount, lumpsum, setLumpsum, isPrivate, setSelectedFundName } = useDashboardContext();
    return <OverviewView 
      portfolioData={portfolioData}
      sipAmount={sipAmount}
      setSipAmount={setSipAmount}
      lumpsum={lumpsum}
      setLumpsum={setLumpsum}
      onFundClick={setSelectedFundName}
      isPrivate={isPrivate}
    />;
  },
});

const portfolioRoute = createRoute({
  getParentRoute: () => dashboardRoute,
  path: 'portfolio',
  component: () => {
    const { portfolioData, isPrivate, setSelectedFundName, pan } = useDashboardContext();
    return <PortfolioView portfolioData={portfolioData} isPrivate={isPrivate} onFundClick={setSelectedFundName} pan={pan} />;
  },
});

const performanceRoute = createRoute({
  getParentRoute: () => dashboardRoute,
  path: 'performance',
  component: () => {
    const { portfolioData, isPrivate, pan } = useDashboardContext();
    return <PerformanceView pan={pan} isPrivate={isPrivate} portfolioData={portfolioData} />;
  },
});



const rebalanceRoute = createRoute({
  getParentRoute: () => dashboardRoute,
  path: 'rebalance',
  component: () => {
    const { portfolioData, sipAmount, setSipAmount, isPrivate } = useDashboardContext();
    return <RebalanceView portfolioData={portfolioData} sipAmount={sipAmount} setSipAmount={setSipAmount} isPrivate={isPrivate} />;
  },
});

const taxRoute = createRoute({
  getParentRoute: () => dashboardRoute,
  path: 'tax',
  component: () => {
    const { portfolioData, isPrivate, pan } = useDashboardContext();
    return <TaxView portfolioData={portfolioData} isPrivate={isPrivate} pan={pan} />;
  },
});

const ledgerRoute = createRoute({
  getParentRoute: () => dashboardRoute,
  path: 'ledger',
  component: () => {
    const { isPrivate, pan } = useDashboardContext();
    return <LedgerView investorPan={pan} isPrivate={isPrivate} />;
  },
});

const uploadRoute = createRoute({
  getParentRoute: () => dashboardRoute,
  path: 'upload',
  component: () => {
    const { portfolioData, pan } = useDashboardContext();
    return <CasUploadView pan={pan} portfolioData={portfolioData} />;
  },
});

const stocksRoute = createRoute({
  getParentRoute: () => dashboardRoute,
  path: 'stocks',
  component: () => {
    const { pan, isPrivate } = useDashboardContext();
    return <StocksView pan={pan} isPrivate={isPrivate} />;
  },
});

// Create the route tree
const routeTree = rootRoute.addChildren([
  indexRoute,
  dashboardRoute.addChildren([
    overviewRoute,
    portfolioRoute,
    performanceRoute,
    rebalanceRoute,
    taxRoute,
    ledgerRoute,
    stocksRoute,
    uploadRoute,
  ]),
]);

export const router = createRouter({ routeTree });

// Context helper is now imported from context/DashboardContext.tsx



declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
