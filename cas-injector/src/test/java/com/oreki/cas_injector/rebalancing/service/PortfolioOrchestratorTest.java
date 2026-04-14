package com.oreki.cas_injector.rebalancing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.oreki.cas_injector.rebalancing.dto.ReasoningMetadata;
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

    @InjectMocks
    private PortfolioOrchestrator orchestrator;

    @Test
    public void testComputeOpportunisticSignals_UnconstrainedCash() {
        StrategyTarget target = new StrategyTarget("ISIN1", "Fund 1", 10.0, 5.0, "ACTIVE", "CORE");
        
        LocalDate oldDate = LocalDate.now().minusDays(30);
        when(metricsRepo.fetchLiveMetricsMap("PAN1")).thenReturn(Map.of("AMFI1", MarketMetrics.fromLegacy(80, 1.5, -2.0, 0.7, -10.0, 0.5, 0.0, -2.0, oldDate)));
        when(schemeRepository.findFirstByAmfiCode("AMFI1")).thenReturn(Optional.of(Scheme.builder().isin("ISIN1").amfiCode("AMFI1").name("Fund 1").build()));
        when(taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", "PAN1")).thenReturn(Collections.emptyList());

        List<TacticalSignal> signals = orchestrator.computeOpportunisticSignals("PAN1", 100000.0);

        assertEquals(1, signals.size());
        assertEquals(SignalType.BUY, signals.get(0).action());
        assertEquals("33333.33", signals.get(0).amount());
    }

    @Test
    public void testComputeOpportunisticSignals_ConstrainedCash() {
        LocalDate oldDate = LocalDate.now().minusDays(30);
        when(metricsRepo.fetchLiveMetricsMap("PAN1")).thenReturn(Map.of("AMFI1", MarketMetrics.fromLegacy(100, 1.5, -2.0, 0.7, -10.0, 0.5, 0.0, -2.5, oldDate)));
        when(schemeRepository.findFirstByAmfiCode("AMFI1")).thenReturn(Optional.of(Scheme.builder().isin("ISIN1").amfiCode("AMFI1").name("Fund 1").build()));
        when(taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", "PAN1")).thenReturn(Collections.emptyList());

        List<TacticalSignal> signals = orchestrator.computeOpportunisticSignals("PAN1", 10000.0);

        assertEquals(1, signals.size());
        assertEquals(SignalType.BUY, signals.get(0).action());
        assertEquals("3333.33", signals.get(0).amount());
    }
}
