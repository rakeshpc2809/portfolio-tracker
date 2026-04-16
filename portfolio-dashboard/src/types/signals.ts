// portfolio-dashboard/src/types/signals.ts

export type HurstRegime = 'MEAN_REVERTING' | 'TRENDING' | 'RANDOM_WALK';
export type ZScoreLabel = 'STATISTICALLY_CHEAP' | 'SLIGHTLY_CHEAP' | 'NEUTRAL' | 'SLIGHTLY_RICH' | 'OVERHEATED' | 'CRITICAL_REVIEW';
export type UIMetaphor  = 'RUBBER_BAND' | 'VOLATILITY_HARVEST' | 'WAVE_RIDER' | 'THERMOMETER' | 'COOLING_OFF';

export interface FeatureAttribution {
  zScoreContrib: number;
  hurstContrib: number;
  hmmContrib:   number;
  ouContrib:    number;
  taxContrib:   number;
}

export interface ReasoningMetadata {
  primaryNarrative:     string;
  technicalLabel:       string;
  noobHeadline:         string;
  uiMetaphor:           UIMetaphor;
  zScore:               number;
  hurstExponent:        number;
  volatilityTax:        number;
  hurstRegime:          HurstRegime;
  zScoreLabel:          ZScoreLabel;
  historicalRarityPct:  number;
  harvestAmountRupees:  number;
  harvestExplanation:   string;
  ouHalfLifeDays:       number;
  ouInterpretation:     string;
  featureAttribution:   FeatureAttribution | null;
}

export interface TacticalSignal {
  schemeName:          string;
  simpleName?:         string;
  amfiCode:            string;
  action:              'BUY' | 'SELL' | 'HOLD' | 'WATCH' | 'EXIT';
  amount:              string;
  plannedPercentage:   number;
  actualPercentage:    number;
  sipPercentage:       number;
  fundStatus:          string;
  convictionScore:     number;
  sortinoRatio:        number;
  maxDrawdown:         number;
  navPercentile3yr:    number;
  drawdownFromAth:     number;
  returnZScore:        number;
  lastBuyDate:         string | null;
  justifications:      string[];
  reasoningMetadata:   ReasoningMetadata | null; // null = legacy signal, render fallback
  
  // Advanced Quantitative Metrics (from SchemePerformanceDTO)
  rollingZScore252:    number;
  historicalRarityPct: number;
  hurstExponent:       number;
  volatilityTax:       number;
  hurstRegime:         string;
  hmmState:            string;
  hmmBullProb:         number;
  hmmBearProb:         number;

  hurst20d:            number;
  hurst60d:            number;
  multiScaleRegime:    string;
  ouHalfLife:          number;
  ouValid:             boolean;
  ouBuyThreshold:      number;
  ouSellThreshold:     number;
  hrpOverrideActive:   boolean;
}

export interface RebalancingTrade {
  sellFundName: string;
  sellAmfiCode: string;
  sellAmount: number;
  estimatedSellTax: number;
  netProceeds: number;
  sellReason: string;
  buyFundName: string;
  buyAmfiCode: string;
  buyAmount: number;
  buyReason: string;
  convictionDelta: number;
  zScoreDelta: number;
  tradeRationale: string;
}

export interface DroppedFundSummary {
  schemeName: string;
  amfiCode: string;
  currentValue: number;
  ltcgGains: number;
  stcgGains: number;
  daysToNextLtcg: number;
  taxIfExitNow: number;
  taxIfWaitForLtcg: number;
  taxSavingByWaiting: number;
  recommendedAction: string;
  exitDateSuggestion: string;
  reason: string;
}

export interface ExitScheduleItem {
  schemeName: string;
  currentValue: number;
  ltcgGains: number;
  stcgGains: number;
  daysToLtcgConversion: number;
  suggestedFY: string;
  taxIfThisFY: number;
  taxIfNextFY: number;
  saving: number;
  reason: string;
}
