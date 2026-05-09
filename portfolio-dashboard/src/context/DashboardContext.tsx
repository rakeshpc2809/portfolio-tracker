import React, { createContext, useContext } from 'react';

interface DashboardContextType {
  portfolioData: any;
  sipAmount: number;
  setSipAmount: (val: number) => void;
  lumpsum: number;
  setLumpsum: (val: number) => void;
  isPrivate: boolean;
  setSelectedFundName: (name: string | null) => void;
  pan: string;
}

const DashboardContext = createContext<DashboardContextType | undefined>(undefined);

export function DashboardProvider({ children, value }: { children: React.ReactNode, value: DashboardContextType }) {
  return (
    <DashboardContext.Provider value={value}>
      {children}
    </DashboardContext.Provider>
  );
}

export function useDashboardContext() {
  const context = useContext(DashboardContext);
  if (context === undefined) {
    throw new Error('useDashboardContext must be used within a DashboardProvider');
  }
  return context;
}
