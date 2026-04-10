// portfolio-dashboard/src/types/signals.ts

export type HurstRegime = 'MEAN_REVERTING' | 'TRENDING' | 'RANDOM_WALK';
export type ZScoreLabel = 'STATISTICALLY_CHEAP' | 'SLIGHTLY_CHEAP' | 'NEUTRAL' | 'SLIGHTLY_RICH' | 'OVERHEATED' | 'CRITICAL_REVIEW';
export type UIMetaphor  = 'RUBBER_BAND' | 'VOLATILITY_HARVEST' | 'WAVE_RIDER' | 'THERMOMETER' | 'COOLING_OFF';

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
}

export interface TacticalSignal {
  schemeName:          string;
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
}
