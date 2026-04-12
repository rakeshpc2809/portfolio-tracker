// portfolio-dashboard/src/components/ui/RecommendationDetailCard.tsx

import React from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { TacticalSignal, ReasoningMetadata, UIMetaphor } from '../../types/signals';
import CurrencyValue from './CurrencyValue';
import { Info } from 'lucide-react';
import * as Tooltip from '@radix-ui/react-tooltip';

// ── Visual Metaphor Components ─────────────────────────────────────────────────

const RubberBandVisual: React.FC<{ zScore: number; rarityPct: number }> = ({ zScore, rarityPct }) => {
  const stretch = Math.min(Math.abs(zScore) / 4, 1); 
  const stretchPx = Math.round(stretch * 80);

  const label = zScore <= -2.0
    ? `Highly Overstretched — Snapback Expected`
    : zScore <= -1.0
    ? `Moderately Stretched — Dip Opportunity`
    : `Mildly Below Average`;

  return (
    <div className="flex flex-col items-center gap-3 py-4">
      <svg width="200" height="60" viewBox="0 0 200 60" className="overflow-visible">
        <circle cx="10" cy="30" r="5" fill="#818cf8" />
        <motion.path
          d={`M 10 30 Q 100 ${30 + stretchPx} 190 30`}
          fill="none"
          stroke="#34d399"
          strokeWidth="2.5"
          className="drop-shadow-[0_0_6px_rgba(52,211,153,0.5)]"
          animate={{ d: [`M 10 30 Q 100 ${30 + stretchPx} 190 30`, `M 10 30 Q 100 ${30 + stretchPx - 10} 190 30`, `M 10 30 Q 100 ${30 + stretchPx} 190 30`] }}
          transition={{ duration: 2, repeat: Infinity, ease: "easeInOut" }}
        />
        <line x1="10" y1="30" x2="190" y2="30" stroke="rgba(255,255,255,0.1)" strokeWidth="1" strokeDasharray="4 4"/>
        <circle cx="190" cy="30" r="5" fill="#818cf8" />
        <text x="100" y={30 + stretchPx + 18} textAnchor="middle" fill="#34d399" fontSize="13" fontWeight="bold">
          {zScore.toFixed(1)}σ
        </text>
      </svg>

      <div className="flex items-center gap-2">
        <span className="px-2 py-0.5 bg-buy/15 border border-buy/30 rounded-full text-[10px] font-bold text-buy uppercase tracking-widest">
          🎯 {label}
        </span>
      </div>
      <p className="text-[11px] text-secondary text-center max-w-[240px]">
        Only <span className="text-buy font-bold">{rarityPct.toFixed(1)}%</span> of historical 
        days were this cheap — statistically rare buying opportunity.
      </p>
    </div>
  );
};

const VolatilityHarvestVisual: React.FC<{
  harvestAmount: number;
  volatilityTax: number;
  isPrivate: boolean;
}> = ({ harvestAmount, volatilityTax, isPrivate }) => (
  <div className="flex flex-col items-center gap-3 py-4">
    <motion.div 
      className="text-5xl"
      animate={{ y: [0, -10, 0], rotate: [0, 5, -5, 0] }}
      transition={{ duration: 3, repeat: Infinity, ease: "easeInOut" }}
    >
      🌾
    </motion.div>
    <div className="text-center">
      <p className="text-[10px] text-muted uppercase tracking-widest mb-1">Rebalancing Bonus Captured</p>
      <div className="text-2xl font-medium text-buy tabular-nums">
        <CurrencyValue isPrivate={isPrivate} value={harvestAmount} />
      </div>
    </div>
    <p className="text-[11px] text-secondary text-center max-w-[260px]">
      We are capturing <span className="text-buy font-bold">
        <CurrencyValue isPrivate={isPrivate} value={harvestAmount} />
      </span> in 'extra' growth created by market chaos. This drag ({(volatilityTax * 100).toFixed(1)}% p.a.)
      would quietly erode your returns otherwise.
    </p>
  </div>
);

