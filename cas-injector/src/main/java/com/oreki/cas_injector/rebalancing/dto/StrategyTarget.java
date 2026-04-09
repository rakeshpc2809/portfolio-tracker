package com.oreki.cas_injector.rebalancing.dto;

public record StrategyTarget(
    String isin,                 
    String schemeName,           
    double targetPortfolioPct,   
    double sipPct,
    String status,
    String bucket
) {}