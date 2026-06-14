package com.oreki.cas_injector.rebalancing.dto;

import java.math.BigDecimal;

public record SmartSipAllocation(
    String schemeName,
    String amfiCode,
    String isin,
    BigDecimal allocatedAmount,
    double allocatedPercentage,
    double zScore,
    double targetPercentage,
    double currentPercentage,
    double weightDeficit,
    double adjustedDeficit
) {}