const ThermometerVisual: React.FC<{ zScore: number; rarityPct: number }> = ({ zScore, rarityPct }) => {
  const fillPct = Math.min((zScore / 4) * 100, 100); 
  return (
    <div className="flex flex-col items-center gap-3 py-4">
      <div className="relative w-8 h-24 bg-white/5 rounded-full border border-white/10 overflow-hidden">
        <motion.div
          className="absolute bottom-0 left-0 right-0 bg-exit rounded-full shadow-[0_0_12px_rgba(248,113,113,0.6)]"
          initial={{ height: 0 }}
          animate={{ height: `${fillPct}%` }}
          transition={{ duration: 1.2, ease: 'easeOut' }}
        />
        <div className="absolute -bottom-1.5 left-1/2 -translate-x-1/2 w-5 h-5 bg-exit rounded-full shadow-[0_0_8px_rgba(248,113,113,0.6)]" />
      </div>
      <div className="flex items-center gap-2">
        <span className="px-2 py-0.5 bg-exit/15 border border-exit/30 rounded-full text-[10px] font-bold text-exit uppercase tracking-widest">
          🌡 Overheated — +{zScore.toFixed(1)}σ
        </span>
      </div>
      <p className="text-[11px] text-secondary text-center max-w-[240px]">
        This fund is in the top <span className="text-exit font-bold">{(100 - rarityPct).toFixed(1)}%</span> of
        expensive days. History shows prices cool down from here.
      </p>
    </div>
  );
};

const WaveRiderVisual: React.FC<{ hurstExponent: number }> = ({ hurstExponent }) => (
  <div className="flex flex-col items-center gap-3 py-4">
    <div className="relative w-40 h-16 overflow-hidden">
      <svg viewBox="0 0 160 60" className="w-full h-full">
        <motion.path
          d="M0 40 Q20 20 40 35 Q60 50 80 30 Q100 10 120 25 Q140 40 160 20"
          fill="none"
          stroke="#818cf8"
          strokeWidth="2.5"
          className="drop-shadow-[0_0_6px_rgba(129,140,248,0.5)]"
          animate={{ pathLength: [0, 1] }}
          transition={{ duration: 1.5, ease: 'easeInOut' }}
        />
        <motion.circle
          cx="120" cy="25" r="5"
          fill="#fbbf24"
          className="drop-shadow-[0_0_4px_rgba(251,191,36,0.6)]"
          animate={{ cx: [0, 160], cy: [40, 20, 35, 30, 25] }}
          transition={{ duration: 4, ease: 'easeInOut', repeat: Infinity }}
        />
      </svg>
    </div>
    <div className="flex items-center gap-2">
      <span className="px-2 py-0.5 bg-accent/15 border border-accent/30 rounded-full text-[10px] font-bold text-accent uppercase tracking-widest">
        🏄 Riding the Wave — H={hurstExponent.toFixed(2)}
      </span>
    </div>
    <p className="text-[11px] text-secondary text-center max-w-[240px]">
      This fund is in a confirmed uptrend (Hurst={hurstExponent.toFixed(2)}). 
      Cutting it now would mean selling a winner too early. We'll let it run.
    </p>
  </div>
);

const CoolingOffVisual: React.FC<{ zScore: number; label: string }> = ({ zScore, label }) => (
  <div className="flex flex-col items-center gap-3 py-4">
    <div className="text-4xl">❄️</div>
    <p className="text-[11px] text-secondary text-center max-w-[240px]">
      {label === 'CRITICAL_REVIEW' ? 'CRITICAL REVIEW REQUIRED' : 'Within Normal Range'}
      <br/>
      Z-Score {zScore.toFixed(2)}σ — no statistically significant mean-reversion signal yet.
    </p>
  </div>
);

// ── Metaphor dispatcher ────────────────────────────────────────────────────────

