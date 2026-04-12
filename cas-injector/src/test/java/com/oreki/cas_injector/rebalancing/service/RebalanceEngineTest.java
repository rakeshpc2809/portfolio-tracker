package com.oreki.cas_injector.rebalancing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService.TailRiskLevel;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;

@ExtendWith(MockitoExtension.class)
public class RebalanceEngineTest {

    @Mock
    private SystemicRiskMonitorService systemicRiskMonitor;

    @InjectMocks
    private RebalanceEngine rebalanceEngine;

    @Test
    public void testEvaluate_NewEntry() {
        // Mock systemic risk as normal
        when(systemicRiskMonitor.assessTailRisk(anyList(), anyMap(), anyMap()))
            .thenReturn(TailRiskLevel.NORMAL);

        AggregatedHolding holding = new AggregatedHolding("Test Fund", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, "EQUITY", "DROPPED", "ISIN123", 0.0);
        StrategyTarget target = new StrategyTarget("ISIN123", "Test Fund", 10.0, 5.0, "ACTIVE", "CORE");
        MarketMetrics metrics = MarketMetrics.fromLegacy(50, 1.2, -5.0, 0.6, -15.0, 0.5, 0.0, 0.0, LocalDate.now());

        // New Entry: target > 0, actual = 0
        TacticalSignal signal = rebalanceEngine.evaluate(
            holding, target, metrics, 100000.0, "AMFI123", 
            Collections.singletonList(holding), Map.of("Test Fund", "AMFI123"), 5.0);

        assertEquals("NEW_ENTRY", signal.fundStatus());
        assertEquals(SignalType.BUY, signal.action());
        assert(signal.justifications().stream().anyMatch(j -> j.contains("New Position")));
    }

    @Test
    public void testEvaluate_CriticalTailRisk() {
        when(systemicRiskMonitor.assessTailRisk(anyList(), anyMap(), anyMap()))
            .thenReturn(TailRiskLevel.CRITICAL);

        AggregatedHolding holding = new AggregatedHolding("Test Fund", 10000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, "EQUITY", "ACTIVE", "ISIN123", 0.0);
        StrategyTarget target = new StrategyTarget("ISIN123", "Test Fund", 10.0, 5.0, "ACTIVE", "CORE");
        MarketMetrics metrics = MarketMetrics.fromLegacy(50, 1.2, -5.0, 0.6, -15.0, 0.5, 0.0, 0.0, LocalDate.now());

        TacticalSignal signal = rebalanceEngine.evaluate(
            holding, target, metrics, 100000.0, "AMFI123", 
            Collections.singletonList(holding), Map.of("Test Fund", "AMFI123"), 10.0);

        assertEquals(SignalType.WATCH, signal.action());
        assert(signal.justifications().stream().anyMatch(j -> j.contains("Tail Risk Alert")));
    }
}
