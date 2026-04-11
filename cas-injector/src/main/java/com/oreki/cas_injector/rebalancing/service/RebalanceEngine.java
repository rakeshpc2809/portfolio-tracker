package com.oreki.cas_injector.rebalancing.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService.TailRiskLevel;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.ReasoningMetadata;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RebalanceEngine {

    private final SystemicRiskMonitorService systemicRiskMonitor;

    // ── Thresholds ────────────────────────────────────────────────────────────
    private static final double DRIFT_TOLERANCE     = 2.5;   // ±2.5% before action
    private static final int    MIN_BUY_CONVICTION  = 35;    // Legacy guard: don't buy if CQS below this
    private static final int    STCG_WAIT_DAYS      = 45;    // Near-LTCG deferral window

    // Z-Score thresholds (primary signal trigger)
    private static final double Z_BUY_STRONG        = -2.0;  // "Highly Overstretched" — strong buy signal
    private static final double Z_BUY_MILD          = -1.0;  // Mild discount — buy confirmed by drift
    private static final double Z_SELL_STRONG       =  2.0;  // "Overheated" — harvest now
    private static final double Z_SELL_MILD         =  1.0;  // Mild richness — sell confirmed by drift

    // Hurst regime
    private static final double H_TRENDING          = 0.55;  // Above this: "Riding the Wave" override
    private static final double H_MEAN_REVERTING    = 0.45;  // Below this: "Rubber Band" confirmed

    public TacticalSignal evaluate(
            AggregatedHolding holding,
            StrategyTarget    target,
            MarketMetrics     metrics,
            double            totalPortfolioValue,
            String            amfiCode,
            List<AggregatedHolding> allHoldings,
            Map<String, String> nameToAmfiMap,
            double            originalSheetPct) {

        TailRiskLevel tailRisk = systemicRiskMonitor.assessTailRisk(
            allHoldings, Map.of(amfiCode, metrics), nameToAmfiMap);

        if (tailRisk == TailRiskLevel.CRITICAL) {
            List<String> justifications = new ArrayList<>();
            justifications.add("🚨 Tail Risk Alert: Portfolio CVaR has breached the -3.5% " +
                "danger threshold. All new buy signals are suspended until systemic risk normalises. " +
                "This is your portfolio's safety circuit breaker.");
            ReasoningMetadata meta = buildCriticalReviewMetadata(holding, metrics.rollingZScore252(), metrics.hurstExponent(), metrics.hurstRegime(), metrics.historicalRarityPct(), target.targetPortfolioPct() - (totalPortfolioValue > 0 ? (holding.getCurrentValue() / totalPortfolioValue * 100.0) : 0), metrics);
            return buildSignal(holding, amfiCode, SignalType.WATCH, 0.0, target.targetPortfolioPct(), 
                (totalPortfolioValue > 0 ? (holding.getCurrentValue() / totalPortfolioValue * 100.0) : 0), target.sipPct(), "ACTIVE", metrics, justifications, meta);
        }

        boolean elevatedRisk = tailRisk == TailRiskLevel.ELEVATED;

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

        String status = resolveStatus(originalSheetPct, sipPct, actualPct);
        SignalType action = SignalType.HOLD;
        List<String> justifications = new ArrayList<>();
        
        if (elevatedRisk) {
            justifications.add("⚠️ Elevated tail risk detected (CVaR < -2.5%). Position sizing reduced. Monitor closely.");
        }

        // ══════════════════════════════════════════════════════════════════════
        // CASE 1: DROPPED FUND — smart exit logic
        // ══════════════════════════════════════════════════════════════════════
        if ("DROPPED".equals(status)) {
            action = SignalType.EXIT;
            
            // ── HURST SAFETY GATE: Trending fund — let it grow ───
            if (H > H_TRENDING && z < Z_SELL_STRONG) {
                action = SignalType.HOLD;
                justifications.add(String.format(Locale.US,
                    "Wave Rider: Fund is DROPPED, but H=%.2f indicates a strong trending regime. " +
                    "Holding position to capture remaining upside before exiting.", H));
                
                ReasoningMetadata waveRiderMeta = buildWaveRiderMetadata(holding, z, H, vt, regime, actualPct, metrics);
                ReasoningMetadata meta = new ReasoningMetadata(
                    holding.getSchemeName() + " is trending — holding dropped fund",
                    waveRiderMeta.technicalLabel(),
                    "Riding the Wave — SIP stopped, but strong uptrend (H=" + String.format(Locale.US, "%.2f", H) + "). Holding to maximize exit value.",
                    waveRiderMeta.uiMetaphor(),
                    waveRiderMeta.zScore(), waveRiderMeta.hurstExponent(), waveRiderMeta.volatilityTax(), waveRiderMeta.hurstRegime(), 
                    waveRiderMeta.zScoreLabel(), waveRiderMeta.historicalRarityPct(), waveRiderMeta.harvestAmountRupees(), 
                    waveRiderMeta.harvestExplanation(), waveRiderMeta.ouHalfLifeDays(), waveRiderMeta.ouInterpretation(), 
                    waveRiderMeta.featureAttribution()
                );
                return buildSignal(holding, amfiCode, action, diffAmount, targetPct, actualPct,
                        sipPct, status, metrics, justifications, meta);
            } 
            
            if (z >= Z_SELL_STRONG) {
                justifications.add(String.format(Locale.US,
                    "Overheated: Z-Score +%.2fσ. Fund is DROPPED and statistically expensive. Liquidating now.", z));
            } else {
                justifications.add("Strategic Exit: Fund is marked as DROPPED (SIP stopped). Phasing out to redeploy capital.");
            }

            // Apply Tax Logic (Overrides action to HOLD if waiting for LTCG is optimal)
            action = applyTaxOverride(holding, action, justifications, totalPortfolioValue, actualPct, STCG_WAIT_DAYS, metrics, true);

            // If applyTaxOverride returned SELL (e.g. for HIFO), revert it to EXIT for the exit queue
            if (action == SignalType.SELL) {
                action = SignalType.EXIT; 
            }

            ReasoningMetadata meta;
            if (action == SignalType.HOLD) {
                ReasoningMetadata waveRiderMeta = buildWaveRiderMetadata(holding, z, H, vt, regime, actualPct, metrics);
                meta = new ReasoningMetadata(
                    holding.getSchemeName() + " is tax-shielded — holding dropped fund",
                    waveRiderMeta.technicalLabel(),
                    "Tax Shield Active — SIP stopped, but waiting for LTCG benefits before exiting.",
                    waveRiderMeta.uiMetaphor(),
                    waveRiderMeta.zScore(), waveRiderMeta.hurstExponent(), waveRiderMeta.volatilityTax(), waveRiderMeta.hurstRegime(), 
                    waveRiderMeta.zScoreLabel(), waveRiderMeta.historicalRarityPct(), waveRiderMeta.harvestAmountRupees(), 
                    waveRiderMeta.harvestExplanation(), waveRiderMeta.ouHalfLifeDays(), waveRiderMeta.ouInterpretation(), 
                    waveRiderMeta.featureAttribution()
                );
            } else {
                meta = buildDroppedMetadata(holding, z, H, vt, regime, metrics);
            }

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

                ReasoningMetadata meta = buildWaveRiderMetadata(holding, z, H, vt, regime, overweightPct, metrics);
                return buildSignal(holding, amfiCode, action, 0, targetPct, actualPct,
                        sipPct, status, metrics, justifications, meta);
            }

            // ── Primary Z-Score SELL trigger ─────────────────────────────────
            justifications.add(String.format(Locale.US,
                "Overweight by %.2f%% (actual %.2f%% vs target %.2f%%). " +
                "Z-Score: %.2fσ. Hurst: %.2f (%s).",
                overweightPct, actualPct, targetPct, z, H, regime));

            double harvestAmount = (overweightPct / 100.0) * totalPortfolioValue * vt;

            double effectiveSellThreshold = metrics.ouValid() ? metrics.ouSellThreshold() : Z_SELL_STRONG;
            
            // HMM Bull Regime Override
            if ("CALM_BULL".equals(metrics.hmmState()) && metrics.hmmBullProb() > 0.65) {
                effectiveSellThreshold += 0.5;
                justifications.add(String.format(Locale.US,
                    "🌊 Bull Regime Override: HMM confidence in Bull state is %.0f%%. Allowing momentum to run slightly longer before trimming (Threshold raised to %.2fσ).",
                    metrics.hmmBullProb() * 100, effectiveSellThreshold));
            }

            if (metrics.ouValid()) {
                justifications.add(String.format(Locale.US,
                    "📐 OU-Calibrated Exit: Optimal sell zone (%.2fσ) used based on half-life of %.0f days.",
                    metrics.ouSellThreshold(), metrics.ouHalfLife()));
            }

            if (z >= effectiveSellThreshold) {
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
                    overweightPct, STCG_WAIT_DAYS, metrics, false);

            ReasoningMetadata meta = buildHarvestMetadata(holding, z, H, vt, regime, rarity,
                    harvestAmount, action, metrics, totalPortfolioValue, overweightPct);
            return buildSignal(holding, amfiCode, action, diffAmount, targetPct, actualPct,
                    sipPct, status, metrics, justifications, meta);
        }

        // ══════════════════════════════════════════════════════════════════════
        // CASE 3: UNDERWEIGHT — potential BUY
        // ══════════════════════════════════════════════════════════════════════
        if (actualPct < targetPct - DRIFT_TOLERANCE) {
            double deficit = targetPct - actualPct;
            
            if (elevatedRisk) {
                diffAmount = diffAmount / 2.0;
            }

            // ── HMM Safety Gate ──
            if ("VOLATILE_BEAR".equals(metrics.hmmState()) && metrics.hmmTransitionBearProb() > 0.60) {
                action = SignalType.WATCH;
                justifications.add("🧠 Regime Intelligence: The Hidden Markov Model detects a " +
                    String.format("%.0f%%", metrics.hmmTransitionBearProb() * 100) +
                    " probability of remaining in a Volatile Bear regime. " +
                    "Waiting for the market floor before deploying capital. " +
                    "This is not a permanent hold — the model re-evaluates every night.");
                ReasoningMetadata meta = buildWatchMetadata(holding, z, H, regime, deficit, metrics);
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

                ReasoningMetadata meta = buildWatchMetadata(holding, z, H, regime, deficit, metrics);
                return buildSignal(holding, amfiCode, action, 0, targetPct, actualPct,
                        sipPct, status, metrics, justifications, meta);
            }

            // ── OU-CALIBRATED ENTRY ──
            double effectiveBuyThreshold = metrics.ouValid() ? metrics.ouBuyThreshold() : Z_BUY_STRONG;
            if (metrics.ouValid()) {
                justifications.add(String.format(Locale.US,
                    "📐 OU-Calibrated Entry: This fund's mean-reversion half-life is %.0f trading days. " +
                    "The optimal buy zone (%.2fσ) is tighter than the default threshold, reflecting faster recovery speed.",
                    metrics.ouHalfLife(), metrics.ouBuyThreshold()));
            }

            // ── MEAN REVERTING + CHEAP: Classic "Rubber Band" buy ─────────────
            if (H < H_MEAN_REVERTING && z <= effectiveBuyThreshold) {
                justifications.add(String.format(Locale.US,
                    "Rubber Band Buy: H=%.2f (Mean Reverting) + Z-Score %.2fσ. " +
                    "This fund is statistically cheap in only %.1f%% of historical days. " +
                    "Mean-reverting funds snap back — buying the dip is safe here.",
                    H, z, rarity));
                action = SignalType.BUY;
            }
            else if (metrics.ouValid() && metrics.ouHalfLife() < 20 && z < -0.8) {
                action = SignalType.BUY;
                justifications.add(String.format(Locale.US,
                    "⚡ Fast Reverter: Historical data shows this fund recovers half its discount in under 20 trading days (half-life: %.0f days) — deploy before the window closes.",
                    metrics.ouHalfLife()));
            }
            else if (metrics.convictionScore() > 0 && metrics.convictionScore() < MIN_BUY_CONVICTION) {
                action = SignalType.WATCH;
                justifications.add(String.format(Locale.US,
                    "BUY suppressed: Conviction %d < threshold %d. " +
                    "Underweight by %.2f%% but fundamentals are deteriorating. Review fund.",
                    metrics.convictionScore(), MIN_BUY_CONVICTION, deficit));
            } else if (z <= effectiveBuyThreshold) {
                justifications.add(String.format(Locale.US,
                    "Underweight by %.2f%%. Z-Score %.2fσ (%.1f%% historical rarity). " +
                    "Conviction: %d/100. Status: %s.",
                    deficit, z, rarity, metrics.convictionScore(), status));
                action = SignalType.BUY;
            }

            ReasoningMetadata meta = buildRubberBandMetadata(holding, z, H, regime, rarity,
                    deficit, action, metrics);
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
            List<String> justs, double totalValue, double overweightPct, int stcgWaitDays, MarketMetrics metrics,
            boolean isFifoEnforced) {
        
        if (!isFifoEnforced && h.getLtcgAmount() > 0) {
            justs.add(String.format(Locale.US,
                "Tax Strategy: Selling oldest lots first — ₹%,.0f in LTCG gains. Use HIFO.",
                h.getLtcgAmount()));
            return SignalType.SELL;
        }
        
        if (h.getStcgValue() > 0 && h.getDaysToNextLtcg() > 0 && h.getDaysToNextLtcg() <= stcgWaitDays) {
            double driftCostAnnual = (Math.abs(overweightPct) / 100.0) * totalValue * metrics.volatilityTax();
            double taxHit = h.getStcgTaxEstimate() != null ? h.getStcgTaxEstimate() : 0.0;

            if (driftCostAnnual > taxHit) {
                justs.add(String.format(Locale.US,
                    "💡 Wealth Optimal: The estimated annual drift cost (%s) exceeds the tax bill (%s). " +
                    "Rebalancing now preserves more long-term wealth than deferring.",
                    String.format("₹%,.0f", driftCostAnnual), String.format("₹%,.0f", taxHit)));
                return action;
            } else {
                justs.add(String.format(Locale.US,
                    "Tax Shield Active: Waiting %d days saves %s in tax — more than the %s drift cost.",
                    h.getDaysToNextLtcg(), String.format("₹%,.0f", taxHit), String.format("₹%,.0f", driftCostAnnual)));
                return SignalType.HOLD;
            }
        }
        
        if (h.getStcgValue() > 0 && h.getDaysToNextLtcg() > stcgWaitDays) {
            double taxHit  = h.getStcgTaxEstimate() != null ? h.getStcgTaxEstimate() : (h.getStcgAmount() * 0.20);
            double driftCost = (overweightPct / 100.0) * totalValue * metrics.volatilityTax();
            if (taxHit >= driftCost) {
                justs.add(String.format(Locale.US,
                    "HOLD: STCG tax %s exceeds drift drag %s. Pause SIP instead.",
                    String.format("₹%,.0f", taxHit), String.format("₹%,.0f", driftCost)));
                return SignalType.HOLD;
            }
            justs.add(String.format(Locale.US,
                "STCG override lifted: Tax %s < drift drag %s. Trim now.",
                String.format("₹%,.0f", taxHit), String.format("₹%,.0f", driftCost)));
        }
        return action;
    }

    private void applyTaxJustifications(AggregatedHolding h, List<String> justs, int stcgWaitDays) {
        if (h.getLtcgAmount() > 0)
            justs.add(String.format(Locale.US,
                "Tax Benefit: Exiting unlocks ₹%,.0f in LTCG. Stay within ₹1.25L annual limit.",
                h.getLtcgAmount()));
        if (h.getStcgAmount() > 0 && h.getDaysToNextLtcg() > 0 && h.getDaysToNextLtcg() <= stcgWaitDays)
            justs.add(String.format(Locale.US,
                "Exit deferred: %d days until LTCG on ₹%,.0f. Waiting saves 20%% STCG.",
                h.getDaysToNextLtcg(), h.getStcgValue()));
    }

    // ── ReasoningMetadata builders ─────────────────────────────────────────────

    private ReasoningMetadata buildRubberBandMetadata(AggregatedHolding h, double z,
            double H, String regime, double rarity, double deficit, SignalType action, MarketMetrics metrics) {
        boolean isStrong = z <= Z_BUY_STRONG;
        String zLabel    = z <= Z_BUY_STRONG ? "STATISTICALLY_CHEAP" : "SLIGHTLY_CHEAP";
        String metaphor  = "RUBBER_BAND";
        String noob = isStrong
            ? String.format("This fund is on a rare %.0f%%-of-days discount. It's a stretched rubber band — a snapback is statistically due.", rarity)
            : String.format("The fund is %.2f%% underweight and showing a mild price dip.", deficit);
        
        double ouHalfLife = metrics.ouHalfLife();
        String ouInterp = metrics.ouValid() ? formatOuInterpretation(ouHalfLife) : "";

        ReasoningMetadata.FeatureAttribution attr = computeAttribution(z, H, metrics, 0.0, 0.0);

        return new ReasoningMetadata(
            h.getSchemeName() + " is underweight by " + String.format("%.2f%%", deficit),
            String.format("Z-Score: %.2fσ | H=%.2f (%s)", z, H, regime),
            noob, metaphor, z, H, 0.0, regime, zLabel, rarity, 0.0, "", ouHalfLife, ouInterp, attr
        );
    }

    private ReasoningMetadata buildHarvestMetadata(AggregatedHolding h, double z,
            double H, double vt, String regime, double rarity, double harvest, SignalType action, MarketMetrics metrics, double totalValue, double overweightPct) {
        String zLabel   = z >= Z_SELL_STRONG ? "OVERHEATED" : "SLIGHTLY_RICH";
        String metaphor = z >= Z_SELL_STRONG ? "THERMOMETER" : "VOLATILITY_HARVEST";
        String noob = z >= Z_SELL_STRONG
            ? String.format("This fund is statistically expensive (top %.0f%% of days). Time to lock in gains before the heat breaks.", 100 - rarity)
            : String.format("We are capturing ₹%,.0f in 'extra' growth created by market chaos. Free money.", harvest);
        String harvestExpl = String.format(
            "Rebalancing captures ₹%,.0f in variance drag (VT=%.2f%% p.a.) that would otherwise erode returns.",
            harvest, vt * 100);

        double ouHalfLife = metrics.ouHalfLife();
        String ouInterp = metrics.ouValid() ? formatOuInterpretation(ouHalfLife) : "";

        double taxHit = h.getStcgTaxEstimate() != null ? h.getStcgTaxEstimate() : 0.0;
        double driftCost = (Math.abs(overweightPct) / 100.0) * totalValue * vt;
        ReasoningMetadata.FeatureAttribution attr = computeAttribution(z, H, metrics, taxHit, driftCost);

        return new ReasoningMetadata(
            "Trim " + h.getSchemeName() + " — drifted overweight",
            String.format("Z-Score: +%.2fσ | H=%.2f (%s) | VT=%.2f%%", z, H, regime, vt * 100),
            noob, metaphor, z, H, vt, regime, zLabel, rarity, harvest, harvestExpl, ouHalfLife, ouInterp, attr
        );
    }

    private ReasoningMetadata buildWaveRiderMetadata(AggregatedHolding h, double z,
            double H, double vt, String regime, double overweightPct, MarketMetrics metrics) {
        double ouHalfLife = metrics.ouHalfLife();
        String ouInterp = metrics.ouValid() ? formatOuInterpretation(ouHalfLife) : "";
        ReasoningMetadata.FeatureAttribution attr = computeAttribution(z, H, metrics, 0.0, 0.0);
        return new ReasoningMetadata(
            h.getSchemeName() + " is trending upward — holding overweight position",
            String.format("H=%.2f (Trending) | Z=%.2fσ — not yet overheated", H, z),
            String.format("Riding the Wave — this fund is in a strong uptrend (H=%.2f). We won't cut profits yet. Wait for Z>+%.1fσ to harvest.", H, Z_SELL_STRONG),
            "WAVE_RIDER", z, H, vt, regime, "NEUTRAL", 50.0, 0.0, "", ouHalfLife, ouInterp, attr
        );
    }

    private ReasoningMetadata buildWatchMetadata(AggregatedHolding h, double z,
            double H, String regime, double deficit, MarketMetrics metrics) {
        double ouHalfLife = metrics.ouHalfLife();
        String ouInterp = metrics.ouValid() ? formatOuInterpretation(ouHalfLife) : "";
        ReasoningMetadata.FeatureAttribution attr = computeAttribution(z, H, metrics, 0.0, 0.0);
        return new ReasoningMetadata(
            h.getSchemeName() + " is underweight but in a downtrend — waiting",
            String.format("H=%.2f (Trending Down) | Z=%.2fσ — no discount yet", H, z),
            String.format("Don't catch a falling knife. The fund is %.2f%% underweight but still trending down (H=%.2f). We'll buy once Z-Score confirms the dip is done.", deficit, H),
            "COOLING_OFF", z, H, 0.0, regime, "NEUTRAL", 50.0, 0.0, "", ouHalfLife, ouInterp, attr
        );
    }

    private ReasoningMetadata buildCriticalReviewMetadata(AggregatedHolding h, double z,
            double H, String regime, double rarity, double deficit, MarketMetrics metrics) {
        double ouHalfLife = metrics.ouHalfLife();
        String ouInterp = metrics.ouValid() ? formatOuInterpretation(ouHalfLife) : "";
        ReasoningMetadata.FeatureAttribution attr = computeAttribution(z, H, metrics, 0.0, 0.0);
        return new ReasoningMetadata(
            h.getSchemeName() + " requires CRITICAL REVIEW",
            String.format("Z-Score: %.2fσ | H=%.2f (%s)", z, H, regime),
            "This fund is crashing harder than usual. It's below our sanity bound — manual review needed to ensure it's not a structural failure.",
            "COOLING_OFF", z, H, 0.0, regime, "CRITICAL_REVIEW", rarity, 0.0, "", ouHalfLife, ouInterp, attr
        );
    }

    private ReasoningMetadata buildDroppedMetadata(AggregatedHolding h, double z,
            double H, double vt, String regime, MarketMetrics metrics) {
        double ouHalfLife = metrics.ouHalfLife();
        String ouInterp = metrics.ouValid() ? formatOuInterpretation(ouHalfLife) : "";
        ReasoningMetadata.FeatureAttribution attr = computeAttribution(z, H, metrics, 0.0, 0.0);
        return new ReasoningMetadata(
            h.getSchemeName() + " has been removed from the strategy",
            "Status: DROPPED — Strategic Exit",
            "This fund is no longer part of your investment plan. Time to exit cleanly.",
            "COOLING_OFF", z, H, vt, regime, "NEUTRAL", 50.0, 0.0, "", ouHalfLife, ouInterp, attr
        );
    }

    private String formatOuInterpretation(double ouHalfLife) {
        if (ouHalfLife < 20) return String.format("This fund snaps back fast — half its discount gone in under %.0f trading days.", ouHalfLife);
        if (ouHalfLife < 60) return String.format("Moderate recovery pace — expect the discount to narrow over %.0f trading days.", ouHalfLife);
        return String.format("Patient trade — full reversion may take %.0f+ trading days. Size accordingly.", ouHalfLife);
    }

    private ReasoningMetadata.FeatureAttribution computeAttribution(double z, double H, MarketMetrics metrics, double taxHit, double driftCost) {
        double zScoreContrib = clamp(Math.abs(z) / 4.0, 0, 1);
        double hurstContrib = "FRACTAL_BREAKOUT".equals(metrics.multiScaleRegime()) ? 0.8 :
                             "MEAN_REVERTING".equals(metrics.hurstRegime()) ? 0.6 : 0.3;
        double hmmContrib = "CALM_BULL".equals(metrics.hmmState()) ? 0.8 :
                           "VOLATILE_BEAR".equals(metrics.hmmState()) ? 0.2 : 0.5;
        double ouContrib = metrics.ouValid() ? clamp((180 - metrics.ouHalfLife()) / 180, 0.1, 0.9) : 0.1;
        double taxContrib = (taxHit > 0) ? clamp(1.0 - taxHit / (taxHit + driftCost + 0.001), 0, 1) : 0.5;

        double sum = zScoreContrib + hurstContrib + hmmContrib + ouContrib + taxContrib;
        return new ReasoningMetadata.FeatureAttribution(
            zScoreContrib / sum,
            hurstContrib / sum,
            hmmContrib / sum,
            ouContrib / sum,
            taxContrib / sum
        );
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
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
            m.lastBuyDate(), justs, meta,
            m.hurst20d(), m.hurst60d(), m.multiScaleRegime(),
            m.ouHalfLife(), m.ouValid(), m.ouBuyThreshold(), m.ouSellThreshold(), false);
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
