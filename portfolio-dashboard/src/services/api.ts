import { normalizeCategory } from '../utils/formatters';

const BASE_URL = '/api';
const API_KEY = 'dev-secret-key'; // In production, this should be an environment variable

const authenticatedFetch = (url: string, options: RequestInit = {}) => {
  return fetch(url, {
    ...options,
    headers: {
      ...options.headers,
      'X-API-KEY': API_KEY,
    },
  });
};

export const fetchMasterPortfolio = async (investorPan: string) => {
  try {
    const response = await authenticatedFetch(`${BASE_URL}/dashboard/full/${investorPan}`);
    if (!response.ok) throw new Error("Portfolio synchronization failed");
    
    const dashboard = await response.json();

    const mergedData = (dashboard.schemeBreakdown || []).map((item: any) => {
      return {
        ...item,
        cleanCategory: normalizeCategory(item.category),
        shortName: item.schemeName ? item.schemeName.substring(0, 25) + "..." : "Unknown Fund",
        deviation: (parseFloat(item.allocationPercentage || 0) - parseFloat(item.plannedPercentage || 0)).toFixed(2)
      };
    }).sort((a: any, b: any) => b.convictionScore - a.convictionScore);

    return { ...dashboard, schemeBreakdown: mergedData };
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
  const response = await authenticatedFetch(
    `${BASE_URL}/transactions?pan=${pan}${typeParam}&page=${page}&size=${size}&sort=date,desc`
  );
  if (!response.ok) throw new Error("Ledger synchronization failed");
  return response.json();
};