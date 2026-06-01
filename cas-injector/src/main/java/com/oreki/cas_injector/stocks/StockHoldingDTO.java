package com.oreki.cas_injector.stocks;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHoldingDTO {
    private String ticker;
    private String isin;
    private String companyName;
    private String exchange;
    private String sector;
    private BigDecimal quantity;
    private BigDecimal avgCostPerShare;
    private BigDecimal currentPrice;
    private BigDecimal currentValue;
    private BigDecimal investedAmount;
    private BigDecimal unrealisedPnl;
    private BigDecimal unrealisedPnlPct;
    private BigDecimal realisedGainFy;
    private BigDecimal unrealisedLtcg;
    private BigDecimal unrealisedStcg;
    private BigDecimal ltcgTaxEstimate;
    private BigDecimal stcgTaxEstimate;
    private int daysToNextLtcg;
    private BigDecimal xirr;
    private BigDecimal dayChangePct;
    private String action;
    private BigDecimal convictionScore;
}
