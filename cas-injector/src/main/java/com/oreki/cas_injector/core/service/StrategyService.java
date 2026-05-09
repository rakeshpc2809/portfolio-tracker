package com.oreki.cas_injector.core.service;

import com.oreki.cas_injector.core.event.StrategyTargetChangedEvent;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.repository.StrategyTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.context.ApplicationEventPublisher;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final ApplicationEventPublisher eventPublisher;
    private final StrategyTargetRepository strategyRepository;
    private final SchemeRepository schemeRepository;

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

    public List<com.oreki.cas_injector.rebalancing.dto.StrategyTarget> fetchLatestStrategy() {
        return strategyRepository.findAll().stream().map(t -> {
            Scheme s = schemeRepository.findFirstByAmfiCode(t.getAmfiCode()).orElse(null);
            String isin = s != null ? s.getIsin() : "";
            String name = s != null ? s.getName() : "Unknown Fund " + t.getAmfiCode();
            double pct = t.getTargetAllocationPct() != null ? t.getTargetAllocationPct().doubleValue() : 0.0;
            // Default SIP pct to target pct, status to ACTIVE
            return new com.oreki.cas_injector.rebalancing.dto.StrategyTarget(
                isin, name, pct, pct, "ACTIVE", t.getStrategyType()
            );
        }).collect(Collectors.toList());
    }
}
