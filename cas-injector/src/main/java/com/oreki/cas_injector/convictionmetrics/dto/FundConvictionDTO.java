package com.oreki.cas_injector.convictionmetrics.dto;

public record FundConvictionDTO(
    String schemeCode,
    String schemeName,
    double currentAllocation, // From V1
    int convictionScore,      // 0-100
    double sortinoRatio,      // V2 Metric
    double maxDrawdown,       // V2 Metric
    double cvar5,             // V2 Metric (Tail Risk)
    double winRate,           // V2 Metric (Consistency)
    String status             // "STRONG BUY", "HOLD", "EXIT"
) {}
