package com.oreki.cas_injector.core.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class CasIngestionCompletedEvent {
    private final String investorPan;
}
