import { normalizeCategory } from '../utils/formatters';
import { DashboardSummarySchema } from '../types/portfolio';

const BASE_URL = '/api';
const PARSER_URL = '/parser';
const API_KEY = (import.meta.env.VITE_API_KEY as string) || 'dev-secret-key';

export const authenticatedFetch = async (url: string, options: RequestInit = {}) => {
  const token = localStorage.getItem('portfolio_token');
  const headers = new Headers(options.headers);
  headers.set('X-API-KEY', API_KEY);
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const response = await fetch(url, {
    ...options,
    headers,
  });
  if (response.status === 401 || response.status === 403) {
    localStorage.removeItem('portfolio_token');
    localStorage.removeItem('portfolio_pan');
    window.location.href = '/';
  }
  return response;
};

export const fetchMasterPortfolio = async (investorPan: string, sip: number = 75000, lumpsum: number = 0) => {
  try {
    const response = await authenticatedFetch(`${BASE_URL}/dashboard/full/${investorPan}?sip=${sip}&lumpsum=${lumpsum}`);
    if (!response.ok) throw new Error("Portfolio synchronization failed");
    
    const rawData = await response.json();
    const dashboard = DashboardSummarySchema.parse(rawData);

    const mergedData = (dashboard.schemeBreakdown || []).map((item) => {
      return {
        ...item,
        cleanCategory: normalizeCategory(item.category, item.schemeName),
        shortName: item.schemeName ? item.schemeName.substring(0, 25) + "..." : "Unknown Fund",
        deviation: (parseFloat(String(item.allocationPercentage || 0)) - parseFloat(String(item.plannedPercentage || 0))).toFixed(2)
      };
    }).sort((a, b) => b.convictionScore - a.convictionScore);

    return { ...dashboard, schemeBreakdown: mergedData };
  } catch (error) {
    console.error("API Validation/Fetch Error:", error);
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

export const fetchRebalanceActions = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/portfolio/${pan}/rebalance-actions`);
  if (response.status === 204) return [];
  if (!response.ok) throw new Error("Failed to fetch rebalance actions");
  return response.json();
};

export const fetchSmartSip = async (pan: string, budget: number) => {
  const response = await authenticatedFetch(
    `${BASE_URL}/portfolio/${pan}/smart-sip?budget=${budget}`
  );
  if (!response.ok) throw new Error("Failed to calculate Smart SIP split");
  return response.json();
};

export const fetchPortfolioState = async () => {
  const response = await authenticatedFetch(`${BASE_URL}/v1/portfolio/state`);
  if (!response.ok) throw new Error("Failed to fetch portfolio state");
  return response.json();
};

export const fetchTaxHeadroom = async () => {
  const response = await authenticatedFetch(`${BASE_URL}/v1/portfolio/tax-headroom`);
  if (!response.ok) throw new Error("Failed to fetch tax headroom");
  return response.json();
};

export const fetchLtcgExitSchedule = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/dashboard/ltcg-exit-schedule/${pan}`);
  if (!response.ok) throw new Error("Failed to fetch LTCG exit schedule");
  return response.json();
};

export const fetchVintageReturns = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/dashboard/vintage-returns/${pan}`);
  if (!response.ok) throw new Error("Failed to fetch vintage returns");
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

export const uploadStockCsv = async (file: File, pan: string, source: string = 'INDMONEY_CSV') => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('pan', pan);
  formData.append('source', source);

  const response = await authenticatedFetch(`${PARSER_URL}/api/upload`, {
    method: 'POST',
    body: formData,
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ error: 'Stock import failed' }));
    throw new Error(errorData.error || 'Stock import failed');
  }

  return response.json();
};

export const fetchStockPortfolio = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/stocks/portfolio?pan=${pan}`);
  if (!response.ok) throw new Error("Failed to fetch stock portfolio");
  return response.json();
};

