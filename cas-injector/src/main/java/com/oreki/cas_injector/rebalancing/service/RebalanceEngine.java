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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RebalanceEngine {

    private final SystemicRiskMonitorService systemicRiskMonitor;

    private static final double DRIFT_TOLERANCE     = 2.5;   
    private static final int    MIN_BUY_CONVICTION  = 35;    

    private static final double Z_BUY_STRONG        = -2.0;  
    private static final double Z_BUY_MILD          = -1.0;  
    private static final double Z_SELL_STRONG       =  2.0;  
    private static final double Z_SELL_MILD         =  1.0;  

    private static final double H_TRENDING          = 0.55;  
    private static final double H_MEAN_REVERTING    = 0.45;  

    private static final int LTCG_WAIT_THRESHOLD_DAYS = 90;

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
            justifications.add("🚨 Tail Risk Alert: Portfolio CVaR has breached critical danger threshold. New buys suspended.");
            ReasoningMetadata meta = buildCriticalReviewMetadata(holding, metrics.rollingZScore252(), metrics.hurstExponent(), metrics.hurstRegime(), metrics.historicalRarityPct(), target.targetPortfolioPct() - (totalPortfolioValue > 0 ? (holding.getCurrentValue() / totalPortfolioValue * 100.0) : 0), metrics);
            return buildSignal(holding, amfiCode, SignalType.WATCH, 0.0, target.targetPortfolioPct(), 
                (totalPortfolioValue > 0 ? (holding.getCurrentValue() / totalPortfolioValue * 100.0) : 0), target.sipPct(), "ACTIVE", metrics, justifications, meta);
        }

        double targetPct = target.targetPortfolioPct();
        double sipPct    = target.sipPct();
        double actualPct = totalPortfolioValue > 0 ? (holding.getCurrentValue() / totalPortfolioValue) * 100.0 : 0.0;
        double targetValueRs  = (targetPct / 100.0) * totalPortfolioValue;
        double diffAmount     = targetValueRs - holding.getCurrentValue();
        double overweightPct  = actualPct - targetPct;

        double z = metrics.rollingZScore252();
        double H = metrics.hurstExponent();
        double vt = metrics.volatilityTax();
        String regime = metrics.hurstRegime();
        double rarity = metrics.historicalRarityPct();

        String status = resolveStatus(targetPct, sipPct, actualPct, originalSheetPct);
        
        // --- DROPPED FUND RULE ---
        if ("DROPPED".equals(status) || "EXIT".equals(status)) {
            return handleDroppedFundExit(holding, target, metrics, totalPortfolioValue, amfiCode, status);
        }

        SignalType action = SignalType.HOLD;
        List<String> justifications = new ArrayList<>();
        
        if (tailRisk == TailRiskLevel.ELEVATED) justifications.add("⚠️ Elevated tail risk detected. Monitor closely.");

        // --- REBALANCING ONLY: SELL LOGIC ---
        if (actualPct > targetPct + DRIFT_TOLERANCE) {
            // Alpha Capture (Wave Rider) for active funds
            if (H > H_TRENDING && z < Z_SELL_STRONG) {
                justifications.add("Wave Rider: Trending regime detected. Holding to capture alpha.");
                ReasoningMetadata meta = buildWaveRiderMetadata(holding, z, H, vt, regime, actualPct, metrics);
                return buildSignal(holding, amfiCode, SignalType.HOLD, 0, targetPct, actualPct, sipPct, status, metrics, justifications, meta);
            }
            
            double harvestAmount = (overweightPct / 100.0) * totalPortfolioValue;
            double sellThreshold = metrics.ouValid() ? metrics.ouSellThreshold() : Z_SELL_STRONG;
            if ("CALM_BULL".equals(metrics.hmmState()) && metrics.hmmBullProb() > 0.65) sellThreshold += 0.5;
            
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
            
            if ("NEW_ENTRY".equals(status)) {
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
            
            double buyThreshold = metrics.ouValid() ? metrics.ouBuyThreshold() : Z_BUY_STRONG;
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
            return buildSignal(holding, amfiCode, action, diffAmount, targetPct, actualPct, sipPct, status, metrics, justifications, meta);
        }

        ReasoningMetadata meta = ReasoningMetadata.neutral(holding.getSchemeName());
        return buildSignal(holding, amfiCode, SignalType.HOLD, 0, targetPct, actualPct, sipPct, status, metrics, justifications, meta);
    }

    private TacticalSignal handleDroppedFundExit(AggregatedHolding holding, StrategyTarget target, MarketMetrics metrics, double totalPortfolioValue, String amfiCode, String status) {
        double z = metrics.rollingZScore252();
        double H = metrics.hurstExponent();
        String regime = metrics.hurstRegime();
        List<String> justifications = new ArrayList<>();
        SignalType action = SignalType.EXIT;

        // 1. Beta Mitigation
        if ("VOLATILE_BEAR".equals(metrics.hmmState())) {
            justifications.add("Beta Mitigation: Accelerating exit due to VOLATILE_BEAR regime.");
            ReasoningMetadata meta = buildDroppedMetadata(holding, z, H, metrics.volatilityTax(), regime, metrics);
            return buildSignal(holding, amfiCode, SignalType.EXIT, holding.getCurrentValue(), 0.0, (holding.getCurrentValue()/totalPortfolioValue*100), 0.0, status, metrics, justifications, meta);
        }

        // 2. LTCG Prioritization
        if (holding.getDaysToNextLtcg() > 0 && holding.getDaysToNextLtcg() < LTCG_WAIT_THRESHOLD_DAYS && holding.getStcgValue() > 0) {
            justifications.add("LTCG Harvesting: Within " + LTCG_WAIT_THRESHOLD_DAYS + " days of LTCG threshold. Holding to harvest tax-free gains.");
            action = SignalType.HOLD;
            ReasoningMetadata meta = buildWaveRiderMetadata(holding, z, H, metrics.volatilityTax(), regime, (holding.getCurrentValue()/totalPortfolioValue*100), metrics);
            return buildSignal(holding, amfiCode, action, 0.0, 0.0, (holding.getCurrentValue()/totalPortfolioValue*100), 0.0, status, metrics, justifications, meta);
        }

        // 3. Alpha Capture (Wave Rider)
        if (H > H_TRENDING && z < Z_SELL_STRONG) {
            justifications.add("Wave Rider: Trending regime detected. Holding to capture remaining momentum before exit.");
            action = SignalType.HOLD;
            ReasoningMetadata meta = buildWaveRiderMetadata(holding, z, H, metrics.volatilityTax(), regime, (holding.getCurrentValue()/totalPortfolioValue*100), metrics);
            return buildSignal(holding, amfiCode, action, 0.0, 0.0, (holding.getCurrentValue()/totalPortfolioValue*100), 0.0, status, metrics, justifications, meta);
        }

        justifications.add("Strategic Exit: Fund is marked for liquidation in strategy.");
        ReasoningMetadata meta = buildDroppedMetadata(holding, z, H, metrics.volatilityTax(), regime, metrics);
        return buildSignal(holding, amfiCode, SignalType.EXIT, holding.getCurrentValue(), 0.0, (holding.getCurrentValue()/totalPortfolioValue*100), 0.0, status, metrics, justifications, meta);
    }

    private String resolveStatus(double targetPct, double sipPct, double actualPct, double originalSheetPct) {
        if (originalSheetPct == 0.0 && sipPct == 0.0 && actualPct > 0.0) return "DROPPED";
        if (targetPct > 0.0 && actualPct == 0.0) return "NEW_ENTRY";
        if (sipPct > 0.0 && actualPct < targetPct) return "ACCUMULATOR";
        return "ACTIVE";
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

    private TacticalSignal buildSignal(AggregatedHolding h, String amfi, SignalType action, double amount, double targetPct, double actualPct, double sipPct, String status, MarketMetrics m, List<String> justs, ReasoningMetadata meta) {
        List<String> enrichedJusts = new ArrayList<>(justs);
        if (m.lastBuyDate() != null) enrichedJusts.add("Informative: Last buy recorded on " + m.lastBuyDate());
        if (m.lastSellDate() != null) enrichedJusts.add("Informative: Last sell recorded on " + m.lastSellDate());
        
        return new TacticalSignal(h.getSchemeName(), CommonUtils.NORMALIZE_NAME.apply(h.getSchemeName()), amfi, action, String.format(Locale.US, "%.2f", Math.abs(amount)), round(targetPct), round(actualPct), round(sipPct), status, m.convictionScore(), m.sortinoRatio(), m.maxDrawdown(), m.navPercentile3yr(), m.drawdownFromAth(), m.returnZScore(), m.lastBuyDate(), enrichedJusts, meta, m.hurst20d(), m.hurst60d(), m.multiScaleRegime(), m.ouHalfLife(), m.ouValid(), m.ouBuyThreshold(), m.ouSellThreshold(), false);
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
