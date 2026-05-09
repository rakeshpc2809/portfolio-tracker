package com.oreki.cas_injector.core.event;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class StrategyTargetChangedEvent extends DomainEvent {
    private String amfiCode;
    private BigDecimal targetAllocationPct;
    private String strategyType;
    private String source; // UI or GOOGLE_SHEETS
}
