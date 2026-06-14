import { z } from 'zod';

export const SchemePerformanceSchema = z.object({
  schemeName: z.string(),
  simpleName: z.string().optional(),
  isin: z.string().nullable().optional(),
  amfiCode: z.union([z.string(), z.number()]).transform(v => String(v)),
  action: z.enum(['BUY', 'SELL', 'HOLD', 'WATCH', 'EXIT', 'HARVEST']).default('HOLD'),
  signalType: z.string().nullable().optional(),
  signalAmount: z.number().nullable().optional(),
  amount: z.string().optional(),
  currentValue: z.number(),
  totalInvested: z.number().nullable().optional(),
  currentInvested: z.number().nullable().optional(),
  allocationPercentage: z.number(),
  plannedPercentage: z.number().nullable().optional(),
  // Tax-specific fields
  stcgUnrealizedGain: z.number().nullable().optional(),
  ltcgUnrealizedGain: z.number().nullable().optional(),
  stcgValue: z.number().nullable().optional(),
  ltcgValue: z.number().nullable().optional(),
  slabRateGain: z.number().nullable().optional(),
  slabRateFund: z.boolean().nullable().optional(),
  daysToNextLtcg: z.number().nullable().optional(),
  realizedGain: z.number().nullable().optional(),
  unrealizedGain: z.number().nullable().optional(),
  // Scoring
  convictionScore: z.number().min(0).max(100),
  sortinoRatio: z.number().nullable().optional(),
  maxDrawdown: z.number().nullable().optional(),
  cvar5: z.number().nullable().optional(),
  winRate: z.number().nullable().optional(),
  returnZScore: z.number().nullable().optional(),
  rollingZScore252: z.number().nullable().optional(),
  navPercentile1yr: z.number().nullable().optional(),
  navPercentile3yr: z.number().nullable().optional(),
  drawdownFromAth: z.number().nullable().optional(),
  historicalRarityPct: z.number().nullable().optional(),
  ouHalfLife: z.number().nullable().optional(),
  ouValid: z.boolean().nullable().optional(),
  // Returns
  xirr: z.string().nullable().optional(),
  benchmarkXirr: z.number().nullable().optional(),
  oneMonthReturn: z.number().nullable().optional(),
  // Classification
  category: z.string(),
  bucket: z.string().nullable().optional(),
  amc: z.string().nullable().optional(),
  benchmarkIndex: z.string().nullable().optional(),
  status: z.string().nullable().optional(),
  // Score components
  yieldScore: z.number().nullable().optional(),
  riskScore: z.number().nullable().optional(),
  valueScore: z.number().nullable().optional(),
  painScore: z.number().nullable().optional(),
  regimeScore: z.number().nullable().optional(),
  frictionScore: z.number().nullable().optional(),
  expenseScore: z.number().nullable().optional(),
  expenseRatio: z.number().nullable().optional(),
  aumCr: z.number().nullable().optional(),
  // HMM regime
  hmmState: z.string().nullable().optional(),
  hmmBullProb: z.number().nullable().optional(),
  hmmBearProb: z.number().nullable().optional(),
  // Hurst / OU
  hurstExponent: z.number().nullable().optional(),
  hurstRegime: z.string().nullable().optional(),
  volatilityTax: z.number().nullable().optional(),
  // Last activity
  lastBuyDate: z.string().nullable().optional(),
  justifications: z.array(z.string()).nullable().optional(),
  convictionHistory: z.array(z.number()).nullable().optional(),
  // Factor model
  rsquared: z.number().nullable().optional(),
  rSquared: z.number().nullable().optional(),
  alpha: z.number().nullable().optional(),
  betaMkt: z.number().nullable().optional(),
  betaSmb: z.number().nullable().optional(),
  betaHml: z.number().nullable().optional(),
}).passthrough();

export const DashboardSummarySchema = z.object({
  currentValueAmount: z.number(),
  totalInvestedAmount: z.number(),
  overallReturn: z.string(),
  overallXirr: z.string(),
  totalSTCG: z.number().nullable().optional(),
  totalLTCG: z.number().nullable().optional(),
  fyLtcgAlreadyRealized: z.number().nullable().optional(),
  taxSlab: z.number().nullable().optional(),
  schemeBreakdown: z.array(SchemePerformanceSchema),
  tacticalPayload: z.any().nullable().optional(),
}).passthrough();

export type SchemePerformance = z.infer<typeof SchemePerformanceSchema>;
export type DashboardSummary = z.infer<typeof DashboardSummarySchema>;
