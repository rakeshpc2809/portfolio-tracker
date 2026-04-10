import type { ReasoningMetadata, TacticalSignal, UIMetaphor, ZScoreLabel } from '../types/signals';

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

/**
 * Client-side fallback: derives ReasoningMetadata from existing TacticalSignal fields.
 * Used when backend has not yet been deployed with the new RebalanceEngine.
 */
export const buildReasoningMetadata = (signal: TacticalSignal): ReasoningMetadata => {
  const z = signal.returnZScore ?? 0;
  const H = 0.5; // Unknown until new engine runs — assume random walk

  const zScoreLabel: ZScoreLabel =
    z <= -4.0 ? 'CRITICAL_REVIEW'     :
    z <= -2.0 ? 'STATISTICALLY_CHEAP' :
    z <= -1.0 ? 'SLIGHTLY_CHEAP'      :
    z >=  2.0 ? 'OVERHEATED'          :
    z >=  1.0 ? 'SLIGHTLY_RICH'       : 'NEUTRAL';

  const uiMetaphor: UIMetaphor =
    signal.action === 'BUY'  && z <= -1.5 ? 'RUBBER_BAND'        :
    signal.action === 'SELL' && z >=  2.0 ? 'THERMOMETER'        :
    signal.action === 'SELL'              ? 'VOLATILITY_HARVEST' :
    signal.action === 'HOLD'              ? 'WAVE_RIDER'          : 'COOLING_OFF';

  const noobHeadline =
    zScoreLabel === 'CRITICAL_REVIEW'
      ? `This fund is crashing harder than usual (Z=${z.toFixed(1)}σ). Manual review recommended.`
    : signal.action === 'BUY'  && z <= -2.0
      ? `This fund is on a statistically rare discount (Z=${z.toFixed(1)}σ). A snapback is expected.`
    : signal.action === 'BUY'
      ? `This fund is underweight and shows a mild price dip (Z=${z.toFixed(1)}σ).`
    : signal.action === 'SELL' && z >= 2.0
      ? `This fund has grown hotter than usual (Z=+${z.toFixed(1)}σ). Time to trim before it cools.`
    : signal.action === 'SELL'
      ? `This fund has drifted overweight. Trimming it now locks in 'extra' gains to redeploy elsewhere.`
    : signal.action === 'HOLD' && (signal.actualPercentage > signal.plannedPercentage)
      ? `Overweight but holding — the trend looks strong. We won't cut profits yet.`
    : `All good — within target range. No action needed.`;

  return {
    primaryNarrative:    signal.justifications[0] ?? signal.schemeName,
    technicalLabel:      `Z-Score: ${z.toFixed(2)}σ`,
    noobHeadline,
    uiMetaphor,
    zScore:              z,
    hurstExponent:       H,
    volatilityTax:       0,
    hurstRegime:         'RANDOM_WALK',
    zScoreLabel,
    historicalRarityPct: zScoreLabel === 'STATISTICALLY_CHEAP' ? 2.3
                       : zScoreLabel === 'OVERHEATED'           ? 2.3
                       : 50,
    harvestAmountRupees: 0,
    harvestExplanation:  '',
  };
};

/**
 * Resolves metadata: uses backend-provided metadata if present, otherwise
 * synthesises it client-side from existing signal fields.
 */
export const resolveReasoningMetadata = (signal: TacticalSignal): ReasoningMetadata =>
  signal.reasoningMetadata ?? buildReasoningMetadata(signal);
