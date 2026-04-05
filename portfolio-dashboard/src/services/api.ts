import { normalizeCategory } from '../utils/formatters';

const BASE_URL = '/api';
const API_KEY = 'dev-secret-key'; 

const authenticatedFetch = (url: string, options: RequestInit = {}) => {
  return fetch(url, {
    ...options,
    headers: {
      ...options.headers,
      'X-API-KEY': API_KEY,
    },
  });
};

export const fetchMasterPortfolio = async (investorPan: string, sip: number = 75000, lumpsum: number = 0) => {
  try {
    const response = await authenticatedFetch(`${BASE_URL}/dashboard/full/${investorPan}?sip=${sip}&lumpsum=${lumpsum}`);
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

export const fetchTlhOpportunities = async (pan: string) => {
  const response = await authenticatedFetch(
    `${BASE_URL}/portfolio/${pan}/tax-loss-harvesting`
  );
  if (!response.ok) return [];
  return response.json();
};
