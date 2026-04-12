package com.oreki.cas_injector.rebalancing.dto;

public record SipLineItem(
    String schemeName,
    String simpleName,
    String isin,
    String amfiCode,
    double amount,
    double sipPct,
    double targetPortfolioPct,  // ADD THIS for Bug 2
    String mode,           // core / strategy / satellite / rebalancer
    String deployFlag,     // DEPLOY / CAUTION_EXPENSIVE
    String note
) {}
