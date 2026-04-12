package com.oreki.cas_injector.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.oreki.cas_injector.backfill.repository.HistoricalNavRepository;
import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.core.model.Investor;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
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

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    public void testGetInvestorSummary_EmptyInvestor() {
        when(investorRepo.findByPanWithDetails(anyString())).thenReturn(Optional.empty());

        DashboardSummaryDTO summary = dashboardService.getInvestorSummary("NONEXISTENT");

        assertNotNull(summary);
        assertEquals("New Investor", summary.getInvestorName());
        assertEquals(0, summary.getSchemeBreakdown().size());
    }

    @Test
    public void testGetInvestorSummary_WithMetrics() {
        Investor investor = Investor.builder().pan("PAN123").name("Test Investor").folios(Set.of()).build();
        when(investorRepo.findByPanWithDetails("PAN123")).thenReturn(Optional.of(investor));
        
        Map<String, MarketMetrics> metricsMap = new HashMap<>();
        when(metricsRepo.fetchLiveMetricsMap("PAN123")).thenReturn(metricsMap);
        when(auditRepo.findAllBySellTransactionSchemeFolioInvestorPan("PAN123")).thenReturn(Collections.emptyList());

        DashboardSummaryDTO summary = dashboardService.getInvestorSummary("PAN123");

        assertNotNull(summary);
        assertEquals("Test Investor", summary.getInvestorName());
    }
}
