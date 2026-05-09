package com.oreki.cas_injector.core.projection;

import com.oreki.cas_injector.core.event.StrategyTargetChangedEvent;
import com.oreki.cas_injector.core.model.StrategyTarget;
import com.oreki.cas_injector.core.repository.StrategyTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyProjector {

    private final StrategyTargetRepository strategyRepository;

    @Transactional
    @EventListener
    public void projectStrategyChange(StrategyTargetChangedEvent event) {
        log.info("Projecting Strategy Change for Aggregate: {}", event.getAggregateId());

        StrategyTarget target = strategyRepository.findByInvestorPanAndAmfiCode(
            event.getAggregateId().split(":")[0], // Extracted PAN
            event.getAmfiCode()
        ).orElseGet(() -> {
            StrategyTarget newTarget = new StrategyTarget();
            newTarget.setInvestorPan(event.getAggregateId().split(":")[0]);
            newTarget.setAmfiCode(event.getAmfiCode());
            return newTarget;
        });

        target.setTargetAllocationPct(event.getTargetAllocationPct());
        target.setStrategyType(event.getStrategyType());
        target.setSource(event.getSource());
        target.setLastUpdated(LocalDateTime.now());

        strategyRepository.save(target);
        log.info("Read model updated for strategy: {}", event.getAmfiCode());
    }
}
