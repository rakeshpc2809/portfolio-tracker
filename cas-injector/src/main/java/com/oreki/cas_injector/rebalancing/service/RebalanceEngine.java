package com.oreki.cas_injector.rebalancing.service;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.utils.FundStatus;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.rebalancing.dto.RebalanceActionDTO;
import com.oreki.cas_injector.backfill.repository.HistoricalNavRepository;
import com.oreki.cas_injector.backfill.model.HistoricalNav;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.backfill.service.NavService;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RebalanceEngine {

    private final HistoricalNavRepository navRepo;
    private final TaxLotRepository taxLotRepository;
    private final NavService amfiService;
    private final RestTemplate restTemplate;

    @Value("${quant.engine.url:http://quant-engine:8001}")
    private String quantEngineUrl;

    @Data
    @Builder
    public static class RebalanceRequest {
        private String pan;
        private BigDecimal totalPortfolioValue;
        private double fyLtcgAlreadyRealized;
        private String tailRiskLevel;
        private List<AggregatedHolding> holdings;
        private List<StrategyTarget> targets;
        private Map<String, MarketMetrics> metrics;
        private Map<String, String> amfiMap;
    }

    /**
     * Generates strict quantitative rebalance signals by querying the Python quant engine.
     */
    public List<RebalanceActionDTO> generateSignals(RebalanceRequest req) {
        log.info("🎛️ Generating strict tactical rebalance actions for PAN: {}", req.getPan());
        List<RebalanceActionDTO> actions = new ArrayList<>();
        LocalDate currentDate = LocalDate.now();

        BigDecimal headroomRemaining = BigDecimal.valueOf(Math.max(0.0, 125000.0 - req.getFyLtcgAlreadyRealized()));

        Map<String, StrategyTarget> targetMap = req.getTargets().stream()
            .collect(Collectors.toMap(StrategyTarget::isin, t -> t, (a, b) -> a));
        
        Set<String> processedIsins = new java.util.HashSet<>();

        // Group holdings into active and mandatory/clutter
        List<AggregatedHolding> mandatoryOrClutterHoldings = new ArrayList<>();
        List<ActiveEvaluationItem> activeItems = new ArrayList<>();

        for (AggregatedHolding holding : req.getHoldings()) {
            String isin = holding.getIsin();
            if (isin == null) continue;
            
            StrategyTarget target = targetMap.get(isin);
            
            double actualPct = 0.0;
            if (req.getTotalPortfolioValue().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal holdingVal = holding.getCurrentValue() != null ? holding.getCurrentValue() : BigDecimal.ZERO;
                actualPct = holdingVal.divide(req.getTotalPortfolioValue(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100.0)).doubleValue();
            }

            boolean isMandatoryExit = false;
            boolean isClutter = false;

            if (target != null) {
                String status = target.status() != null ? target.status().toUpperCase() : "ACTIVE";
                String bucket = target.bucket() != null ? target.bucket().toUpperCase() : "CORE";
                if ("DROPPED".equals(status) || "EXIT".equals(status) || "DROPPED".equals(bucket) || "EXIT".equals(bucket)) {
                    isMandatoryExit = true;
                }
            } else {
                isMandatoryExit = true; // No target weight configured means we want to exit it
            }

            // Clutter definition: targetPct == 0 (or target is null), and actualPct < 0.2%
            if (target != null && target.targetPortfolioPct() == 0 && !isMandatoryExit) {
                if (actualPct < 0.2) {
                    isClutter = true;
                }
            } else if (target == null) {
                if (actualPct < 0.2) {
                    isClutter = true;
                    isMandatoryExit = false; // prioritize clutter label
                }
            }

            if (isMandatoryExit || isClutter) {
                mandatoryOrClutterHoldings.add(holding);
            } else {
                if (target != null) {
                    activeItems.add(new ActiveEvaluationItem(holding, target));
                }
            }
            processedIsins.add(isin);
        }

        // Add active targets that are not yet held
        for (StrategyTarget target : req.getTargets()) {
            String isin = target.isin();
            if (isin == null || processedIsins.contains(isin)) continue;

            if (target.targetPortfolioPct() > 0) {
                AggregatedHolding holding = AggregatedHolding.builder()
                    .isin(isin)
                    .schemeName(target.schemeName())
                    .currentValue(BigDecimal.ZERO)
                    .units(BigDecimal.ZERO)
                    .investedAmount(BigDecimal.ZERO)
                    .ltcgAmount(BigDecimal.ZERO)
                    .stcgAmount(BigDecimal.ZERO)
                    .daysToNextLtcg(0)
                    .build();
                activeItems.add(new ActiveEvaluationItem(holding, target));
                processedIsins.add(isin);
            }
        }

        // --- STEP 1: Process Active Items First ---
        for (ActiveEvaluationItem item : activeItems) {
            AggregatedHolding holding = item.holding;
            StrategyTarget target = item.target;
            String amfiCode = req.getAmfiMap().getOrDefault(target.isin(), "");

            double actualPct = 0.0;
            if (req.getTotalPortfolioValue().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal holdingVal = holding.getCurrentValue() != null ? holding.getCurrentValue() : BigDecimal.ZERO;
                actualPct = holdingVal.divide(req.getTotalPortfolioValue(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100.0)).doubleValue();
            }
            double targetPct = target.targetPortfolioPct();
            double drift = actualPct - targetPct;

            double zScore = 0.0;
            double hurstExponent = 0.5;
            
            if (amfiCode != null && !amfiCode.isBlank()) {
                try {
                    List<HistoricalNav> history = navRepo.findByAmfiCodeOrderByNavDateAsc(amfiCode);
                    if (history != null && history.size() >= 50) {
                        List<Double> navs = history.stream()
                            .map(h -> h.getNav().doubleValue())
                            .collect(Collectors.toList());
                        
                        Map<String, Object> payload = Map.of("navs", navs);
                        Map<?, ?> response = restTemplate.postForObject(
                            quantEngineUrl + "/api/v1/quant/metrics",
                            payload,
                            Map.class
                        );
                        
                        if (response != null) {
                            Number zVal = (Number) response.get("rolling_z_score_252");
                            Number hVal = (Number) response.get("hurst_exponent");
                            if (zVal != null) zScore = zVal.doubleValue();
                            if (hVal != null) hurstExponent = hVal.doubleValue();
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to retrieve quant metrics from python sidecar for amfi {}: {}", amfiCode, e.getMessage());
                }
            }

            BigDecimal currentNav = BigDecimal.ZERO;
            if (amfiCode != null && !amfiCode.isEmpty()) {
                try {
                    var details = amfiService.getLatestSchemeDetails(amfiCode);
                    if (details != null && details.getNav() != null) {
                        currentNav = details.getNav();
                    }
                } catch (Exception e) {
                    log.warn("Failed to retrieve latest NAV for active amfi {}: {}", amfiCode, e.getMessage());
                }
            }
            if (currentNav.compareTo(BigDecimal.ZERO) <= 0) {
                currentNav = holding.getNav() != null ? holding.getNav() : BigDecimal.ZERO;
            }

            String bucket = target.bucket() != null ? target.bucket().toUpperCase() : "CORE";
            if ("PROPORTIONAL".equals(bucket)) {
                bucket = "CORE";
            }
            double threshold = 5.0;
            if ("SATELLITE".equals(bucket)) {
                threshold = 2.0;
            } else if ("TACTICAL".equals(bucket)) {
                threshold = 1.5;
            }

            // Retrieve HMM bear probability
            double hmmBearProb = 0.33; // Default fallback
            if (req.getMetrics() != null && req.getMetrics().containsKey(amfiCode)) {
                var m = req.getMetrics().get(amfiCode);
                if (m != null) {
                    hmmBearProb = m.hmmBearProb();
                }
            }

            String signal = "HOLD";
            BigDecimal unitsToTransact = BigDecimal.ZERO;
            String justification = String.format("Hold: Current deviation (%+.2f%%) is within the rebalance threshold for %s funds (%.1f%%).", drift, bucket, threshold);

            if (Math.abs(drift) >= threshold) {
                if (drift < 0) {
                    // Underweight (BUY)
                    if (zScore < -4.0) {
                        signal = "CRITICAL_REVIEW";
                        justification = String.format("Critical Review: Z-Score is extremely low (Z=%.2f). Halting buys.", zScore);
                    } else {
                        boolean triggerBuy = true;
                        String condNote = "";
                        if ("TACTICAL".equals(bucket)) {
                            if (zScore < -1.5 && hmmBearProb < 0.6) {
                                condNote = String.format("Tactical cheapness trigger met (Z=%.2f, HMM Bear=%.2f)", zScore, hmmBearProb);
                            } else if (hurstExponent > 0.55 && zScore < 0 && hmmBearProb < 0.6) {
                                condNote = String.format("Tactical momentum/trend trigger met (H=%.3f, Z=%.2f, HMM Bear=%.2f)", hurstExponent, zScore, hmmBearProb);
                            } else {
                                triggerBuy = false;
                                justification = String.format("Hold (Tactical): Underweight by %.2f%%, but tactical buy triggers (Z-Score < -1.5 or positive trend with H > 0.55) under HMM Bear < 0.6 gating are not met (Z=%.2f, H=%.3f, HMM Bear=%.2f).", Math.abs(drift), zScore, hurstExponent, hmmBearProb);
                            }
                        } else if ("SATELLITE".equals(bucket)) {
                            if (zScore < -1.0) {
                                condNote = String.format("Satellite cheapness trigger met (Z=%.2f)", zScore);
                            } else {
                                triggerBuy = false;
                                justification = String.format("Hold (Satellite): Underweight by %.2f%%, but cheapness filter Z-Score < -1.0 is not met (Z=%.2f).", Math.abs(drift), zScore);
                            }
                        } else {
                            condNote = String.format("Core allocation underweight by %.2f%%", Math.abs(drift));
                        }

                        if (triggerBuy && currentNav.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal underweightPct = BigDecimal.valueOf(Math.abs(drift));
                            BigDecimal deficitValue = underweightPct.divide(BigDecimal.valueOf(100.0), 6, RoundingMode.HALF_UP)
                                .multiply(req.getTotalPortfolioValue());
                            unitsToTransact = deficitValue.divide(currentNav, 4, RoundingMode.HALF_UP);
                            signal = "BUY";
                            justification = String.format("Buy (%s): Underweight by %.2f%% (exceeds %.1f%% band). %s.", bucket, Math.abs(drift), threshold, condNote);
                        }
                    }
                } else if (drift > 0) {
                    // Overweight (SELL)
                    boolean triggerSell = false;
                    String condNote = "";

                    if (drift >= 2 * threshold) {
                        triggerSell = true;
                        condNote = String.format("Hard Sell Override: Drift (%+.2f%%) exceeds 2x threshold (%.1f%%)", drift, threshold);
                    } else if (hurstExponent > 0.55) {
                        signal = "HOLD";
                        unitsToTransact = BigDecimal.ZERO;
                        justification = String.format("Hold (Wave Rider): Target is overweight by %.2f%% but Hurst Exponent indicates a strong upward trend (H=%.3f). Letting profits run.", drift, hurstExponent);
                    } else {
                        if ("TACTICAL".equals(bucket)) {
                            if (zScore > 1.5) {
                                triggerSell = true;
                                condNote = String.format("Tactical overheated trigger met (Z=%.2f)", zScore);
                            } else if (hurstExponent < 0.45) {
                                triggerSell = true;
                                condNote = String.format("Tactical trend breakdown trigger met (H=%.3f)", hurstExponent);
                            } else {
                                justification = String.format("Hold (Tactical): Overweight by %.2f%%, but tactical sell triggers (Z-Score > 1.5 or trend breakdown with H < 0.45) are not met (Z=%.2f, H=%.3f).", drift, zScore, hurstExponent);
                            }
                        } else if ("SATELLITE".equals(bucket)) {
                            if (zScore > 1.5) {
                                triggerSell = true;
                                condNote = String.format("Satellite overheated trigger met (Z=%.2f)", zScore);
                            } else {
                                justification = String.format("Hold (Satellite): Overweight by %.2f%%, but sell trigger Z-Score > 1.5 is not met (Z=%.2f).", drift, zScore);
                            }
                        } else {
                            if (zScore > 2.0) {
                                triggerSell = true;
                                condNote = String.format("Core overheated trigger met (Z=%.2f)", zScore);
                            } else {
                                justification = String.format("Hold (Core): Overweight by %.2f%%, but sell trigger Z-Score > 2.0 is not met (Z=%.2f).", drift, zScore);
                            }
                        }
                    }

                    if (triggerSell) {
                        if (currentNav.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal overweightPct = BigDecimal.valueOf(drift);
                            BigDecimal overweightValue = overweightPct.divide(BigDecimal.valueOf(100.0), 6, RoundingMode.HALF_UP)
                                .multiply(req.getTotalPortfolioValue());
                            BigDecimal desiredUnitsToSell = overweightValue.divide(currentNav, 4, RoundingMode.HALF_UP);

                            List<TaxLot> openLots = java.util.Collections.emptyList();
                            if (amfiCode != null && !amfiCode.isEmpty()) {
                                try {
                                    openLots = taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan("OPEN", amfiCode, req.getPan());
                                } catch (Exception e) {
                                    log.warn("Failed to query open lots for scheme amfi {}: {}", amfiCode, e.getMessage());
                                }
                            }
                            
                            BigDecimal ltcgUnitsAvailable = BigDecimal.ZERO;
                            BigDecimal totalLtcgCost = BigDecimal.ZERO;

                            for (TaxLot lot : openLots) {
                                long daysHeld = ChronoUnit.DAYS.between(lot.getBuyDate(), currentDate);
                                if (daysHeld > 365) {
                                    BigDecimal remUnits = lot.getRemainingUnits() != null ? lot.getRemainingUnits() : BigDecimal.ZERO;
                                    BigDecimal costBasis = lot.getCostBasisPerUnit() != null ? lot.getCostBasisPerUnit() : BigDecimal.ZERO;
                                    ltcgUnitsAvailable = ltcgUnitsAvailable.add(remUnits);
                                    totalLtcgCost = totalLtcgCost.add(remUnits.multiply(costBasis));
                                }
                            }

                            BigDecimal avgLtcgCostBasis = BigDecimal.ZERO;
                            if (ltcgUnitsAvailable.compareTo(BigDecimal.ZERO) > 0) {
                                avgLtcgCostBasis = totalLtcgCost.divide(ltcgUnitsAvailable, 6, RoundingMode.HALF_UP);
                            }

                            BigDecimal gainPerUnit = currentNav.subtract(avgLtcgCostBasis);
                            BigDecimal unitsToSell = desiredUnitsToSell.min(ltcgUnitsAvailable);

                            if (unitsToSell.compareTo(BigDecimal.ZERO) <= 0) {
                                signal = "HOLD";
                                unitsToTransact = BigDecimal.ZERO;
                                justification = String.format("Hold (%s): Overweight by %.2f%%, but no long-term (LTCG) units are available to sell (preserving short-term lots).", bucket, drift);
                            } else {
                                if (gainPerUnit.compareTo(BigDecimal.ZERO) > 0) {
                                    BigDecimal estimatedGain = gainPerUnit.multiply(unitsToSell);
                                    if (estimatedGain.compareTo(headroomRemaining) <= 0) {
                                        signal = "SELL";
                                        unitsToTransact = unitsToSell;
                                        justification = String.format("Sell (%s): Target is overweight by %.2f%%. %s. Est. LTCG: ₹%,.0f fits within remaining tax headroom (₹%,.0f).", bucket, drift, condNote, estimatedGain, headroomRemaining);
                                        headroomRemaining = headroomRemaining.subtract(estimatedGain);
                                    } else {
                                        BigDecimal allowedUnits = headroomRemaining.divide(gainPerUnit, 4, RoundingMode.HALF_UP);
                                        BigDecimal finalUnits = allowedUnits.min(ltcgUnitsAvailable);
                                        if (finalUnits.compareTo(BigDecimal.ZERO) > 0) {
                                            signal = "SELL";
                                            unitsToTransact = finalUnits;
                                            justification = String.format("Sell (Capped %s): Target is overweight by %.2f%%. %s. Capped to fit remaining LTCG tax headroom (₹%,.0f).", bucket, drift, condNote, headroomRemaining);
                                            headroomRemaining = BigDecimal.ZERO;
                                        } else {
                                            signal = "HOLD";
                                            unitsToTransact = BigDecimal.ZERO;
                                            justification = String.format("Hold (%s): Overweight by %.2f%%, but remaining tax headroom is ₹%,.0f (too low to rebalance long-term units).", bucket, drift, headroomRemaining);
                                        }
                                    }
                                } else {
                                    signal = "SELL";
                                    unitsToTransact = unitsToSell;
                                    justification = String.format("Sell (%s): Target is overweight by %.2f%%. %s. No capital gain generated (selling at or below cost basis).", bucket, drift, condNote);
                                }
                            }
                        }
                    }
                }
            }

            actions.add(RebalanceActionDTO.builder()
                .schemeName(holding.getSchemeName() != null ? holding.getSchemeName() : target.schemeName())
                .amfiCode(amfiCode)
                .isin(holding.getIsin() != null ? holding.getIsin() : target.isin())
                .signal(signal)
                .unitsToTransact(unitsToTransact.setScale(4, RoundingMode.HALF_UP))
                .amount(unitsToTransact.multiply(currentNav).setScale(2, RoundingMode.HALF_UP))
                .justification(justification)
                .zScore(BigDecimal.valueOf(zScore).setScale(2, RoundingMode.HALF_UP))
                .hurstExponent(BigDecimal.valueOf(hurstExponent).setScale(3, RoundingMode.HALF_UP))
                .build());
        }

        // --- STEP 2: Process Mandatory Exits and Clutter Positions Second ---
        for (AggregatedHolding holding : mandatoryOrClutterHoldings) {
            String isin = holding.getIsin();
            StrategyTarget target = targetMap.get(isin);
            String amfiCode = req.getAmfiMap().getOrDefault(isin, "");

            double actualPct = 0.0;
            if (req.getTotalPortfolioValue().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal holdingVal = holding.getCurrentValue() != null ? holding.getCurrentValue() : BigDecimal.ZERO;
                actualPct = holdingVal.divide(req.getTotalPortfolioValue(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100.0)).doubleValue();
            }

            boolean isMandatoryExit = false;
            boolean isClutter = false;

            if (target != null) {
                String status = target.status() != null ? target.status().toUpperCase() : "ACTIVE";
                String bucket = target.bucket() != null ? target.bucket().toUpperCase() : "CORE";
                if ("DROPPED".equals(status) || "EXIT".equals(status) || "DROPPED".equals(bucket) || "EXIT".equals(bucket)) {
                    isMandatoryExit = true;
                }
            } else {
                isMandatoryExit = true;
            }

            if (target != null && target.targetPortfolioPct() == 0 && !isMandatoryExit) {
                if (actualPct < 0.2) {
                    isClutter = true;
                }
            } else if (target == null) {
                if (actualPct < 0.2) {
                    isClutter = true;
                    isMandatoryExit = false;
                }
            }

            BigDecimal currentNav = BigDecimal.ZERO;
            if (amfiCode != null && !amfiCode.isEmpty()) {
                try {
                    var details = amfiService.getLatestSchemeDetails(amfiCode);
                    if (details != null && details.getNav() != null) {
                        currentNav = details.getNav();
                    }
                } catch (Exception e) {
                    log.warn("Failed to get NAV for mandatory/clutter exit fund amfi {}: {}", amfiCode, e.getMessage());
                }
            }
            if (currentNav.compareTo(BigDecimal.ZERO) <= 0) {
                currentNav = holding.getNav() != null ? holding.getNav() : BigDecimal.ZERO;
            }

            BigDecimal totalLtcgGain = BigDecimal.ZERO;
            BigDecimal totalStcgGain = BigDecimal.ZERO;
            BigDecimal unitsToSell = holding.getUnits() != null ? holding.getUnits() : BigDecimal.ZERO;

            if (unitsToSell.compareTo(BigDecimal.ZERO) > 0 && amfiCode != null && !amfiCode.isEmpty()) {
                try {
                    List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan("OPEN", amfiCode, req.getPan());
                    for (TaxLot lot : openLots) {
                        long daysHeld = ChronoUnit.DAYS.between(lot.getBuyDate(), currentDate);
                        BigDecimal remUnits = lot.getRemainingUnits() != null ? lot.getRemainingUnits() : BigDecimal.ZERO;
                        BigDecimal costBasis = lot.getCostBasisPerUnit() != null ? lot.getCostBasisPerUnit() : BigDecimal.ZERO;
                        BigDecimal gainPerUnit = currentNav.subtract(costBasis);
                        BigDecimal gain = gainPerUnit.multiply(remUnits);

                        if (gain.compareTo(BigDecimal.ZERO) > 0) {
                            if (daysHeld > 365) {
                                totalLtcgGain = totalLtcgGain.add(gain);
                            } else {
                                totalStcgGain = totalStcgGain.add(gain);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to query open lots for exit scheme amfi {}: {}", amfiCode, e.getMessage());
                }
            }

            // Deduct mandatory exit LTCG from leftover headroom
            headroomRemaining = headroomRemaining.subtract(totalLtcgGain);
            if (headroomRemaining.compareTo(BigDecimal.ZERO) < 0) {
                headroomRemaining = BigDecimal.ZERO;
            }

            String signal = isClutter ? "SELL" : "EXIT";
            String reason = isClutter 
                ? String.format("Sell (Clutter): Small position (< 0.2%% of portfolio value) identified as portfolio clutter. Liquidating entire position. Est. LTCG: ₹%,.0f, STCG: ₹%,.0f.", totalLtcgGain, totalStcgGain)
                : String.format("Exit (Mandatory): Fund is marked as DROPPED/EXIT. Liquidating entire position. Est. LTCG: ₹%,.0f, STCG: ₹%,.0f.", totalLtcgGain, totalStcgGain);

            actions.add(RebalanceActionDTO.builder()
                .schemeName(holding.getSchemeName() != null ? holding.getSchemeName() : (target != null ? target.schemeName() : "Unknown Fund"))
                .amfiCode(amfiCode)
                .isin(isin)
                .signal(signal)
                .unitsToTransact(unitsToSell.setScale(4, RoundingMode.HALF_UP))
                .amount(unitsToSell.multiply(currentNav).setScale(2, RoundingMode.HALF_UP))
                .justification(reason)
                .zScore(BigDecimal.ZERO)
                .hurstExponent(BigDecimal.valueOf(0.5))
                .build());
        }

        return actions;
    }

    private static class ActiveEvaluationItem {
        final AggregatedHolding holding;
        final StrategyTarget target;
        ActiveEvaluationItem(AggregatedHolding holding, StrategyTarget target) {
            this.holding = holding;
            this.target = target;
        }
    }

    /**
     * Backwards-compatibility bridge for computeSignals returning List of TacticalSignals.
     */
    public List<TacticalSignal> computeSignals(RebalanceRequest req) {
        List<RebalanceActionDTO> actions = generateSignals(req);
        List<TacticalSignal> signals = new ArrayList<>();

        for (RebalanceActionDTO action : actions) {
            String amfiCode = action.getAmfiCode();
            MarketMetrics metrics = req.getMetrics().getOrDefault(amfiCode, 
                MarketMetrics.fromLegacy(50, 0, 0, 0, 0, 0.5, 0, 0, java.time.LocalDate.of(1970, 1, 1)));

            double actualPct = 0.0;
            double targetPct = 0.0;

            StrategyTarget target = req.getTargets().stream()
                .filter(t -> t.isin().equals(action.getIsin()))
                .findFirst().orElse(null);
            
            AggregatedHolding holding = req.getHoldings().stream()
                .filter(h -> h.getIsin().equals(action.getIsin()))
                .findFirst().orElse(null);

            if (target != null) {
                targetPct = target.targetPortfolioPct();
            }
            if (holding != null && req.getTotalPortfolioValue().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal holdingVal = holding.getCurrentValue() != null ? holding.getCurrentValue() : BigDecimal.ZERO;
                actualPct = holdingVal.divide(req.getTotalPortfolioValue(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100.0)).doubleValue();
            }

            var details = amfiService.getLatestSchemeDetails(amfiCode);
            BigDecimal currentNav = (details != null && details.getNav() != null) ? details.getNav() : BigDecimal.ZERO;
            if (currentNav == null || currentNav.compareTo(BigDecimal.ZERO) <= 0) {
                currentNav = holding != null && holding.getNav() != null ? holding.getNav() : BigDecimal.ZERO;
            }
            double amount = action.getUnitsToTransact().multiply(currentNav).doubleValue();

            String signalStr = action.getSignal();
            SignalType signalType = SignalType.HOLD;
            if ("BUY".equals(signalStr)) signalType = SignalType.BUY;
            else if ("SELL".equals(signalStr)) signalType = SignalType.SELL;
            else if ("EXIT".equals(signalStr)) signalType = SignalType.EXIT;
            else if ("CRITICAL_REVIEW".equals(signalStr)) signalType = SignalType.WATCH;

            String status = "ACTIVE";
            if (target != null) status = target.status();

            signals.add(TacticalSignal.builder()
                .schemeName(action.getSchemeName())
                .amfiCode(action.getAmfiCode())
                .action(signalType)
                .amount(String.format(java.util.Locale.US, "%.2f", amount))
                .plannedPercentage(targetPct)
                .actualPercentage(actualPct)
                .justifications(List.of(action.getJustification()))
                .fundStatus(FundStatus.fromString(status))
                .convictionScore(metrics.convictionScore())
                .sortinoRatio(metrics.sortinoRatio())
                .maxDrawdown(metrics.maxDrawdown())
                .navPercentile1yr(metrics.navPercentile1yr())
                .navPercentile3yr(metrics.navPercentile3yr())
                .drawdownFromAth(metrics.drawdownFromAth())
                .returnZScore(action.getZScore() != null ? action.getZScore().doubleValue() : metrics.returnZScore())
                .hurstExponent(action.getHurstExponent() != null ? action.getHurstExponent().doubleValue() : metrics.hurstExponent())
                .winRate(metrics.winRate())
                .cvar5(metrics.cvar5())
                .lastBuyDate(metrics.lastBuyDate())
                .yieldScore(metrics.yieldScore())
                .riskScore(metrics.riskScore())
                .valueScore(metrics.valueScore())
                .painScore(metrics.painScore())
                .regimeScore(metrics.regimeScore())
                .frictionScore(metrics.frictionScore())
                .expenseScore(metrics.expenseScore())
                .expenseRatio(metrics.expenseRatio())
                .aumCr(metrics.aumCr())
                .ouHalfLife(metrics.ouHalfLife())
                .ouValid(metrics.ouValid())
                .build());
        }

        return signals;
    }
}
