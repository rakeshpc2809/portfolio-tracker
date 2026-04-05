package com.oreki.cas_injector.convictionmetrics.dto;

import java.time.LocalDate;

public record MarketMetrics(
    int convictionScore,
    double sortinoRatio,
    double cvar5,
    double winRate,
    double maxDrawdown,
    double navPercentile3yr,
    double drawdownFromAth,
    double returnZScore,
    LocalDate lastBuyDate
) {}