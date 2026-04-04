package com.oreki.cas_injector.rebalancing.dto;

import java.util.List;
public record TacticalSignal(
    String schemeName,
    String action,
    String amount,
    double plannedPercentage,
    double actualPercentage,
    double sipPercentage,
    String fundStatus,
    int confidenceScore,
    double sortinoRatio,    // 🚀 MUST BE HERE
    double maxDrawdown,     // 🚀 MUST BE HERE
    List<String> justifications

) {}