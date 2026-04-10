package com.oreki.cas_injector.rebalancing.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.ReasoningMetadata;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;

@Service
public class RebalanceEngine {

    // ── Thresholds ────────────────────────────────────────────────────────────
    private static final double DRIFT_TOLERANCE     = 2.5;   // ±2.5% before action
    private static final int    MIN_BUY_CONVICTION  = 35;    // Legacy guard: don't buy if CQS below this
    private static final int    STCG_WAIT_DAYS      = 45;    // Near-LTCG deferral window

    // Z-Score thresholds (primary signal trigger)
    private static final double Z_BUY_STRONG        = -2.0;  // "Highly Overstretched" — strong buy signal
    private static final double Z_BUY_MILD          = -1.0;  // Mild discount — buy confirmed by drift
    private static final double Z_SELL_STRONG       =  2.0;  // "Overheated" — harvest now
    private static final double Z_SELL_MILD         =  1.0;  // Mild richness — sell confirmed by drift
    private static final double Z_CRITICAL          = -4.0;  // "Sanity Bound" from DESIGN-v2.md

    // Hurst regime
    private static final double H_TRENDING          = 0.55;  // Above this: "Riding the Wave" override
    private static final double H_MEAN_REVERTING    = 0.45;  // Below this: "Rubber Band" confirmed

