package com.oreki.cas_injector.taxmanagement.dto;

public record TlhOpportunity(
    String schemeName,
    String amfiCode,
    double harvestableAmount, // The exact ₹ amount to sell
    double estimatedCapitalLoss, // The exact tax write-off you will generate
    String taxBucket, // "STCL" (Short Term Capital Loss) or "LTCL" (Long Term)
    String proxySchemeRecommendation // The correlated asset to buy immediately
) {}