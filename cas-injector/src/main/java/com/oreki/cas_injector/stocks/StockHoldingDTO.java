package com.oreki.cas_injector.stocks;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private double quantity;
    private double avgCostPerShare;
    private double currentPrice;
    private double currentValue;
    private double investedAmount;
    private double unrealisedPnl;
    private double unrealisedPnlPct;
    private double realisedGainFy;
    private double unrealisedLtcg;
    private double unrealisedStcg;
    private double ltcgTaxEstimate;
    private double stcgTaxEstimate;
    private int daysToNextLtcg;
    private double xirr;
    private double dayChangePct;
    private String action;
    private double convictionScore;
}
