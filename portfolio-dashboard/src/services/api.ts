import { normalizeCategory } from '../utils/formatters';

const BASE_URL = '/api';
const PARSER_URL = '/parser';
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
        cleanCategory: normalizeCategory(item.category, item.schemeName),
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

export const uploadCas = async (file: File, password: string) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('password', password);

  const response = await fetch(`${PARSER_URL}/parse`, {
    method: 'POST',
    body: formData,
    // Note: Don't set Content-Type header for FormData, browser will set it with boundary
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ detail: 'Unknown error occurred during parsing' }));
    throw new Error(errorData.detail || 'CAS Parsing failed');
  }

  return response.json();
};

export const triggerBackfill = async () => {
  const response = await authenticatedFetch(`${BASE_URL}/admin/trigger-historical-backfill`, {
    method: 'POST'
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || "Failed to trigger backfill");
  }
  return response.text();
};

export const triggerSnapshotBackfill = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/admin/trigger-snapshot-backfill?pan=${pan}`, {
    method: 'POST'
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || "Failed to trigger snapshot backfill");
  }
  return response.text();
};

export const triggerForceSync = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/admin/force-sync?pan=${pan}`, {
    method: 'POST'
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || "Failed to trigger sync");
  }
  return response.text();
};

export const fetchAdminStatus = async () => {
  const response = await authenticatedFetch(`${BASE_URL}/admin/status`);
  if (!response.ok) throw new Error("Failed to fetch admin status");
  return response.json();
};

export const fetchPerformanceData = async (pan: string) => {
  const res = await authenticatedFetch(`${BASE_URL}/dashboard/performance/${pan}`);
  if (!res.ok) return null;
  return res.json();
};

export const checkInvestorExistence = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/investor/check/${pan}`);
  if (response.status === 404) return null;
  if (!response.ok) throw new Error("Verification failed");
  return response.json();
};

export const fetchCorrelationMatrix = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/dashboard/correlation/${pan}`);
  if (!response.ok) throw new Error("Failed to fetch correlation matrix");
  return response.json();
};

export const fetchFundHistory = async (amfiCode: string, benchmark: string = "NIFTY 50") => {
  const response = await authenticatedFetch(
    `${BASE_URL}/history/fund/${amfiCode}?benchmark=${encodeURIComponent(benchmark)}`
  );
  if (!response.ok) throw new Error("Failed to fetch fund history");
  return response.json();
};
