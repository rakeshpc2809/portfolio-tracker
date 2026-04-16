package com.oreki.cas_injector.dashboard.dto;

public record DroppedFundSummary(
    String  schemeName,
    String  amfiCode,
    double  currentValue,
    double  ltcgGains,
    double  stcgGains,
    int     daysToNextLtcg,
    double  taxIfExitNow,
    double  taxIfWaitForLtcg,
    double  taxSavingByWaiting,
    String  recommendedAction,   // "EXIT_NOW_TAX_FREE" | "WAIT_FOR_LTCG" | "EXIT_NOW" | "HOLD_WAVE_RIDER"
    String  exitDateSuggestion,  // ISO date string, when to exit
    String  reason
) {}
