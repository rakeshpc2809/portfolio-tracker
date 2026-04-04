package com.oreki.cas_injector.core.dto;

import java.math.BigDecimal;

import com.oreki.cas_injector.core.utils.FundStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor // Added for better compatibility with some JSON mappers
public class SchemePerformanceDTO {
    private String schemeName;
    private String isin;
    
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

    private String category;

    private String bucket;

    private String benchmarkIndex;
}