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
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.ReasoningMetadata;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.core.utils.FundStatus;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RebalanceEngine {

    private final PositionSizingService positionSizingService;

    private static final double DRIFT_TOLERANCE     = 2.5;   
    private static final int    MIN_BUY_CONVICTION  = 35;    

    private static final double Z_BUY_STRONG        = -2.0;  
    private static final double Z_BUY_MILD          = -1.0;  
    private static final double Z_SELL_STRONG       =  2.0;  
    private static final double Z_SELL_MILD         =  1.0;  

    private static final double H_TRENDING          = 0.55;  
    private static final double H_MEAN_REVERTING    = 0.45;  

    private static final int LTCG_WAIT_THRESHOLD_DAYS = 90;

    /**
     * Graduated trim: avoids correcting the full drift in one transaction.
     */
    private double computeGraduatedSellAmount(
            double overweightPct, double totalPortfolioValue) {
        double excess = (overweightPct / 100.0) * totalPortfolioValue;
        if (excess <= 0) return 0;
        double span = Math.max(10.0 - DRIFT_TOLERANCE, 1.0);
        double f = Math.min(1.0, Math.max(0.0, (overweightPct - DRIFT_TOLERANCE) / span));
        double trimFraction = 0.40 + 0.60 * f;
        return excess * trimFraction;
    }

    public TacticalSignal evaluate(
            AggregatedHolding holding,
            StrategyTarget    target,
            MarketMetrics     metrics,
            double            totalPortfolioValue,
            String            amfiCode,
            List<AggregatedHolding> allHoldings,
            Map<String, String> nameToAmfiMap,
            double            originalSheetPct,
            double            fyLtcgAlreadyRealized,
            TailRiskLevel     tailRisk) {

        List<String> justifications = new ArrayList<>();
        if (tailRisk == TailRiskLevel.CRITICAL) {
            justifications.add("🚨 Systemic Risk Warning: Portfolio CVaR is critical. Position sizes should be monitored.");
        } else if (tailRisk == TailRiskLevel.ELEVATED) {
            justifications.add("⚠️ Elevated Risk: Portfolio tail risk is rising.");
        }

        double targetPct = target.targetPortfolioPct();
        double sipPct    = target.sipPct();
        double actualPct = totalPortfolioValue > 0 ? (holding.getCurrentValue() / totalPortfolioValue) * 100.0 : 0.0;
        double overweightPct  = actualPct - targetPct;

        double z = metrics.rollingZScore252();
        double H = metrics.hurstExponent();
        double vt = metrics.volatilityTax();
        String regime = metrics.hurstRegime();
        double rarity = metrics.historicalRarityPct();

        FundStatus status = resolveStatus(targetPct, sipPct, actualPct, originalSheetPct);
        
        // --- GUARD: Core SIP safety ---
        if (sipPct > 0 && (status == FundStatus.DROPPED || status == FundStatus.EXIT)) {
            status = FundStatus.ACTIVE;
        }

        // --- ASSET CLASS ROUTING ---
        if (status == FundStatus.REBALANCER) {
            return buildSignal(holding, amfiCode, SignalType.HOLD, 0, targetPct, actualPct, sipPct, status, metrics, List.of("Rebalancer: Liquidity parking vehicle for rebalancing proceeds. No tactical signals."), ReasoningMetadata.neutral(holding.getSchemeName()));
        }

        if (status == FundStatus.ACCUMULATOR) {
            return evaluateCommodityAccumulator(holding, target, metrics, totalPortfolioValue, amfiCode);
        }

        // --- DROPPED FUND RULE ---
        if (status == FundStatus.DROPPED || status == FundStatus.EXIT) {
            return handleDroppedFundExit(holding, target, metrics, totalPortfolioValue, amfiCode, status, fyLtcgAlreadyRealized);
        }

        SignalType action = SignalType.HOLD;

        // --- REBALANCING ONLY: SELL LOGIC ---
        if (actualPct > targetPct + DRIFT_TOLERANCE) {
            // Alpha Capture (Wave Rider) for active funds
            if (H > 0.62 && z < Z_SELL_MILD) {
                justifications.add(String.format(
                    "Wave Rider: Strong persistent trend (H=%.2f > 0.62) at fair value (z=%.2f). " +
                    "Holding overweight position to capture momentum alpha.", H, z));
                ReasoningMetadata meta = buildWaveRiderMetadata(holding, z, H, vt, regime, actualPct, metrics);
                return buildSignal(holding, amfiCode, SignalType.HOLD, 0, targetPct, actualPct, sipPct, status, metrics, justifications, meta);
            }
            
            double harvestAmount = computeGraduatedSellAmount(overweightPct, totalPortfolioValue);
            double sellThreshold = Z_SELL_STRONG;
            
            if (z >= sellThreshold) {
                justifications.add("Volatility Harvest triggered: Asset is statistically expensive.");
                action = SignalType.SELL;
            } else {
                action = SignalType.SELL;
                justifications.add("Rebalance: Position drift exceeds tolerance.");
            }
            
            ReasoningMetadata meta = buildHarvestMetadata(holding, z, H, vt, regime, rarity, harvestAmount, action, metrics, totalPortfolioValue, overweightPct);
            return buildSignal(holding, amfiCode, action, harvestAmount, targetPct, actualPct, sipPct, status, metrics, justifications, meta);
        }

        // --- REBALANCING ONLY: BUY LOGIC ---
        if (actualPct < targetPct - DRIFT_TOLERANCE) {
            double deficit = targetPct - actualPct;
            
            if (status == FundStatus.NEW_ENTRY) {
                action = SignalType.BUY;
                justifications.add("New Position: Initial entry signal based on strategy.");
            }

            // Market Regime Suspension
            if ("VOLATILE_BEAR".equals(metrics.hmmState()) && metrics.hmmTransitionBearProb() > 0.60) {
                justifications.add("Market Caution: HMM indicates high probability of bear transition. Suspending buys.");
                ReasoningMetadata meta = buildWatchMetadata(holding, z, H, regime, deficit, metrics);
                return buildSignal(holding, amfiCode, SignalType.WATCH, 0, targetPct, actualPct, sipPct, status, metrics, justifications, meta);
            }
            
            // Trending Guard
            if (H > H_TRENDING && z > Z_BUY_MILD) {
                justifications.add("Trending Guard: Asset is trending but not yet at a deep discount.");
                ReasoningMetadata meta = buildWatchMetadata(holding, z, H, regime, deficit, metrics);
                return buildSignal(holding, amfiCode, SignalType.WATCH, 0, targetPct, actualPct, sipPct, status, metrics, justifications, meta);
            }
            
            double buyThreshold = Z_BUY_STRONG;
            if (H < H_MEAN_REVERTING && z <= buyThreshold) {
                action = SignalType.BUY;
                justifications.add("Rubber Band: Mean reversion trigger at statistical low.");
            }
            else if (metrics.ouValid() && metrics.ouHalfLife() < 20 && z < -0.8) {
                action = SignalType.BUY;
                justifications.add("OU Trigger: Fast mean reversion detected.");
            }
            else if (metrics.convictionScore() > 0 && metrics.convictionScore() < MIN_BUY_CONVICTION) {
                action = SignalType.WATCH;
                justifications.add("Conviction Guard: Score below " + MIN_BUY_CONVICTION + ". Waiting for fundamental strength.");
            }
            else if (z <= buyThreshold) {
                action = SignalType.BUY;
                justifications.add("Value Buy: Asset trading at significant discount.");
            }

            ReasoningMetadata meta = buildRubberBandMetadata(holding, z, H, regime, rarity, deficit, action, metrics);
            double diffAmount = (targetPct / 100.0) * totalPortfolioValue - holding.getCurrentValue();
            double buyAmount = positionSizingService.calculateAdjustedBuySize(Math.abs(diffAmount), deficit);
            return buildSignal(holding, amfiCode, action, buyAmount, targetPct, actualPct, sipPct, status, metrics, justifications, meta);
        }

        ReasoningMetadata meta = ReasoningMetadata.neutral(holding.getSchemeName());
        return buildSignal(holding, amfiCode, SignalType.HOLD, 0, targetPct, actualPct, sipPct, status, metrics, justifications, meta);
    }

    private TacticalSignal evaluateCommodityAccumulator(
            AggregatedHolding holding, StrategyTarget target, MarketMetrics metrics,
            double totalPortfolioValue, String amfiCode) {
        double z = metrics.rollingZScore252();
        double navPctile = metrics.navPercentile1yr();
        double targetPct = target.targetPortfolioPct();
        double actualPct = totalPortfolioValue > 0 ? (holding.getCurrentValue() / totalPortfolioValue) * 100.0 : 0.0;
        
        if (z <= -1.5 && navPctile < 0.25) {
            return buildSignal(holding, amfiCode, SignalType.BUY, 0,
                targetPct, actualPct, 0, FundStatus.ACCUMULATOR,
                metrics, List.of("Tactical Accumulator: Gold/Silver at 1yr low. Accumulate on dip."), 
                ReasoningMetadata.neutral(holding.getSchemeName()));
        }
        if (actualPct > targetPct * 2.0 && z > 1.5) {
            return buildSignal(holding, amfiCode, SignalType.SELL, 
                holding.getCurrentValue() * 0.25, // Trim 25%
                targetPct, actualPct, 0, FundStatus.ACCUMULATOR,
                metrics, List.of("Tactical Accumulator: Massively overweight and expensive. Harvesting partial gains."),
                ReasoningMetadata.neutral(holding.getSchemeName()));
        }
        return buildSignal(holding, amfiCode, SignalType.HOLD, 0, targetPct, actualPct, 0, FundStatus.ACCUMULATOR, metrics, List.of("Commodity: Within normal tactical range."), ReasoningMetadata.neutral(holding.getSchemeName()));
    }

    private TacticalSignal handleDroppedFundExit(
            AggregatedHolding holding,
            StrategyTarget    target,
            MarketMetrics     metrics,
            double            totalPortfolioValue,
            String            amfiCode,
            FundStatus        status,
            double            fyLtcgAlreadyRealized) {

        double z     = metrics.rollingZScore252();
        double H     = metrics.hurstExponent();
        String regime = metrics.hurstRegime();
        double vt    = metrics.volatilityTax();

        List<String> justifications = new ArrayList<>();

        if ("VOLATILE_BEAR".equals(metrics.hmmState())) {
            justifications.add("Beta Mitigation: VOLATILE_BEAR regime. Exiting immediately to reduce drawdown exposure.");
            return buildSignal(holding, amfiCode, SignalType.EXIT,
                holding.getCurrentValue(), 0.0,
                safeActualPct(holding, totalPortfolioValue), 0.0,
                status, metrics, justifications,
                buildDroppedMetadata(holding, z, H, vt, regime, metrics));
        }

        double ltcgExemptionRemaining = Math.max(0, 125000.0 - fyLtcgAlreadyRealized);
        double ltcgGains = holding.getLtcgAmount();
        double stcgGains = holding.getStcgAmount();

        boolean allGainsAreLtcg = (ltcgGains > 0 && stcgGains < 100);
        boolean fitsInExemption = (ltcgGains <= ltcgExemptionRemaining);

        if (allGainsAreLtcg && fitsInExemption) {
            justifications.add(String.format(
                "Tax-Free Exit: All unrealized gains (₹%.0f) are LTCG and fit within " +
                "remaining ₹%.0f FY exemption. Exiting NOW is tax-free.",
                ltcgGains, ltcgExemptionRemaining));
            return buildSignal(holding, amfiCode, SignalType.EXIT,
                holding.getCurrentValue(), 0.0,
                safeActualPct(holding, totalPortfolioValue), 0.0,
                status, metrics, justifications,
                buildDroppedMetadata(holding, z, H, vt, regime, metrics));
        }

        if (holding.getDaysToNextLtcg() > 0 && holding.getDaysToNextLtcg() <= LTCG_WAIT_THRESHOLD_DAYS && stcgGains > 1000) {
            double taxSavingIfWait = stcgGains * (0.20 - 0.125);
            double driftCostOfWaiting = holding.getCurrentValue() * vt * (holding.getDaysToNextLtcg() / 252.0);
            if (taxSavingIfWait > driftCostOfWaiting) {
                justifications.add(String.format(
                    "LTCG Harvest: %d days to LTCG conversion. Waiting saves ₹%.0f in tax " +
                    "vs ₹%.0f estimated drift cost. Holding.",
                    holding.getDaysToNextLtcg(), taxSavingIfWait, driftCostOfWaiting));
                return buildSignal(holding, amfiCode, SignalType.HOLD,
                    0.0, 0.0,
                    safeActualPct(holding, totalPortfolioValue), 0.0,
                    status, metrics, justifications,
                    buildWaveRiderMetadata(holding, z, H, vt, regime,
                        safeActualPct(holding, totalPortfolioValue), metrics));
            }
        }

        double actualPct = safeActualPct(holding, totalPortfolioValue);
        if (H > 0.62 && z < 0.0 && actualPct < 5.0) {
            justifications.add(String.format(
                "Wave Rider: Strong trend (H=%.2f) on undervalued dropped fund (z=%.2f). " +
                "Holding for momentum capture before planned exit.",
                H, z));
            return buildSignal(holding, amfiCode, SignalType.HOLD,
                0.0, 0.0, actualPct, 0.0,
                status, metrics, justifications,
                buildWaveRiderMetadata(holding, z, H, vt, regime, actualPct, metrics));
        }

        justifications.add("Strategic Exit: No tax or momentum reason to delay. Exiting dropped fund.");
        return buildSignal(holding, amfiCode, SignalType.EXIT,
            holding.getCurrentValue(), 0.0, actualPct, 0.0,
            status, metrics, justifications,
            buildDroppedMetadata(holding, z, H, vt, regime, metrics));
    }

    private double safeActualPct(AggregatedHolding h, double totalPortfolioValue) {
        return totalPortfolioValue > 0 ? (h.getCurrentValue() / totalPortfolioValue) * 100.0 : 0.0;
    }

    private ReasoningMetadata buildRubberBandMetadata(AggregatedHolding h, double z, double H, String regime, double rarity, double deficit, SignalType action, MarketMetrics metrics) {
        return new ReasoningMetadata(h.getSchemeName() + " discount", "", "Statistical discount detected.", "RUBBER_BAND", z, H, 0.0, regime, z <= Z_BUY_STRONG ? "STATISTICALLY_CHEAP" : "SLIGHTLY_CHEAP", rarity, 0.0, "", metrics.ouHalfLife(), "", null);
    }

    private ReasoningMetadata buildHarvestMetadata(AggregatedHolding h, double z, double H, double vt, String regime, double rarity, double harvest, SignalType action, MarketMetrics metrics, double totalValue, double overweightPct) {
        return new ReasoningMetadata("Trim " + h.getSchemeName(), "", "Harvesting rebalance bonus.", "VOLATILITY_HARVEST", z, H, vt, regime, z >= Z_SELL_STRONG ? "OVERHEATED" : "SLIGHTLY_RICH", rarity, harvest, "", metrics.ouHalfLife(), "", null);
    }

    private ReasoningMetadata buildWaveRiderMetadata(AggregatedHolding h, double z, double H, double vt, String regime, double overweightPct, MarketMetrics metrics) {
        return new ReasoningMetadata(h.getSchemeName() + " trending", "", "Riding the Wave.", "WAVE_RIDER", z, H, vt, regime, "NEUTRAL", 50.0, 0.0, "", metrics.ouHalfLife(), "", null);
    }

    private ReasoningMetadata buildWatchMetadata(AggregatedHolding h, double z, double H, String regime, double deficit, MarketMetrics metrics) {
        return new ReasoningMetadata(h.getSchemeName() + " cooling", "", "Waiting for pulse.", "COOLING_OFF", z, H, 0.0, regime, "NEUTRAL", 50.0, 0.0, "", metrics.ouHalfLife(), "", null);
    }

    private ReasoningMetadata buildCriticalReviewMetadata(AggregatedHolding h, double z, double H, String regime, double rarity, double deficit, MarketMetrics metrics) {
        return new ReasoningMetadata(h.getSchemeName() + " critical", "", "Critical review required.", "COOLING_OFF", z, H, 0.0, regime, "CRITICAL_REVIEW", rarity, 0.0, "", metrics.ouHalfLife(), "", null);
    }

    private ReasoningMetadata buildDroppedMetadata(AggregatedHolding h, double z, double H, double vt, String regime, MarketMetrics metrics) {
        return new ReasoningMetadata(h.getSchemeName() + " dropped", "", "Strategic exit.", "COOLING_OFF", z, H, vt, regime, "NEUTRAL", 50.0, 0.0, "", metrics.ouHalfLife(), "", null);
    }

    private FundStatus resolveStatus(double targetPct, double sipPct, double actualPct, double originalSheetPct) {
        if (originalSheetPct == 0.0 && sipPct == 0.0 && actualPct > 0.0) return FundStatus.DROPPED;
        if (targetPct > 0.0 && actualPct == 0.0) return FundStatus.NEW_ENTRY;
        if (sipPct > 0.0 && actualPct < targetPct) return FundStatus.ACCUMULATOR;
        return FundStatus.ACTIVE;
    }

    private TacticalSignal buildSignal(AggregatedHolding h, String amfi, SignalType action, double amount, double targetPct, double actualPct, double sipPct, FundStatus status, MarketMetrics m, List<String> justs, ReasoningMetadata meta) {
        List<String> enrichedJusts = new ArrayList<>(justs);
        if (m.lastBuyDate() != null) enrichedJusts.add("Informative: Last buy recorded on " + m.lastBuyDate());
        if (m.lastSellDate() != null) enrichedJusts.add("Informative: Last sell recorded on " + m.lastSellDate());
        
        return TacticalSignal.builder()
            .schemeName(h.getSchemeName())
            .simpleName(CommonUtils.NORMALIZE_NAME.apply(h.getSchemeName()))
            .amfiCode(amfi)
            .action(action)
            .amount(String.format(Locale.US, "%.2f", Math.abs(amount)))
            .plannedPercentage(round(targetPct))
            .actualPercentage(round(actualPct))
            .sipPercentage(round(sipPct))
            .fundStatus(status)
            .convictionScore(m.convictionScore())
            .sortinoRatio(m.sortinoRatio())
            .maxDrawdown(m.maxDrawdown())
            .navPercentile1yr(m.navPercentile1yr())
            .navPercentile3yr(m.navPercentile3yr())
            .drawdownFromAth(m.drawdownFromAth())
            .returnZScore(m.returnZScore())
            .lastBuyDate(m.lastBuyDate())
            .justifications(enrichedJusts)
            .reasoningMetadata(meta)
            .yieldScore(m.yieldScore())
            .riskScore(m.riskScore())
            .valueScore(m.valueScore())
            .painScore(m.painScore())
            .regimeScore(m.regimeScore())
            .frictionScore(m.frictionScore())
            .expenseScore(m.expenseScore())
            .expenseRatio(m.expenseRatio())
            .aumCr(m.aumCr())
            .ouHalfLife(m.ouHalfLife())
            .ouValid(m.ouValid())
            .build();
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
