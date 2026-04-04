export const CATEGORY_COLORS: any = {
  "Equity (Active)": "#10b981",
  "Index Funds": "#3b82f6",
  "Debt": "#8b5cf6",
  "Hybrid/Arbitrage": "#f59e0b",
  "Gold/Commodities": "#eab308",
  "Other": "#64748b"
};

export const getScoreColor = (score: number) => {
  if (score >= 55) return "#10b981";
  if (score >= 45) return "#3b82f6";
  return "#f43f5e";
};

export const formatCurrency = (val: number) => 
  new Intl.NumberFormat('en-IN', { 
    style: 'currency', 
    currency: 'INR', 
    maximumFractionDigits: 0 
  }).format(val || 0);
  
export const normalizeCategory = (rawCat: string) => {
  const cat = rawCat?.toUpperCase() || "";
  if (cat.includes("INDEX")) return "Index Funds";
  if (cat.includes("EQUITY")) return "Equity (Active)";
  if (cat.includes("DEBT")) return "Debt";
  if (cat.includes("HYBRID") || cat.includes("ARBITRAGE")) return "Hybrid/Arbitrage";
  if (cat.includes("FOF") || cat.includes("GOLD")) return "Gold/Commodities";
  return "Other";
};