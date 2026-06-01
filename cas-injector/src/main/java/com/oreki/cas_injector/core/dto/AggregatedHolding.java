package com.oreki.cas_injector.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AggregatedHolding {
    private String schemeName;      // 1
    private BigDecimal units;           // 2
    private BigDecimal currentValue;    // 3
    private BigDecimal investedAmount;  // 4
    private BigDecimal ltcgValue;       // 5
    private BigDecimal ltcgAmount;      // 6
    private BigDecimal stcgValue;       // 7
    private BigDecimal stcgAmount;      // 8
    private int daysToNextLtcg;     // 9
    private int oldestAgeDays;      // 10
    private String assetCategory;   // 11
    private String status;          // 12
    private String isin;            // 13
    private BigDecimal stcgTaxEstimate; // 14
    private BigDecimal nav;             // 15
}
