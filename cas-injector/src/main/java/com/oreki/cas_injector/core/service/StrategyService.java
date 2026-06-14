package com.oreki.cas_injector.core.service;

import com.oreki.cas_injector.core.event.StrategyTargetChangedEvent;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.repository.StrategyTargetRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.service.LotAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.context.ApplicationEventPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final ApplicationEventPublisher eventPublisher;
    private final StrategyTargetRepository strategyRepository;
    private final SchemeRepository schemeRepository;
    private final TaxLotRepository taxLotRepository;
    private final LotAggregationService lotAggregationService;

    public void updateTarget(String pan, String amfiCode, BigDecimal allocation, String type, String source) {
        log.info("Request to update strategy for fund {} to {}%", amfiCode, allocation);
        
        StrategyTargetChangedEvent event = StrategyTargetChangedEvent.builder()
                .aggregateId(pan + ":" + amfiCode)
                .amfiCode(amfiCode)
                .targetAllocationPct(allocation)
                .strategyType(type)
                .source(source)
                .version(System.currentTimeMillis())
                .build();

        eventPublisher.publishEvent(event);
    }

    public List<com.oreki.cas_injector.rebalancing.dto.StrategyTarget> fetchLatestStrategy(String pan) {
        List<com.oreki.cas_injector.core.model.StrategyTarget> storedTargets = strategyRepository.findByInvestorPan(pan);
        if (!storedTargets.isEmpty()) {
            return storedTargets.stream().map(t -> {
                Scheme s = schemeRepository.findFirstByAmfiCode(t.getAmfiCode()).orElse(null);
                String isin = s != null ? s.getIsin() : "";
                String name = s != null ? s.getName() : "Unknown Fund " + t.getAmfiCode();
                double pct = t.getTargetAllocationPct() != null ? t.getTargetAllocationPct().doubleValue() : 0.0;
                String stratType = t.getStrategyType() != null ? t.getStrategyType().toUpperCase() : "CORE";
                String status = ("DROPPED".equals(stratType) || "EXIT".equals(stratType)) ? stratType : "ACTIVE";
                return new com.oreki.cas_injector.rebalancing.dto.StrategyTarget(
                    isin, name, pct, pct, status, stratType
                );
            }).collect(Collectors.toList());
        }

        // Auto-bootstrap: derive proportional targets from current open lots for this specific investor
        log.info("📊 No strategy targets configured for PAN {} — auto-bootstrapping from current holdings.", pan);
        try {
            List<com.oreki.cas_injector.transactions.model.TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
            if (openLots.isEmpty()) return Collections.emptyList();

            List<AggregatedHolding> holdings = lotAggregationService.aggregate(openLots);
            BigDecimal totalValue = holdings.stream()
                .map(h -> h.getCurrentValue() != null ? h.getCurrentValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalValue.compareTo(BigDecimal.ZERO) <= 0) return Collections.emptyList();

            return holdings.stream()
                .filter(h -> h.getCurrentValue() != null && h.getCurrentValue().compareTo(BigDecimal.ZERO) > 0)
                .map(h -> {
                    Scheme s = schemeRepository.findByIsin(h.getIsin() != null ? h.getIsin() : "").orElse(null);
                    String isin = h.getIsin() != null ? h.getIsin() : "";
                    String name = h.getSchemeName() != null ? h.getSchemeName() : (s != null ? s.getName() : "Unknown Fund");
                    double pct = h.getCurrentValue().divide(totalValue, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100.0)).doubleValue();
                    pct = Math.round(pct * 100.0) / 100.0; // Round to 2dp
                    return new com.oreki.cas_injector.rebalancing.dto.StrategyTarget(
                        isin, name, pct, pct, "ACTIVE", "PROPORTIONAL"
                    );
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("⚠️ Auto-bootstrap of strategy targets failed for PAN {}: {}", pan, e.getMessage());
            return Collections.emptyList();
        }
    }
}