    public TacticalSignal evaluate(
            AggregatedHolding holding,
            StrategyTarget    target,
            MarketMetrics     metrics,
            double            totalPortfolioValue,
            String            amfiCode) {

        double targetPct = target.targetPortfolioPct();
        double sipPct    = target.sipPct();
        double actualPct = totalPortfolioValue > 0
            ? (holding.getCurrentValue() / totalPortfolioValue) * 100.0
            : 0.0;

        double targetValueRs  = (targetPct / 100.0) * totalPortfolioValue;
        double diffAmount     = targetValueRs - holding.getCurrentValue();
        double overweightPct  = actualPct - targetPct;

        // ── Extract new metrics (with safe fallbacks) ─────────────────────────
        double  z   = metrics.rollingZScore252();   // Primary signal trigger
        double  H   = metrics.hurstExponent();      // Safety gate
        double  vt  = metrics.volatilityTax();      // Harvest explanation
        String  regime    = metrics.hurstRegime();
        double  rarity    = metrics.historicalRarityPct();

        String status = resolveStatus(targetPct, sipPct, actualPct);
        SignalType action = SignalType.HOLD;
        List<String> justifications = new ArrayList<>();

        // ══════════════════════════════════════════════════════════════════════
        // CASE 1: DROPPED FUND — full exit logic
        // ══════════════════════════════════════════════════════════════════════
        if ("DROPPED".equals(status)) {
            action = SignalType.EXIT;
            justifications.add("Strategic: Explicitly marked as DROPPED. Target is 0%.");
            applyTaxJustifications(holding, justifications, STCG_WAIT_DAYS);
            if (action == SignalType.EXIT && holding.getDaysToNextLtcg() > 0
                    && holding.getDaysToNextLtcg() <= STCG_WAIT_DAYS) {
                action = SignalType.HOLD;
            }

            ReasoningMetadata meta = buildDroppedMetadata(holding, z, H, vt, regime);
            return buildSignal(holding, amfiCode, action, diffAmount, targetPct, actualPct,
                    sipPct, status, metrics, justifications, meta);
        }

        // ══════════════════════════════════════════════════════════════════════
        // CASE 2: OVERWEIGHT — potential SELL / HARVEST
        // ══════════════════════════════════════════════════════════════════════
        if (actualPct > targetPct + DRIFT_TOLERANCE) {

            // ── HURST SAFETY GATE: Trending fund — don't cut winners early ───
            if (H > H_TRENDING && z < Z_SELL_STRONG) {
                action = SignalType.HOLD;
                justifications.add(String.format(Locale.US,
                    "Wave Rider Override: H=%.2f indicates a trending regime. " +
                    "Z-Score %.2fσ has not yet reached overheated territory (>+%.1fσ). " +
                    "Keeping position open to capture remaining momentum.",
                    H, z, Z_SELL_STRONG));

                ReasoningMetadata meta = buildWaveRiderMetadata(holding, z, H, vt, regime, overweightPct);
                return buildSignal(holding, amfiCode, action, 0, targetPct, actualPct,
                        sipPct, status, metrics, justifications, meta);
            }

            // ── Primary Z-Score SELL trigger ─────────────────────────────────
            justifications.add(String.format(Locale.US,
                "Overweight by %.2f%% (actual %.2f%% vs target %.2f%%). " +
                "Z-Score: %.2fσ. Hurst: %.2f (%s).",
                overweightPct, actualPct, targetPct, z, H, regime));

            double harvestAmount = (overweightPct / 100.0) * totalPortfolioValue * vt;

            if (z >= Z_SELL_STRONG) {
                justifications.add(String.format(Locale.US,
                    "Volatility Harvest: Z-Score +%.2fσ — fund is statistically overheated " +
                    "(only %.1f%% of days are this expensive). Trim now to capture ₹%,.0f " +
                    "in 'extra' growth before mean reversion.",
                    z, rarity, harvestAmount));
                action = SignalType.SELL;
            } else {
                justifications.add(String.format(Locale.US,
                    "Volatility Harvest: Trim drifted position. " +
                    "Estimated ₹%,.0f rebalancing bonus from variance drag (VT=%.2f%% p.a.).",
                    harvestAmount, vt * 100));
                action = SignalType.SELL;
            }

            action = applyTaxOverride(holding, action, justifications, totalPortfolioValue,
                    overweightPct, STCG_WAIT_DAYS);

            ReasoningMetadata meta = buildHarvestMetadata(holding, z, H, vt, regime, rarity,
                    harvestAmount, action);
            return buildSignal(holding, amfiCode, action, diffAmount, targetPct, actualPct,
                    sipPct, status, metrics, justifications, meta);
        }

        // ══════════════════════════════════════════════════════════════════════
        // CASE 3: UNDERWEIGHT — potential BUY
        // ══════════════════════════════════════════════════════════════════════
        if (actualPct < targetPct - DRIFT_TOLERANCE) {
            double deficit = targetPct - actualPct;

            // ── SANITY BOUND: DESIGN-v2.md requirement ───────────────────────
            if (z < Z_CRITICAL) {
                action = SignalType.WATCH;
                justifications.add(String.format(Locale.US,
                    "CRITICAL REVIEW: Z-Score %.2fσ is below sanity bound (%.1fσ). " +
                    "This may indicate a structural crash rather than a healthy dip. " +
                    "Manual review required before buying.",
                    z, Z_CRITICAL));
                
                ReasoningMetadata meta = buildCriticalReviewMetadata(holding, z, H, regime, rarity, deficit);
                return buildSignal(holding, amfiCode, action, 0, targetPct, actualPct,
                        sipPct, status, metrics, justifications, meta);
            }

            // ── HURST SAFETY GATE: Trending downward — don't buy falling knives ─
            if (H > H_TRENDING && z > Z_BUY_MILD) {
                action = SignalType.WATCH;
                justifications.add(String.format(Locale.US,
                    "Trending Caution: H=%.2f indicates downward momentum. " +
                    "Z-Score %.2fσ has not yet reached discount territory (<%.1fσ). " +
                    "Fund is underweight by %.2f%% but buying into a trend is risky. " +
                    "Wait for Z-Score to confirm the dip is over.",
                    H, z, Z_BUY_MILD, deficit));

                ReasoningMetadata meta = buildWatchMetadata(holding, z, H, regime, deficit);
                return buildSignal(holding, amfiCode, action, 0, targetPct, actualPct,
                        sipPct, status, metrics, justifications, meta);
            }

            // ── MEAN REVERTING + CHEAP: Classic "Rubber Band" buy ─────────────
            if (H < H_MEAN_REVERTING && z <= Z_BUY_STRONG) {
                justifications.add(String.format(Locale.US,
                    "Rubber Band Buy: H=%.2f (Mean Reverting) + Z-Score %.2fσ. " +
                    "This fund is statistically cheap in only %.1f%% of historical days. " +
                    "Mean-reverting funds snap back — buying the dip is safe here.",
                    H, z, rarity));
                action = SignalType.BUY;
            }
            else if (metrics.convictionScore() > 0 && metrics.convictionScore() < MIN_BUY_CONVICTION) {
                action = SignalType.WATCH;
                justifications.add(String.format(Locale.US,
                    "BUY suppressed: Conviction %d < threshold %d. " +
                    "Underweight by %.2f%% but fundamentals are deteriorating. Review fund.",
                    metrics.convictionScore(), MIN_BUY_CONVICTION, deficit));
            } else {
                justifications.add(String.format(Locale.US,
                    "Underweight by %.2f%%. Z-Score %.2fσ (%.1f%% historical rarity). " +
                    "Conviction: %d/100. Status: %s.",
                    deficit, z, rarity, metrics.convictionScore(), status));
                action = SignalType.BUY;
            }

            ReasoningMetadata meta = buildRubberBandMetadata(holding, z, H, regime, rarity,
                    deficit, action);
            return buildSignal(holding, amfiCode, action, diffAmount, targetPct, actualPct,
                    sipPct, status, metrics, justifications, meta);
        }

        // ══════════════════════════════════════════════════════════════════════
        // CASE 4: WITHIN TOLERANCE — HOLD
        // ══════════════════════════════════════════════════════════════════════
        justifications.add(String.format(Locale.US,
            "Within ±%.1f%% target tolerance (actual %.2f%%, target %.2f%%). Z=%.2fσ, H=%.2f.",
            DRIFT_TOLERANCE, actualPct, targetPct, z, H));

        ReasoningMetadata meta = ReasoningMetadata.neutral(holding.getSchemeName());
        return buildSignal(holding, amfiCode, SignalType.HOLD, 0, targetPct, actualPct,
                sipPct, status, metrics, justifications, meta);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String resolveStatus(double targetPct, double sipPct, double actualPct) {
        if (targetPct == 0.0 && sipPct == 0.0 && actualPct > 0.0) return "DROPPED";
        if (sipPct > 0.0 && actualPct < targetPct) return "ACCUMULATOR";
        return "ACTIVE";
    }

    private SignalType applyTaxOverride(AggregatedHolding h, SignalType action,
            List<String> justs, double totalValue, double overweightPct, int stcgWaitDays) {
        if (h.getLtcgAmount() > 0) {
            justs.add(String.format(Locale.US,
                "Tax Strategy: Selling oldest lots first — ₹%.2f in LTCG gains. Use HIFO.",
                h.getLtcgAmount()));
            return SignalType.SELL;
        }
        if (h.getStcgValue() > 0 && h.getDaysToNextLtcg() > 0 && h.getDaysToNextLtcg() <= stcgWaitDays) {
            justs.add(String.format(Locale.US,
                "Action overridden to HOLD: %d days until LTCG on ₹%.2f. Redirect SIP instead.",
                h.getDaysToNextLtcg(), h.getStcgValue()));
            return SignalType.HOLD;
        }
        if (h.getStcgValue() > 0 && h.getDaysToNextLtcg() > stcgWaitDays) {
            double stcgTax  = h.getStcgAmount() * 0.20;
            double driftCost = (overweightPct / 100.0) * totalValue * 0.015;
            if (stcgTax >= driftCost) {
                justs.add(String.format(Locale.US,
                    "HOLD: STCG tax ₹%.2f exceeds drift drag ₹%.2f. Pause SIP instead.",
                    stcgTax, driftCost));
                return SignalType.HOLD;
            }
            justs.add(String.format(Locale.US,
                "STCG override lifted: Tax ₹%.2f < drift drag ₹%.2f. Trim now.",
                stcgTax, driftCost));
        }
        return action;
    }

    private void applyTaxJustifications(AggregatedHolding h, List<String> justs, int stcgWaitDays) {
        if (h.getLtcgAmount() > 0)
            justs.add(String.format(Locale.US,
                "Tax Benefit: Exiting unlocks ₹%.2f in LTCG. Stay within ₹1.25L annual limit.",
                h.getLtcgAmount()));
        if (h.getStcgAmount() > 0 && h.getDaysToNextLtcg() > 0 && h.getDaysToNextLtcg() <= stcgWaitDays)
            justs.add(String.format(Locale.US,
                "Exit deferred: %d days until LTCG on ₹%.2f. Waiting saves 20%% STCG.",
                h.getDaysToNextLtcg(), h.getStcgValue()));
    }

    // ── ReasoningMetadata builders ─────────────────────────────────────────────

    private ReasoningMetadata buildRubberBandMetadata(AggregatedHolding h, double z,
            double H, String regime, double rarity, double deficit, SignalType action) {
        boolean isStrong = z <= Z_BUY_STRONG;
        String zLabel    = z <= Z_BUY_STRONG ? "STATISTICALLY_CHEAP" : "SLIGHTLY_CHEAP";
        String metaphor  = "RUBBER_BAND";
        String noob = isStrong
            ? String.format("This fund is on a rare %.0f%%-of-days discount. It's a stretched rubber band — a snapback is statistically due.", rarity)
            : String.format("The fund is %.2f%% underweight and showing a mild price dip.", deficit);
        return new ReasoningMetadata(
            h.getSchemeName() + " is underweight by " + String.format("%.2f%%", deficit),
            String.format("Z-Score: %.2fσ | H=%.2f (%s)", z, H, regime),
            noob, metaphor, z, H, 0.0, regime, zLabel, rarity, 0.0, ""
        );
    }

    private ReasoningMetadata buildHarvestMetadata(AggregatedHolding h, double z,
            double H, double vt, String regime, double rarity, double harvest, SignalType action) {
        String zLabel   = z >= Z_SELL_STRONG ? "OVERHEATED" : "SLIGHTLY_RICH";
        String metaphor = z >= Z_SELL_STRONG ? "THERMOMETER" : "VOLATILITY_HARVEST";
        String noob = z >= Z_SELL_STRONG
            ? String.format("This fund is statistically expensive (top %.0f%% of days). Time to lock in gains before the heat breaks.", 100 - rarity)
            : String.format("We are capturing ₹%,.0f in 'extra' growth created by market chaos. Free money.", harvest);
        String harvestExpl = String.format(
            "Rebalancing captures ₹%,.0f in variance drag (VT=%.2f%% p.a.) that would otherwise erode returns.",
            harvest, vt * 100);
        return new ReasoningMetadata(
            "Trim " + h.getSchemeName() + " — drifted overweight",
            String.format("Z-Score: +%.2fσ | H=%.2f (%s) | VT=%.2f%%", z, H, regime, vt * 100),
            noob, metaphor, z, H, vt, regime, zLabel, rarity, harvest, harvestExpl
        );
    }

    private ReasoningMetadata buildWaveRiderMetadata(AggregatedHolding h, double z,
            double H, double vt, String regime, double overweightPct) {
        return new ReasoningMetadata(
            h.getSchemeName() + " is trending upward — holding overweight position",
            String.format("H=%.2f (Trending) | Z=%.2fσ — not yet overheated", H, z),
            String.format("Riding the Wave — this fund is in a strong uptrend (H=%.2f). We won't cut profits yet. Wait for Z>+%.1fσ to harvest.", H, Z_SELL_STRONG),
            "WAVE_RIDER", z, H, vt, regime, "NEUTRAL", 50.0, 0.0, ""
        );
    }

    private ReasoningMetadata buildWatchMetadata(AggregatedHolding h, double z,
            double H, String regime, double deficit) {
        return new ReasoningMetadata(
            h.getSchemeName() + " is underweight but in a downtrend — waiting",
            String.format("H=%.2f (Trending Down) | Z=%.2fσ — no discount yet", H, z),
            String.format("Don't catch a falling knife. The fund is %.2f%% underweight but still trending down (H=%.2f). We'll buy once Z-Score confirms the dip is done.", deficit, H),
            "COOLING_OFF", z, H, 0.0, regime, "NEUTRAL", 50.0, 0.0, ""
        );
    }

    private ReasoningMetadata buildCriticalReviewMetadata(AggregatedHolding h, double z,
            double H, String regime, double rarity, double deficit) {
        return new ReasoningMetadata(
            h.getSchemeName() + " requires CRITICAL REVIEW",
            String.format("Z-Score: %.2fσ | H=%.2f (%s)", z, H, regime),
            "This fund is crashing harder than usual. It's below our sanity bound — manual review needed to ensure it's not a structural failure.",
            "COOLING_OFF", z, H, 0.0, regime, "CRITICAL_REVIEW", rarity, 0.0, ""
        );
    }

    private ReasoningMetadata buildDroppedMetadata(AggregatedHolding h, double z,
            double H, double vt, String regime) {
        return new ReasoningMetadata(
            h.getSchemeName() + " has been removed from the strategy",
            "Status: DROPPED — Strategic Exit",
            "This fund is no longer part of your investment plan. Time to exit cleanly.",
            "COOLING_OFF", z, H, vt, regime, "NEUTRAL", 50.0, 0.0, ""
        );
    }

    private TacticalSignal buildSignal(AggregatedHolding h, String amfi, SignalType action,
            double amount, double targetPct, double actualPct, double sipPct, String status,
            MarketMetrics m, List<String> justs, ReasoningMetadata meta) {
        return new TacticalSignal(
            h.getSchemeName(), amfi, action,
            String.format(Locale.US, "%.2f", Math.abs(amount)),
            round(targetPct), round(actualPct), round(sipPct), status,
            m.convictionScore(), m.sortinoRatio(), m.maxDrawdown(),
            m.navPercentile3yr(), m.drawdownFromAth(), m.returnZScore(),
            m.lastBuyDate(), justs, meta);
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
