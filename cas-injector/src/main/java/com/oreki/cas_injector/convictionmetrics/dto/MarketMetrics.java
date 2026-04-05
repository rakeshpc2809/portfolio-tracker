package com.oreki.cas_injector.convictionmetrics.dto;

import java.time.LocalDate;

public record MarketMetrics(
    int convictionScore,
    double sortinoRatio,
    double cvar5,
    double winRate,
    double maxDrawdown,
    double peRatio,
    double pbRatio,
    double zScore,
    double coveragePct,
    LocalDate lastBuyDate,
    String valuationStatus // 🚀 NEW: Human-readable status (CHEAP, EXPENSIVE, etc.)
) {}