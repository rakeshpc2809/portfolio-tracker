package com.oreki.cas_injector.rebalancing.dto;

import java.time.LocalDate;
import java.util.List;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.core.utils.FundStatus;
import lombok.Builder;

@Builder
public record TacticalSignal(
    String schemeName,
    String simpleName,
    String amfiCode,
    SignalType action,
    String amount,
    double plannedPercentage,
    double actualPercentage,
    double sipPercentage,
    FundStatus fundStatus,
    int    convictionScore,
    double sortinoRatio,
    double maxDrawdown,
    double navPercentile1yr,
    double navPercentile3yr,
    double drawdownFromAth,
    double returnZScore,
    LocalDate lastBuyDate,
    List<String> justifications,

    // ── NEW: structured "Why" payload for the Explanation Engine ─────────────
    ReasoningMetadata reasoningMetadata,

    // ── Conviction Sub-scores (7-Factor) ──
    double yieldScore,
    double riskScore,
    double valueScore,
    double painScore,
    double regimeScore,
    double frictionScore,
    double expenseScore,

    // ── Metadata ──
    double expenseRatio,
    double aumCr,

    // ── OU FIELDS ──
    double ouHalfLife,
    boolean ouValid
) {}
