export const formatCurrency = (amount: number | string): string => {
  const value = typeof amount === 'string' ? parseFloat(amount) : amount;
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value || 0);
};

export const formatCurrencyShort = (val: number): string => {
  if (val >= 10000000) return `₹${(val / 10000000).toFixed(1)}Cr`;
  if (val >= 100000) return `₹${(val / 100000).toFixed(1)}L`;
  if (val >= 1000) return `₹${(val / 1000).toFixed(0)}K`;
  return `₹${Math.round(val)}`;
};

export const normalizeCategory = (rawCat: string): string => {
  if (!rawCat) return "Other";
  const c = rawCat.toUpperCase();
  if (c.includes("EQUITY") || c.includes("INDEX") || c.includes("GROWTH")) return "Equity";
  if (c.includes("DEBT") || c.includes("LIQUID") || c.includes("BOND") || c.includes("GILT")) return "Debt";
  if (c.includes("GOLD")) return "Gold";
  if (c.includes("ARBITRAGE")) return "Arbitrage";
  return "Hybrid";
};

export const convictionColor = (score: number): string => {
  if (score >= 65) return '#34d399';
  if (score >= 45) return '#fbbf24';
  return '#f87171';
};

export const buildPlainEnglishReason = (signal: any): string => {
  const pct = signal.navPercentile3yr != null
    ? `At ${Math.round(signal.navPercentile3yr * 100)}% of its 3-year NAV range.`
    : '';
  const deviation = signal.actualPercentage - signal.plannedPercentage;
  const drift = deviation != null
    ? `${Math.abs(deviation).toFixed(1)}% ${deviation < 0 ? 'underweight' : 'overweight'} target.`
    : '';

  if (signal.action === 'BUY') {
    return `${pct} ${drift} Conviction: ${signal.convictionScore}/100.`.trim();
  }
  if (signal.action === 'EXIT') {
    const ltcg = signal.ltcgValue > 0 ? `₹${Math.round(signal.ltcgValue).toLocaleString('en-IN')} in LTCG-eligible units ready.` : '';
    const stcg = signal.stcgValue > 0 ? `₹${Math.round(signal.stcgValue).toLocaleString('en-IN')} in STCG — wait ${signal.daysToNextLtcg} days to avoid tax.` : '';
    return `Removed from strategy. ${ltcg} ${stcg}`.trim();
  }
  return 'Within target range. No action needed.';
};
