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
    private BigDecimal totalInvestedAmount; 
    private BigDecimal totalRealizedGain;   
    private long openTaxLots;               
    private BigDecimal totalLTCG; 
    private BigDecimal totalSTCG; 
    private BigDecimal currentInvestedAmount; 
    private BigDecimal currentValueAmount; 
    private BigDecimal totalUnrealizedGain; 
    private String overallReturn;
    private String overallXirr;
    private double fyLtcgAlreadyRealized;
    private double taxSlab;
    private List<SchemePerformanceDTO> schemeBreakdown;
    private UnifiedTacticalPayload tacticalPayload; // 🚀 ADD THIS
}
