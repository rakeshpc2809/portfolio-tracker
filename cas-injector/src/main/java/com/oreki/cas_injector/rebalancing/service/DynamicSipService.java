package com.oreki.cas_injector.rebalancing.service;

import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.utils.FundStatus;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.SmartSipAllocation;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DynamicSipService {

    private final RebalanceOrchestrator orchestrator;
    private final SchemeRepository schemeRepository;

    public List<SmartSipAllocation> calculateSipSplit(String pan, BigDecimal totalSipBudget) {
        log.info("Calculating Dynamic SIP split for PAN: {}, Budget: ₹{}", pan, totalSipBudget);

        if (totalSipBudget == null || totalSipBudget.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }

        // 1. Fetch tactical signals
        List<TacticalSignal> allSignals = orchestrator.getTacticalSignals(pan);
        if (allSignals == null || allSignals.isEmpty()) {
            log.warn("No tactical signals returned for PAN: {}", pan);
            return List.of();
        }

        // 2. Filter out dropped or exit status funds, and funds with active SELL signal
        List<TacticalSignal> activeSignals = allSignals.stream()
            .filter(s -> {
                boolean isDroppedOrExit = s.fundStatus() == FundStatus.DROPPED || s.fundStatus() == FundStatus.EXIT;
                boolean isSellOrExitSignal = s.action() == SignalType.SELL || s.action() == SignalType.EXIT;
                return !isDroppedOrExit && !isSellOrExitSignal;
            })
            .collect(Collectors.toList());

        if (activeSignals.isEmpty()) {
            log.warn("No active target funds remaining after filtering for PAN: {}", pan);
            return List.of();
        }

        // 3. Compute weight deficits and apply conviction multiplier
        List<AllocationDraft> drafts = new ArrayList<>();
        double sumOfAdjustedDeficits = 0.0;

        for (TacticalSignal signal : activeSignals) {
            double plannedPct = signal.plannedPercentage();
            double actualPct = signal.actualPercentage();
            
            // Deficit bounded at 0
            double deficit = Math.max(0.0, plannedPct - actualPct);

            // Conviction Multiplier
            double zScore = signal.returnZScore();
            double multiplier = 1.0;
            if (zScore < -1.0) {
                multiplier = 1.5;
            } else if (zScore > 1.0) {
                multiplier = 0.5;
            }

            double adjustedDeficit = deficit * multiplier;
            sumOfAdjustedDeficits += adjustedDeficit;

            drafts.add(new AllocationDraft(signal, deficit, adjustedDeficit));
        }

        List<SmartSipAllocation> result = new ArrayList<>();

        if (sumOfAdjustedDeficits > 0.0) {
            // Normalize adjusted deficits and allocate budget
            for (AllocationDraft draft : drafts) {
                double allocatedPct = draft.adjustedDeficit / sumOfAdjustedDeficits;
                BigDecimal allocatedAmt = totalSipBudget.multiply(BigDecimal.valueOf(allocatedPct))
                    .setScale(2, RoundingMode.HALF_UP);

                String isin = schemeRepository.findFirstByAmfiCode(draft.signal.amfiCode())
                    .map(Scheme::getIsin)
                    .orElse("");

                result.add(new SmartSipAllocation(
                    draft.signal.schemeName(),
                    draft.signal.amfiCode(),
                    isin,
                    allocatedAmt,
                    allocatedPct * 100.0,
                    draft.signal.returnZScore(),
                    draft.signal.plannedPercentage(),
                    draft.signal.actualPercentage(),
                    draft.deficit,
                    draft.adjustedDeficit
                ));
            }
        } else {
            // Fallback Logic: Allocate proportionally to baseline target percentages
            log.info("Sum of adjusted weight deficits is 0 for active target funds. Falling back to baseline target percentages.");
            
            double sumOfTargets = activeSignals.stream()
                .mapToDouble(TacticalSignal::plannedPercentage)
                .sum();

            if (sumOfTargets > 0.0) {
                for (AllocationDraft draft : drafts) {
                    double allocatedPct = draft.signal.plannedPercentage() / sumOfTargets;
                    BigDecimal allocatedAmt = totalSipBudget.multiply(BigDecimal.valueOf(allocatedPct))
                        .setScale(2, RoundingMode.HALF_UP);

                    String isin = schemeRepository.findFirstByAmfiCode(draft.signal.amfiCode())
                        .map(Scheme::getIsin)
                        .orElse("");

                    result.add(new SmartSipAllocation(
                        draft.signal.schemeName(),
                        draft.signal.amfiCode(),
                        isin,
                        allocatedAmt,
                        allocatedPct * 100.0,
                        draft.signal.returnZScore(),
                        draft.signal.plannedPercentage(),
                        draft.signal.actualPercentage(),
                        0.0,
                        0.0
                    ));
                }
            } else {
                // If baseline targets are also all 0, distribute equally
                log.info("Sum of target percentages is also 0. Distributing budget equally among active funds.");
                double allocatedPct = 1.0 / activeSignals.size();
                BigDecimal allocatedAmt = totalSipBudget.multiply(BigDecimal.valueOf(allocatedPct))
                    .setScale(2, RoundingMode.HALF_UP);

                for (AllocationDraft draft : drafts) {
                    String isin = schemeRepository.findFirstByAmfiCode(draft.signal.amfiCode())
                        .map(Scheme::getIsin)
                        .orElse("");

                    result.add(new SmartSipAllocation(
                        draft.signal.schemeName(),
                        draft.signal.amfiCode(),
                        isin,
                        allocatedAmt,
                        allocatedPct * 100.0,
                        draft.signal.returnZScore(),
                        draft.signal.plannedPercentage(),
                        draft.signal.actualPercentage(),
                        0.0,
                        0.0
                    ));
                }
            }
        }

        return result;
    }

    private static class AllocationDraft {
        final TacticalSignal signal;
        final double deficit;
        final double adjustedDeficit;

        AllocationDraft(TacticalSignal signal, double deficit, double adjustedDeficit) {
            this.signal = signal;
            this.deficit = deficit;
            this.adjustedDeficit = adjustedDeficit;
        }
    }
}
