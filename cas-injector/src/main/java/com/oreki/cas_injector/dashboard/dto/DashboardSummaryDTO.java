package com.oreki.cas_injector.dashboard.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

import com.oreki.cas_injector.core.dto.SchemePerformanceDTO;

@Data
@Builder
public class DashboardSummaryDTO {
    private String investorName;
    private int totalFolios;
    private int totalSchemes;
    private long totalTransactions;
    private BigDecimal totalInvestedAmount; // Sum of BUYs
    private BigDecimal totalRealizedGain;   // Sum of Audit entries
    private long openTaxLots;               // Count of OPEN lots
    private BigDecimal totalLTCG; // Long Term Capital Gain
    private BigDecimal totalSTCG; // Short Term Capital Gain
    private BigDecimal currentInvestedAmount; // Sum of BUYs
    private BigDecimal currentValueAmount; // Sum of BUYs
    private BigDecimal totalUnrealizedGain; // Sum of BUYs
    private String overallReturn;
    private String overallXirr;
    private List<SchemePerformanceDTO> schemeBreakdown;

}