import { normalizeCategory } from '../utils/formatters';

const BASE_URL = '/api';

export const fetchMasterPortfolio = async (investorPan: string) => {
  try {
    const [dashRes, sigRes, metRes] = await Promise.all([
      fetch(`${BASE_URL}/dashboard/summary/${investorPan}`),
      fetch(`${BASE_URL}/portfolio/${investorPan}/tactical-signals`),
      fetch(`${BASE_URL}/metrics/latest/${investorPan}`)
    ]);

    

    const dashboard = await dashRes.json();
    const signals = await sigRes.json();
    const metrics = await metRes.json();

    const mergedData = (dashboard.schemeBreakdown || []).map((item: any) => {
      const nameToMatch = item.schemeName?.trim().toLowerCase();
      const m = metrics.find((met: any) => met.schemeName?.trim().toLowerCase() === nameToMatch);
      const s = signals.find((sig: any) => sig.schemeName?.trim().toLowerCase() === nameToMatch);
      
      const actualPct = parseFloat(s?.actualPercentage) || parseFloat(item?.allocationPercentage) || 0;
      const plannedPct = parseFloat(s?.plannedPercentage) || actualPct;
      
      return {
        ...item,
        ...s, 
        convictionScore: m?.convictionScore ?? 0,
        sortinoRatio: m?.sortinoRatio ?? 0,
        maxDrawdown: m?.maxDrawdown ? Math.abs(m.maxDrawdown) : 0, 
        cleanCategory: normalizeCategory(item.category),
        shortName: item.schemeName ? item.schemeName.substring(0, 25) + "..." : "Unknown Fund",
        actualPercentage: actualPct,
        plannedPercentage: plannedPct,
        deviation: (actualPct - plannedPct).toFixed(2)
      };
    }).sort((a: any, b: any) => b.convictionScore - a.convictionScore);

    return { ...dashboard, schemeBreakdown: mergedData, rawSignals: signals };
  } catch (error) {
    console.error("API Error:", error);
    throw error;
  }
};

export const fetchTransactions = async (
  pan: string, 
  page: number = 0, 
  type: string = "ALL", 
  size: number = 20
) => {
  const typeParam = type !== "ALL" ? `&type=${type}` : "";
  const response = await fetch(
    `${BASE_URL}/transactions?pan=${pan}${typeParam}&page=${page}&size=${size}&sort=date,desc`
  );
  if (!response.ok) throw new Error("Ledger synchronization failed");
  return response.json();
};