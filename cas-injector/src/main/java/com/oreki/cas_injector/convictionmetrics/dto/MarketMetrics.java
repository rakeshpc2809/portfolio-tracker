package com.oreki.cas_injector.convictionmetrics.dto;

public record MarketMetrics(
    int convictionScore,
    double sortinoRatio,
    double cvar5,
    double winRate,
    double maxDrawdown,
    double extra // Placeholder for future expansion
) {}