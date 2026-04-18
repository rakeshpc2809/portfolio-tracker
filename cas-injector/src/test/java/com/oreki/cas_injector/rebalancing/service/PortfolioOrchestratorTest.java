package com.oreki.cas_injector.rebalancing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.convictionmetrics.service.ConvictionScoringService;
import com.oreki.cas_injector.core.GoogleSheetService;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.service.LotAggregationService;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.core.utils.FundStatus;
import com.oreki.cas_injector.dashboard.dto.UnifiedTacticalPayload;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.taxmanagement.service.TaxLossHarvestingService;
import com.oreki.cas_injector.taxmanagement.service.TaxSimulatorService;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;
import com.oreki.cas_injector.backfill.service.NavService;

@ExtendWith(MockitoExtension.class)
public class PortfolioOrchestratorTest {

    @Mock private GoogleSheetService strategyService;
    @Mock private NavService amfiService;
    @Mock private TaxLotRepository taxLotRepository;
    @Mock private SchemeRepository schemeRepository;
    @Mock private TaxSimulatorService taxSimulator;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private LotAggregationService lotAggregationService;
    @Mock private ConvictionMetricsRepository metricsRepo;
    @Mock private ConvictionScoringService scoringService;
    @Mock private HierarchicalRiskParityService hrpService;
    @Mock private SystemicRiskMonitorService riskMonitor;
    @Mock private TaxLossHarvestingService tlhService;
    @Mock private TransactionRepository txnRepo;
    @Mock private RebalanceEngine rebalanceEngine;

    @InjectMocks
    private PortfolioOrchestrator orchestrator;

    @Test
    public void testGenerateUnifiedPayload() {
        String pan = "PAN123";
        when(taxLotRepository.findByStatusAndSchemeFolioInvestorPan(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(lotAggregationService.aggregate(anyList())).thenReturn(Collections.emptyList());
        when(metricsRepo.fetchLiveMetricsMap(anyString())).thenReturn(Collections.emptyMap());
        when(strategyService.fetchLatestStrategy()).thenReturn(List.of(
            new StrategyTarget("ISIN1", "Fund 1", 10.0, 5.0, "ACTIVE", "CORE")
        ));
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any(), any())).thenReturn(0.0);
        when(riskMonitor.assessTailRisk(anyList(), anyMap(), anyMap())).thenReturn(SystemicRiskMonitorService.TailRiskLevel.NORMAL);
        when(schemeRepository.findByIsin(anyString())).thenReturn(Optional.of(Scheme.builder().amfiCode("AMFI1").build()));

        TacticalSignal mockSignal = TacticalSignal.builder()
            .schemeName("Fund 1")
            .action(SignalType.BUY)
            .amount("5000.00")
            .returnZScore(-2.1)
            .fundStatus(FundStatus.NEW_ENTRY)
            .build();

        when(rebalanceEngine.evaluate(any(), any(), any(), anyDouble(), anyString(), anyList(), anyMap(), anyDouble(), anyDouble(), any()))
            .thenReturn(mockSignal);

        UnifiedTacticalPayload payload = orchestrator.generateUnifiedPayload(pan, 75000.0, 0.0);

        assertFalse(payload.getOpportunisticSignals().isEmpty());
        assertEquals("Fund 1", payload.getOpportunisticSignals().get(0).schemeName());
        assertEquals(SignalType.BUY, payload.getOpportunisticSignals().get(0).action());
    }
}