const MetaphorVisual: React.FC<{
  metaphor: UIMetaphor;
  meta: ReasoningMetadata;
  isPrivate: boolean;
}> = ({ metaphor, meta, isPrivate }) => {
  switch (metaphor) {
    case 'RUBBER_BAND':
      return <RubberBandVisual zScore={meta.zScore} rarityPct={meta.historicalRarityPct} />;
    case 'VOLATILITY_HARVEST':
      return <VolatilityHarvestVisual harvestAmount={meta.harvestAmountRupees} volatilityTax={meta.volatilityTax} isPrivate={isPrivate} />;
    case 'THERMOMETER':
      return <ThermometerVisual zScore={meta.zScore} rarityPct={meta.historicalRarityPct} />;
    case 'WAVE_RIDER':
      return <WaveRiderVisual hurstExponent={meta.hurstExponent} />;
    case 'COOLING_OFF':
    default:
      return <CoolingOffVisual zScore={meta.zScore} label={meta.zScoreLabel} />;
  }
};

const SignalNoiseTable: React.FC<{ meta: ReasoningMetadata }> = ({ meta }) => {
  const rows = [
    {
      technical: `Z-Score: ${meta.zScore.toFixed(2)}σ`,
      noob: meta.zScoreLabel === 'STATISTICALLY_CHEAP' ? '🟢 Statistically Cheap'
          : meta.zScoreLabel === 'OVERHEATED'           ? '🔴 Overheated'
          : meta.zScoreLabel === 'CRITICAL_REVIEW'      ? '🚨 Critical Review'
          : meta.zScoreLabel === 'SLIGHTLY_CHEAP'       ? '🟡 Mild Discount'
          : meta.zScoreLabel === 'SLIGHTLY_RICH'        ? '🟠 Slightly Rich'
          : '⚪ Normal Range',
      value: `${meta.historicalRarityPct.toFixed(1)}% of days`,
      color: meta.zScoreLabel === 'STATISTICALLY_CHEAP' ? 'text-buy'
           : meta.zScoreLabel === 'OVERHEATED' || meta.zScoreLabel === 'CRITICAL_REVIEW' ? 'text-exit'
           : 'text-warning',
    },
    {
      technical: `Hurst Exponent: ${meta.hurstExponent.toFixed(2)}`,
      noob: meta.hurstRegime === 'MEAN_REVERTING' ? '↩️ Bouncing Back'
          : meta.hurstRegime === 'TRENDING'         ? '🏄 Riding the Wave'
          : '〰️ Unpredictable',
      value: meta.hurstRegime,
      color: meta.hurstRegime === 'MEAN_REVERTING' ? 'text-buy'
           : meta.hurstRegime === 'TRENDING'         ? 'text-accent'
           : 'text-muted',
    },
    {
      technical: `Volatility Tax: ${(meta.volatilityTax * 100).toFixed(2)}% p.a.`,
      noob: '🌾 Bonus Potential',
      value: 'Free money from rebalancing',
      color: 'text-buy',
    },
  ];

  return (
    <div className="mt-4 rounded-lg overflow-hidden border border-white/5">
      <table className="w-full text-left">
        <thead>
          <tr className="bg-white/[0.02]">
            <th className="px-4 py-2 text-[9px] font-bold uppercase tracking-widest text-muted">Technical Metric</th>
            <th className="px-4 py-2 text-[9px] font-bold uppercase tracking-widest text-muted">"Noob" Translation</th>
            <th className="px-4 py-2 text-[9px] font-bold uppercase tracking-widest text-muted">Context</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-white/5">
          {rows.map((row, i) => (
            <tr key={i} className="hover:bg-white/[0.01] transition-colors">
              <td className="px-4 py-3 text-[11px] text-secondary font-mono">{row.technical}</td>
              <td className={`px-4 py-3 text-[11px] font-medium ${row.color}`}>{row.noob}</td>
              <td className="px-4 py-3 text-[10px] text-muted">{row.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

const FeatureAttributionChart: React.FC<{ attr: any }> = ({ attr }) => {
  if (!attr) return null;
  const items = [
    { label: "Price deviation", value: attr.zScoreContrib },
    { label: "Trend regime", value: attr.hurstContrib },
    { label: "Market state", value: attr.hmmContrib },
    { label: "Recovery speed", value: attr.ouContrib },
    { label: "Tax window", value: attr.taxContrib },
  ];

  return (
    <div className="space-y-2 mt-4">
      <Tooltip.Provider delayDuration={200}>
        <div className="flex items-center gap-1.5 mb-2">
          <p className="text-[10px] text-muted uppercase tracking-widest font-bold">Why this signal?</p>
          <Tooltip.Root>
            <Tooltip.Trigger asChild>
              <Info size={10} className="text-muted cursor-help" />
            </Tooltip.Trigger>
            <Tooltip.Portal>
              <Tooltip.Content className="bg-surface border border-border rounded-xl p-3 shadow-xl max-w-xs text-[12px] text-secondary z-50 animate-in fade-in zoom-in duration-200" sideOffset={5}>
                <p className="text-[11px]">This shows what's driving the signal. Longer bar = bigger influence on the decision.</p>
                <Tooltip.Arrow className="fill-border" />
              </Tooltip.Content>
            </Tooltip.Portal>
          </Tooltip.Root>
        </div>
      </Tooltip.Provider>
      <div className="space-y-1.5">
        {items.map((item, i) => (
          <div key={i} className="flex items-center gap-3">
            <span className="text-[10px] text-secondary w-24 truncate">{item.label}</span>
            <div className="flex-1 h-1.5 bg-white/5 rounded-full overflow-hidden">
              <motion.div
                className="h-full bg-buy/40"
                initial={{ width: 0 }}
                animate={{ width: `${item.value * 100}%` }}
                transition={{ duration: 1, delay: i * 0.1 }}
              />
            </div>
            <span className="text-[9px] text-muted w-8 text-right">{(item.value * 100).toFixed(0)}%</span>
          </div>
        ))}
      </div>
    </div>
  );
};

interface Props {
  signal: TacticalSignal;
  isPrivate: boolean;
  defaultExpanded?: boolean;
}

const actionColors: Record<string, string> = {
  BUY:   'text-buy  border-buy/20  bg-buy/5',
  SELL:  'text-exit border-exit/20 bg-exit/5',
  HOLD:  'text-muted border-white/10 bg-white/[0.02]',
  WATCH: 'text-warning border-warning/20 bg-warning/5',
  EXIT:  'text-exit border-exit/20 bg-exit/5',
};

export const RecommendationDetailCard: React.FC<Props> = ({
  signal, isPrivate, defaultExpanded = false
}) => {
  const [expanded, setExpanded] = React.useState(defaultExpanded);
  const meta = signal.reasoningMetadata;
  const colors = actionColors[signal.action] ?? actionColors.HOLD;

  if (!meta) {
    return (
      <div className={`rounded-xl border p-5 ${colors}`}>
        <div className="flex items-center justify-between mb-3">
          <span className="text-[10px] font-bold uppercase tracking-widest">{signal.action}</span>
          <span className="text-[13px] font-medium text-primary truncate max-w-[200px]">{signal.schemeName}</span>
        </div>
        <div className="space-y-1">
          {signal.justifications.slice(0, 2).map((j, i) => (
            <p key={i} className="text-[10px] text-secondary border-l border-white/10 pl-2">{j}</p>
          ))}
        </div>
      </div>
    );
  }

  return (
    <motion.div
      layout
      className={`rounded-xl border p-5 cursor-pointer select-none ${colors} transition-all duration-200`}
      onClick={() => setExpanded(e => !e)}
    >
      <div className="flex items-center justify-between mb-3">
        <span className="text-[10px] font-bold uppercase tracking-widest opacity-70">{signal.action} SIGNAL</span>
        <div className="flex items-center gap-2">
          {meta.zScoreLabel === 'STATISTICALLY_CHEAP' && (
            <span className="px-2 py-0.5 bg-buy/15 border border-buy/30 rounded-full text-[9px] font-bold text-buy uppercase tracking-widest">
              🏷️ DISCOUNT — {meta.historicalRarityPct.toFixed(1)}% rarity
            </span>
          )}
          {meta.zScoreLabel === 'OVERHEATED' && (
            <span className="px-2 py-0.5 bg-exit/15 border border-exit/30 rounded-full text-[9px] font-bold text-exit uppercase tracking-widest">
              🌡️ OVERHEATED
            </span>
          )}
          {meta.zScoreLabel === 'CRITICAL_REVIEW' && (
            <span className="px-2 py-0.5 bg-exit/30 border border-exit/50 rounded-full text-[9px] font-bold text-white uppercase tracking-widest animate-pulse">
              🚨 CRITICAL REVIEW
            </span>
          )}
          {meta.hurstRegime === 'MEAN_REVERTING' && (
            <span className="px-2 py-0.5 bg-buy/10 border border-buy/20 rounded-full text-[9px] font-bold text-buy uppercase tracking-widest">
              ↩️ BOUNCING BACK
            </span>
          )}
          {meta.hurstRegime === 'TRENDING' && (
            <span className="px-2 py-0.5 bg-accent/10 border border-accent/20 rounded-full text-[9px] font-bold text-accent uppercase tracking-widest">
              🏄 TRENDING
            </span>
          )}
        </div>
      </div>

      <p className="text-[14px] font-black text-primary truncate mb-1 tracking-tight">{signal.simpleName || signal.schemeName}</p>

      <div className="text-xl font-black tabular-nums mb-3 tracking-tighter">
        {['BUY', 'SELL', 'EXIT'].includes(signal.action) ? (
          <CurrencyValue isPrivate={isPrivate} value={parseFloat(signal.amount)} />
        ) : (
          <span className="text-muted text-sm">{signal.action}</span>
        )}
      </div>

      <p className="text-[12px] text-secondary leading-relaxed mb-3 font-medium">
        {meta.noobHeadline}
      </p>

      {meta.ouHalfLifeDays > 0 && (
        <p className="text-[11px] text-muted italic mb-3">{meta.ouInterpretation}</p>
      )}

      <button
        className="text-[9px] text-muted uppercase tracking-widest font-bold flex items-center gap-1 hover:text-secondary transition-colors"
        onClick={e => { e.stopPropagation(); setExpanded(x => !x); }}
      >
        {expanded ? '▲ Less detail' : '▼ Show me why'}
      </button>

      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            className="mt-4 space-y-4 overflow-hidden"
            onClick={e => e.stopPropagation()}
          >
            <div className="bg-white/[0.02] rounded-xl border border-white/5 px-4">
              <MetaphorVisual metaphor={meta.uiMetaphor} meta={meta} isPrivate={isPrivate} />
            </div>

            <FeatureAttributionChart attr={meta.featureAttribution} />

            <SignalNoiseTable meta={meta} />

            <div className="space-y-2">
              <p className="text-[9px] text-muted uppercase tracking-widest font-bold">Technical Reasoning</p>
              {signal.justifications.map((j, i) => (
                <p key={i} className="text-[10px] text-secondary leading-relaxed border-l border-white/10 pl-3">
                  {j}
                </p>
              ))}
            </div>

            {meta.harvestAmountRupees > 0 && meta.harvestExplanation && (
              <div className="bg-buy/[0.04] border border-buy/15 rounded-lg p-4">
                <p className="text-[10px] text-muted uppercase tracking-widest font-bold mb-1">🌾 Volatility Harvest</p>
                <p className="text-[11px] text-secondary leading-relaxed">{meta.harvestExplanation}</p>
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
};

export default RecommendationDetailCard;
