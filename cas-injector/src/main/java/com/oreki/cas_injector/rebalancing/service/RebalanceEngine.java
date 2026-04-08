package com.oreki.cas_injector.rebalancing.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;


@Service
public class RebalanceEngine {

    public TacticalSignal evaluate(AggregatedHolding holding,
                                StrategyTarget target,
                                MarketMetrics metrics,
                                double totalPortfolioValue,
                                String amfiCode) {

    final double DRIFT_TOLERANCE   = 2.5;   // ±2.5% before action
    final int    MIN_BUY_CONVICTION = 35;   // Don't buy if conviction below this
    final int    STCG_WAIT_DAYS    = 45;    // If LTCG threshold within 45 days, wait

    double targetPct = target.targetPortfolioPct();
    double sipPct    = target.sipPct();
    double actualPct = (totalPortfolioValue > 0)
            ? (holding.getCurrentValue() / totalPortfolioValue) * 100.0
            : 0.0;

    String status = "ACTIVE";
    if (targetPct == 0.0 && sipPct == 0.0 && actualPct > 0.0) {
        status = "DROPPED";
    } else if (sipPct > 0.0 && actualPct < targetPct) {
        status = "ACCUMULATOR";
    }

    double targetValueInRupees = (targetPct / 100.0) * totalPortfolioValue;
    double diffAmount = targetValueInRupees - holding.getCurrentValue();

    SignalType action = SignalType.HOLD;
    List<String> justifications = new ArrayList<>();

    // ── DROPPED ───────────────────────────────────────────────────────────
    if ("DROPPED".equals(status)) {
        action = SignalType.EXIT;
        justifications.add("Strategic: Explicitly marked as DROPPED. Target is 0%.");
        if (holding.getLtcgAmount() > 0)
            justifications.add(String.format(Locale.US,
                "Tax Benefit: Exiting unlocks ₹%.2f in LTCG. Stay within ₹1.25L annual limit.",
                holding.getLtcgAmount()));
        if (holding.getStcgAmount() > 0) {
            String cat = holding.getAssetCategory() != null ? holding.getAssetCategory().toUpperCase() : "";
            if (cat.contains("DEBT")) {
                justifications.add(String.format(Locale.US,
                    "Tax Warning: Debt STCG ₹%.2f taxed at slab rate (post-Apr 2023).",
                    holding.getStcgAmount()));
            } else if (holding.getDaysToNextLtcg() > 0 && holding.getDaysToNextLtcg() <= STCG_WAIT_DAYS) {
                // SMART OVERRIDE: Close to LTCG threshold — defer exit, save tax
                action = SignalType.HOLD;
                justifications.clear();
                justifications.add(String.format(Locale.US,
                    "Exit deferred: %d days until next lot becomes LTCG-eligible. " +
                    "Waiting saves 20%% STCG on ₹%.2f. Re-evaluate after that date.",
                    holding.getDaysToNextLtcg(), holding.getStcgValue()));
            } else {
                justifications.add(String.format(Locale.US,
                    "Tax Warning: STCG ₹%.2f at 20%%. %d days until next LTCG. Consider waiting.",
                    holding.getStcgAmount(), holding.getDaysToNextLtcg()));
            }
        }
    }

    // ── OVERWEIGHT → SELL ─────────────────────────────────────────────────
    else if (actualPct > (targetPct + DRIFT_TOLERANCE)) {
        double overweightPct = actualPct - targetPct;
        justifications.add(String.format(Locale.US,
            "Strategic: Overweight by %.2f%% (actual %.2f%% vs target %.2f%%).",
            overweightPct, actualPct, targetPct));

        if (holding.getLtcgAmount() > 0) {
            // Healthy: trim LTCG lots first
            action = SignalType.SELL;
            justifications.add(String.format(Locale.US,
                "Tax Strategy: Selling oldest lots first — ₹%.2f in LTCG gains. Use HIFO within LTCG.",
                holding.getLtcgAmount()));

        } else if (holding.getStcgValue() > 0 && holding.getDaysToNextLtcg() > 0
                   && holding.getDaysToNextLtcg() <= STCG_WAIT_DAYS) {
            // Smart override: near LTCG threshold, wait
            action = SignalType.HOLD;
            justifications.add(String.format(Locale.US,
                "Action overridden to HOLD: %d days until LTCG on ₹%.2f. " +
                "Redirect next SIP installment instead of selling to reduce overweight.",
                holding.getDaysToNextLtcg(), holding.getStcgValue()));

        } else if (holding.getStcgValue() > 0 && holding.getDaysToNextLtcg() > STCG_WAIT_DAYS) {
            // Far from LTCG — overweight cost probably exceeds tax cost if large
            double stcgTaxCost = holding.getStcgAmount() * 0.20;
            double driftCost   = (overweightPct / 100.0) * totalPortfolioValue * 0.015; // ~1.5% drag cost
            if (stcgTaxCost < driftCost) {
                action = SignalType.SELL;
                justifications.add(String.format(Locale.US,
                    "STCG override lifted: Tax cost ₹%.2f < drift cost ₹%.2f. Trim now.",
                    stcgTaxCost, driftCost));
            } else {
                action = SignalType.HOLD;
                justifications.add(String.format(Locale.US,
                    "HOLD: STCG tax ₹%.2f exceeds current drift drag ₹%.2f. Pause SIP instead.",
                    stcgTaxCost, driftCost));
            }
        } else {
            // No tax lots or no gains — just trim
            action = SignalType.SELL;
            justifications.add("Trim position to target weight. No material tax impact.");
        }
    }

    // ── UNDERWEIGHT → BUY (with conviction gate) ──────────────────────────
    else if (actualPct < (targetPct - DRIFT_TOLERANCE)) {
        double deficit = targetPct - actualPct;

        // CONVICTION GATE: Don't buy deteriorating funds
        if (metrics.convictionScore() > 0 && metrics.convictionScore() < MIN_BUY_CONVICTION) {
            action = SignalType.WATCH;
            justifications.add(String.format(Locale.US,
                "BUY suppressed: Conviction score %d is below minimum threshold of %d. " +
                "Fund is underweight by %.2f%% but quantitatively deteriorating. " +
                "Review fund selection before deploying capital.",
                metrics.convictionScore(), MIN_BUY_CONVICTION, deficit));
        } else {
            action = SignalType.BUY;
            justifications.add(String.format(Locale.US,
                "Strategic: Underweight by %.2f%%. Conviction: %d/100. Status: %s.",
                deficit, metrics.convictionScore(), status));
        }
    }

    // ── IN TOLERANCE ──────────────────────────────────────────────────────
    else {
        justifications.add(String.format(Locale.US,
            "Within ±%.1f%% target tolerance (actual %.2f%%, target %.2f%%).",
            DRIFT_TOLERANCE, actualPct, targetPct));
    }

    String formattedAmount = String.format(Locale.US, "%.2f", Math.abs(diffAmount));

    return new TacticalSignal(
        holding.getSchemeName(), amfiCode, action, formattedAmount,
        round(targetPct), round(actualPct), round(sipPct), status,
        metrics.convictionScore(), metrics.sortinoRatio(), metrics.maxDrawdown(),
        metrics.navPercentile3yr(), metrics.drawdownFromAth(), metrics.returnZScore(),
        metrics.lastBuyDate(), justifications);
}

    private double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}