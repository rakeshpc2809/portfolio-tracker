package com.oreki.cas_injector.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AggregatedHolding {
    private String schemeName;      // 1
    private double units;           // 2
    private double currentValue;    // 3
    private double investedAmount;  // 4
    private double ltcgValue;       // 5 (The sellable cash)
    private double ltcgAmount;      // 6 (The profit in LTCG)
    private double stcgValue;       // 7 (The locked cash)
    private double stcgAmount;      // 8 (The profit in STCG)
    private int daysToNextLtcg;     // 9 (Fixed from 0)
    private int oldestAgeDays;      // 10
    private String assetCategory;   // 11
    private String status;          // 12
    private String isin;            // 13 (Added for your Strategy check)
    private Double stcgTaxEstimate; // 14 (Added for Phase 1E)
    private double nav;             // 15 (Added to preserve precision instead of dividing currentValue/units)
}
