package com.oreki.cas_injector.dashboard.dto;

public record PeriodReturns(
    double oneMonth, double threeMonth, double sixMonth,
    double oneYear, double threeYear, double itd
) {}
