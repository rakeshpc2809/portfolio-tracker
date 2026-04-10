package com.oreki.cas_injector.rebalancing.dto;

import java.time.LocalDate;
import java.util.List;
import com.oreki.cas_injector.core.utils.SignalType;

public record TacticalSignal(
    String schemeName,
    String amfiCode,
    SignalType action,
    String amount,
    double plannedPercentage,
    double actualPercentage,
    double sipPercentage,
    String fundStatus,
    int    convictionScore,
    double sortinoRatio,
    double maxDrawdown,
    double navPercentile3yr,
    double drawdownFromAth,
    double returnZScore,
    LocalDate lastBuyDate,
    List<String> justifications,

    // ── NEW: structured "Why" payload for the Explanation Engine ─────────────
    ReasoningMetadata reasoningMetadata
) {}
