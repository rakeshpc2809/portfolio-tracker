package com.oreki.cas_injector.rebalancing.dto;

public record RebalancingTrade(
    String    sellFundName,
    String    sellAmfiCode,
    double    sellAmount,           // Graduated amount (post-tax net)
    double    estimatedSellTax,
    double    netProceeds,          // sellAmount - estimatedSellTax
    String    sellReason,           // Brief human explanation

    String    buyFundName,
    String    buyAmfiCode,
    double    buyAmount,            // Kelly-adjusted, capped by netProceeds
    String    buyReason,

    double    convictionDelta,      // buyFund.convictionScore - sellFund.convictionScore
    double    zScoreDelta,          // |buyFund.z| - |sellFund.z| (cheapness delta)
    String    tradeRationale        // "Rotating ₹X from overheated A → discounted B"
) {}