export const syncStockPrices = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/stocks/sync?pan=${pan}`, {
    method: 'POST'
  });
  if (!response.ok) throw new Error("Failed to sync stock prices");
  return response.json();
};

export const purgeStockData = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/stocks/purge?pan=${pan}`, {
    method: 'DELETE'
  });
  if (!response.ok) throw new Error("Failed to purge stock data");
  return response.json();
};

export const previewCas = async (file: File, password: string) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('password', password);

  const response = await fetch(`${PARSER_URL}/preview`, {
    method: 'POST',
    body: formData,
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ detail: 'Unknown error occurred during preview' }));
    throw new Error(errorData.detail || 'CAS Preview failed');
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

export const fetchStrategyTargets = async (pan: string) => {
  const response = await authenticatedFetch(`${BASE_URL}/strategy/${pan}`);
  if (!response.ok) throw new Error("Failed to fetch strategy targets");
  return response.json();
};

export const fetchAlphaFeed = async () => {
  try {
    const response = await authenticatedFetch(`${BASE_URL}/sentiment/alpha-feed`);
    if (!response.ok) throw new Error("Failed to fetch alpha feed");
    const data = await response.json();
    if (data && data.length > 0) return data;
    
    // Fallback Mock Data if backend returns empty during setup
    return [
      {
        title: "FII inflows surge in Large-cap Mutual Funds",
        sentiment: "positive",
        confidence: 0.92,
        timestamp: new Date().toISOString()
      },
      {
        title: "New SEBI guidelines on mid-cap liquidity stress tests",
        sentiment: "neutral",
        confidence: 0.75,
        timestamp: new Date(Date.now() - 3600000).toISOString()
      }
    ];
  } catch (err) {
    console.error("Alpha feed fetch failed, using fallback", err);
    return [
      {
        title: "System Calibrating: Monitoring Global Macro Signals",
        sentiment: "neutral",
        confidence: 1.0,
        timestamp: new Date().toISOString()
      }
    ];
  }
};

export const updateStrategyTarget = async (data: { pan: string, amfiCode: string, allocation: number, strategyType: string }) => {
  const response = await authenticatedFetch(`${BASE_URL}/strategy/target`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(data)
  });
  if (!response.ok) throw new Error("Failed to update strategy target");
  return response;
};

export const fetchFundHistory = async (amfiCode: string, benchmark: string = "NIFTY 50") => {
  const response = await authenticatedFetch(
    `${BASE_URL}/history/fund/${amfiCode}?benchmark=${encodeURIComponent(benchmark)}`
  );
  if (!response.ok) throw new Error("Failed to fetch fund history");
  return response.json();
};

export const benchmarkService = {
  getBenchmarkReturns: async (index: string) => {
    const response = await authenticatedFetch(
      `${BASE_URL}/history/benchmark/${encodeURIComponent(index)}`
    );
    if (!response.ok) throw new Error("Failed to fetch benchmark returns");
    return response.json();
  },
  getBenchmarkReturnsForAllPeriods: async (index: string) => {
    const response = await authenticatedFetch(
      `${BASE_URL}/history/benchmark/${encodeURIComponent(index)}/returns`
    );
    if (!response.ok) throw new Error("Failed to fetch benchmark returns for all periods");
    return response.json();
  }
};

export const resetBackfillStatus = async () => {
  const response = await authenticatedFetch(`${BASE_URL}/admin/reset-backfill-status`, {
    method: "POST"
  });
  return response.ok;
};

export const fetchAIReasoning = async (data: { 
  fund_name: string, 
  current_weight: number, 
  target_weight: number, 
  conviction_score: number, 
  market_regime: string 
}) => {
  const response = await authenticatedFetch(`${PARSER_URL}/api/v1/ai/reason`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data)
  });
  if (!response.ok) return { reasoning: "Strategic rebalancing based on quantitative signals." };
  return response.json();
};

export const fetchWealthAIChat = async (data: { 
  query: string, 
  portfolio_summary: string, 
  history: any[] 
}) => {
  const response = await authenticatedFetch(`/parser/api/v1/ai/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data)
  });
  if (!response.ok) throw new Error("Chat link broken");
  return response.json();
};
