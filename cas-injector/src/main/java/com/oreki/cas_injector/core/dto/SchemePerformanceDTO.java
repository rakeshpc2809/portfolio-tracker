package com.oreki.cas_injector.core.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.oreki.cas_injector.core.utils.FundStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonInclude;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor // Added for better compatibility with some JSON mappers
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SchemePerformanceDTO {
    private String schemeName;
    private String isin;
    private String amfiCode;
    
    // 1. Total Invested (Gross historical Buy amounts)
    private BigDecimal totalInvested; 
    
    // 2. Sold Amount (The "Cost Basis" portion of units you've exited)
    private BigDecimal soldAmountCost; 
    
    // 3. Current Invested (Total Invested - Sold Amount Cost)
    private BigDecimal currentInvested; 

    // 4. Current Value (Units Held * Current NAV)
    private BigDecimal currentValue;

    // 5. Realized Gain (Profit/Loss already "booked" in the bank)
    private BigDecimal realizedGain;

    private BigDecimal unrealizedGain;

    private long transactionCount;

    private FundStatus status; // ACTIVE or REDEEMED

    private String xirr;
    private Double benchmarkXirr;

    private String category;

    private String bucket;

    private String amc;

    private String benchmarkIndex;
    private boolean isSlabRateFund;
    private BigDecimal slabRateGain;

    // Conviction & Risk Metrics (Updated for NAV Signals)
    private Integer convictionScore;
    private Double sortinoRatio;
    private Double maxDrawdown;
    private Double cvar5; // Added for Bug 1
    private Double winRate; // NEW
    private double allocationPercentage;
    private Double plannedPercentage;
    private Double navPercentile1yr;
    private Double navPercentile3yr;
    private Double drawdownFromAth;
    private Double returnZScore;

    // Tax Efficiency Metrics (Added for Bug 4)
    private BigDecimal ltcgUnrealizedGain;
    private BigDecimal stcgUnrealizedGain;
    private int daysToNextLtcg;    // days until oldest STCG lot becomes LTCG-eligible
    private BigDecimal stcgValue;      // alias for stcgUnrealizedGain (keep for compat)
    private BigDecimal ltcgValue;      // alias for ltcgUnrealizedGain (keep for compat)

    // Conviction Sub-scores (Revised 7-Factor)
    private Double yieldScore;
    private Double riskScore;
    private BigDecimal valueScore;
    private Double painScore;
    private Double frictionScore;
    private Double regimeScore;
    private Double expenseScore;
    
    private String simpleName;

    // Advanced Quantitative Metrics (Task 2B/3C)
    private Double rollingZScore252;
    private Double hurstExponent;
    private Double volatilityTax;
    private String hurstRegime;
    private Double historicalRarityPct;
    private Double ouHalfLife;
    private Boolean ouValid;
    private String hmmState;
    private Double hmmBullProb;
    private Double hmmBearProb;

    // Tactical Signal Details
    private String signalType;
    private String action;
    private BigDecimal signalAmount;
    @Builder.Default
    private List<String> justifications = new java.util.ArrayList<>();
    private LocalDate lastBuyDate;
    private List<Integer> convictionHistory;
    
    // Attribution Fields
    private Double alpha;
    private Double betaMkt;
    private Double betaSmb;
    private Double betaHml;
    private Double rSquared;
}