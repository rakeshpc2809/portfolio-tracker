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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.core.GoogleSheetService;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.service.LotAggregationService;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.ReasoningMetadata;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.taxmanagement.service.TaxLossHarvestingService;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

@ExtendWith(MockitoExtension.class)
public class PortfolioOrchestratorTest {

    @Mock private GoogleSheetService strategyService;
    @Mock private ConvictionMetricsRepository metricsRepo;
    @Mock private TaxLotRepository taxLotRepository;
    @Mock private SchemeRepository schemeRepository;
    @Mock private LotAggregationService lotAggregationService;
    @Mock private RebalanceEngine rebalanceEngine;
    @Mock private HierarchicalRiskParityService hrpService;
    @Mock private TaxLossHarvestingService tlhService;
    @Mock private PositionSizingService positionSizingService;

    @InjectMocks
    private PortfolioOrchestrator orchestrator;

    @Test
    public void testComputeOpportunisticSignals_UnconstrainedCash() {
        StrategyTarget target = new StrategyTarget("ISIN1", "Fund 1", 10.0, 5.0, "ACTIVE", "CORE");
        when(strategyService.fetchLatestStrategy()).thenReturn(Collections.singletonList(target));
        
        LocalDate oldDate = LocalDate.now().minusDays(30);
        when(metricsRepo.fetchLiveMetricsMap("PAN1")).thenReturn(Map.of("AMFI1", MarketMetrics.fromLegacy(80, 1.5, -2.0, 0.7, -10.0, 0.5, 0.0, 0.0, oldDate)));
        when(schemeRepository.findAll()).thenReturn(Collections.singletonList(Scheme.builder().isin("ISIN1").amfiCode("AMFI1").name("Fund 1").build()));
        when(taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", "PAN1")).thenReturn(Collections.emptyList());
        when(lotAggregationService.aggregate(anyList())).thenReturn(Collections.emptyList());
        when(hrpService.computeHrpWeights(anyList())).thenReturn(new HierarchicalRiskParityService.HrpResult(Collections.emptyMap(), new double[0][0], Collections.emptyList()));
        
        TacticalSignal rawSignal = new TacticalSignal("Fund 1", "Fund 1", "AMFI1", SignalType.BUY, "50000.00", 10.0, 0.0, 5.0, "NEW_ENTRY", 80, 1.5, -10.0, 0.5, 0.0, 0.0, oldDate, Collections.emptyList(), ReasoningMetadata.neutral("Fund 1"), 0.5, 0.5, "RANDOM_WALK", 0.0, false, 0.0, 0.0, false);
        when(rebalanceEngine.evaluate(any(), any(), any(), anyDouble(), anyString(), anyList(), anyMap(), anyDouble())).thenReturn(rawSignal);
        when(positionSizingService.calculateExecutionAmount(anyDouble(), anyDouble(), any())).thenReturn(50000.0);

        List<TacticalSignal> signals = orchestrator.computeOpportunisticSignals("PAN1", 100000.0);

        assertEquals(1, signals.size());
        assertEquals("50000.00", signals.get(0).amount());
    }

    @Test
    public void testComputeOpportunisticSignals_ConstrainedCash() {
        StrategyTarget target = new StrategyTarget("ISIN1", "Fund 1", 10.0, 5.0, "ACTIVE", "CORE");
        when(strategyService.fetchLatestStrategy()).thenReturn(Collections.singletonList(target));
        
        LocalDate oldDate = LocalDate.now().minusDays(30);
        when(metricsRepo.fetchLiveMetricsMap("PAN1")).thenReturn(Map.of("AMFI1", MarketMetrics.fromLegacy(100, 1.5, -2.0, 0.7, -10.0, 0.5, 0.0, 0.0, oldDate)));
        when(schemeRepository.findAll()).thenReturn(Collections.singletonList(Scheme.builder().isin("ISIN1").amfiCode("AMFI1").name("Fund 1").build()));
        when(taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", "PAN1")).thenReturn(Collections.emptyList());
        when(lotAggregationService.aggregate(anyList())).thenReturn(Collections.emptyList());
        when(hrpService.computeHrpWeights(anyList())).thenReturn(new HierarchicalRiskParityService.HrpResult(Collections.emptyMap(), new double[0][0], Collections.emptyList()));
        
        TacticalSignal rawSignal = new TacticalSignal("Fund 1", "Fund 1", "AMFI1", SignalType.BUY, "50000.00", 10.0, 0.0, 5.0, "NEW_ENTRY", 100, 1.5, -10.0, 0.5, 0.0, 0.0, oldDate, Collections.emptyList(), ReasoningMetadata.neutral("Fund 1"), 0.5, 0.5, "RANDOM_WALK", 0.0, false, 0.0, 0.0, false);
        when(rebalanceEngine.evaluate(any(), any(), any(), anyDouble(), anyString(), anyList(), anyMap(), anyDouble())).thenReturn(rawSignal);
        when(positionSizingService.calculateExecutionAmount(anyDouble(), anyDouble(), any())).thenReturn(50000.0);

        List<TacticalSignal> signals = orchestrator.computeOpportunisticSignals("PAN1", 10000.0);

        assertEquals(1, signals.size());
        assertEquals("10000.00", signals.get(0).amount());
    }
}
