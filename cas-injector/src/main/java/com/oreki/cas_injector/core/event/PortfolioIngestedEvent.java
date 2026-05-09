package com.oreki.cas_injector.core.event;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PortfolioIngestedEvent extends DomainEvent {
    private String investorName;
    private String pan;
    private int schemesCount;
}
