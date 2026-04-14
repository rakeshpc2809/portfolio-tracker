package com.oreki.cas_injector.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.oreki.cas_injector.backfill.repository.HistoricalNavRepository;
import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.dashboard.model.PortfolioSummary;
import com.oreki.cas_injector.transactions.repository.CapitalGainAuditRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
public class DashboardServiceTest {

    @Mock private InvestorRepository investorRepo;
    @Mock private TaxLotRepository taxLotRepo;
    @Mock private CapitalGainAuditRepository auditRepo;
    @Mock private TransactionRepository txnRepo;
    @Mock private NavService navService;
    @Mock private HistoricalNavRepository historicalNavRepo;
    @Mock private BenchmarkService benchmarkService;
    @Mock private ConvictionMetricsRepository metricsRepo;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    @SuppressWarnings("unchecked")
    public void testGetInvestorSummary_EmptyInvestor() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
            .thenReturn(Collections.emptyList());

        DashboardSummaryDTO summary = dashboardService.getInvestorSummary("NONEXISTENT");

        assertNotNull(summary);
        assertEquals("New Investor", summary.getInvestorName());
        assertEquals(0, summary.getSchemeBreakdown().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetInvestorSummary_WithData() {
        PortfolioSummary mockSummary = new PortfolioSummary();
        mockSummary.setInvestorName("Test Investor");
        mockSummary.setInvestorPan("PAN123");
        mockSummary.setSchemeId(1L);
        mockSummary.setAmfiCode("12345");
        mockSummary.setTransactionDate(java.time.LocalDate.now());
        mockSummary.setTransactionType("BUY");
        mockSummary.setAmount(new java.math.BigDecimal("1000"));
        mockSummary.setUnits(new java.math.BigDecimal("10"));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("PAN123")))
            .thenReturn(List.of(mockSummary));
        
        when(metricsRepo.fetchLiveMetricsMap("PAN123")).thenReturn(Collections.emptyMap());
        when(auditRepo.findAllBySellTransactionSchemeFolioInvestorPan("PAN123")).thenReturn(Collections.emptyList());
        when(taxLotRepo.findByStatusAndSchemeFolioInvestorPan("OPEN", "PAN123")).thenReturn(Collections.emptyList());
        when(historicalNavRepo.findByAmfiCodeInAndNavDateIn(any(), any())).thenReturn(Collections.emptyList());

        DashboardSummaryDTO summary = dashboardService.getInvestorSummary("PAN123");

        assertNotNull(summary);
        assertEquals("Test Investor", summary.getInvestorName());
    }
}
