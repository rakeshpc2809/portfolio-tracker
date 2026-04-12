// portfolio-dashboard/src/components/ui/LearnTooltip.tsx

import React from 'react';
import * as Tooltip from '@radix-ui/react-tooltip';
import { GraduationCap } from 'lucide-react';

const DEFINITIONS: Record<string, { definition: string; whyItMatters: string }> = {
  XIRR: {
    definition: "Your personal annualised return on this fund — like a bank's interest rate, but calculated on every SIP you made at different times.",
    whyItMatters: "A positive XIRR means you're growing wealth. Negative means you've lost money so far."
  },
  CVaR: {
    definition: "Conditional Value at Risk. It answers: 'In the worst 5% of months historically, how much would this portfolio lose?' -1.6% means in a bad month, expect to lose around 1.6% of your portfolio value.",
    whyItMatters: "Lower (less negative) is safer. Think of it as the portfolio's worst-case stress test."
  },
  Sortino: {
    definition: "A score for 'risk-adjusted returns' that only penalises downside volatility — drops in value — not upside swings. Higher is better.",
    whyItMatters: "Two funds with equal returns can have very different Sortino ratios. Higher Sortino = smoother ride down, more predictable losses."
  },
  Hurst: {
    definition: "A number between 0 and 1 measuring whether a fund tends to keep moving in the same direction (trending) or snap back (mean-reverting). 0.5 = coin flip. >0.55 = trending. <0.45 = rubber band.",
    whyItMatters: "This tells the engine whether to 'ride the wave' or 'buy the dip'."
  },
  Drift: {
    definition: "How far your actual holding percentage has moved from your target. +5% drift means you hold 5% more than planned — usually from strong performance.",
    whyItMatters: "Large drift = your portfolio no longer matches your intended plan. Rebalancing brings it back."
  },
  Z_SCORE: {
    definition: "Measures how far today's NAV is from the fund's own 1-year average, relative to its typical daily swings. -2σ means the fund is cheaper than 97.5% of recent history — a statistical buy signal. +2σ means it's more expensive than 97.5% of recent history.",
    whyItMatters: "Extreme Z-scores trigger BUY or SELL signals based on statistical rarity."
  },
};

interface Props {
  term: string;
  children: React.ReactNode;
}

export default function LearnTooltip({ term, children }: Props) {
  const info = DEFINITIONS[term] || { definition: "Definition coming soon...", whyItMatters: "" };

  return (
    <Tooltip.Provider delayDuration={200}>
      <Tooltip.Root>
        <Tooltip.Trigger asChild>
          <span className="inline-flex items-center gap-1 cursor-help border-b border-dotted border-muted-foreground/40 hover:border-muted-foreground transition-colors">
            {children}
            <GraduationCap size={10} className="text-muted-foreground opacity-60" />
          </span>
        </Tooltip.Trigger>
        <Tooltip.Portal>
          <Tooltip.Content
            className="bg-surface border border-border rounded-xl p-3 shadow-xl max-w-xs text-[12px] text-secondary z-50 animate-in fade-in zoom-in duration-200"
            sideOffset={5}
          >
            <div className="space-y-2">
              <p className="font-bold text-primary">{term}</p>
              <p className="leading-relaxed opacity-90">{info.definition}</p>
              {info.whyItMatters && (
                <div className="pt-2 border-t border-white/5">
                  <p className="text-[10px] font-bold text-muted uppercase tracking-wider mb-0.5">Why it matters</p>
                  <p className="italic opacity-80">{info.whyItMatters}</p>
                </div>
              )}
            </div>
            <Tooltip.Arrow className="fill-border" />
          </Tooltip.Content>
        </Tooltip.Portal>
      </Tooltip.Root>
    </Tooltip.Provider>
  );
}
