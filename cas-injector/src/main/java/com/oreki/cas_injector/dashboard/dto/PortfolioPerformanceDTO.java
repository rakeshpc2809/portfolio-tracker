package com.oreki.cas_injector.dashboard.dto;

import java.util.List;

public record PortfolioPerformanceDTO(
    List<SnapshotPoint> history,
    List<BenchmarkPoint> niftyHistory,
    double totalReturn,
    double xirr,
    PeriodReturns periodReturns,
    PeriodReturns niftyReturns,
    double alphaPct,
    double totalGainRs,
    double sipContributionRs,
    double marketGainRs
) {}
