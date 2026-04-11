package com.oreki.cas_injector.rebalancing.service;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService.TailRiskLevel;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RebalanceEngineTest {

    private RebalanceEngine rebalanceEngine;
    private StubSystemicRiskMonitor systemicRiskMonitor;

    // Manual stub since Mockito fails on Java 25
    private static class StubSystemicRiskMonitor extends SystemicRiskMonitorService {
        public TailRiskLevel level = TailRiskLevel.NORMAL;
        public StubSystemicRiskMonitor() { super(); }
        @Override
        public TailRiskLevel assessTailRisk(List<AggregatedHolding> h, Map<String, MarketMetrics> m, Map<String, String> n) {
            return level;
        }
    }

    @BeforeEach
    void setUp() {
        systemicRiskMonitor = new StubSystemicRiskMonitor();
        rebalanceEngine = new RebalanceEngine(systemicRiskMonitor);
    }

    private MarketMetrics createDefaultMetrics() {
        return new MarketMetrics(
            50, 1.5, -2.0, 60.0, 15.0, 0.5, -0.05, 0.0, LocalDate.now(),
            0.0, 0.5, 0.02, "RANDOM_WALK", 50.0, 0.5, 0.5, "RANDOM_WALK",
            0.0, 0.0, 0.0, 0.0, false, 0.0, 0.0,
            "STRESSED_NEUTRAL", 0.33, 0.33, 0.33
        );
    }

    private AggregatedHolding createHolding(double value) {
        return new AggregatedHolding(
            "Test Fund", 100.0, value, value * 0.9,
            value * 0.5, value * 0.1, value * 0.5, value * 0.1,
            0, 500, "EQUITY", "ACTIVE", "ISIN123", 0.0
        );
    }

    @Test
    void testEvaluate_CriticalTailRisk_TriggersWatch() {
        systemicRiskMonitor.level = TailRiskLevel.CRITICAL;

        AggregatedHolding holding = createHolding(100000);
        StrategyTarget target = new StrategyTarget("ISIN123", "Test Fund", 10.0, 5.0, "ACTIVE", "core");
        MarketMetrics metrics = createDefaultMetrics();

        TacticalSignal signal = rebalanceEngine.evaluate(holding, target, metrics, 1000000, "123", Collections.emptyList(), Collections.emptyMap());

        assertEquals(SignalType.WATCH, signal.action());
        assertTrue(signal.justifications().get(0).contains("Tail Risk Alert"));
    }
@Test
void testEvaluate_DroppedFund_TriggersExit() {
    systemicRiskMonitor.level = TailRiskLevel.NORMAL;

    AggregatedHolding holding = createHolding(100000);
    // Target is 0, SIP is 0 -> DROPPED
    StrategyTarget target = new StrategyTarget("ISIN123", "Test Fund", 0.0, 0.0, "ACTIVE", "core");
    MarketMetrics metrics = createDefaultMetrics();

    TacticalSignal signal = rebalanceEngine.evaluate(holding, target, metrics, 1000000, "123", Collections.emptyList(), Collections.emptyMap());

    assertEquals(SignalType.EXIT, signal.action());
    assertTrue(signal.justifications().stream().anyMatch(j -> j.contains("Strategic Exit")));
}

@Test
void testEvaluate_DroppedButTrending_TriggersHold() {
    systemicRiskMonitor.level = TailRiskLevel.NORMAL;

    AggregatedHolding holding = createHolding(100000);
    StrategyTarget target = new StrategyTarget("ISIN123", "Test Fund", 0.0, 0.0, "ACTIVE", "core");

    // H = 0.6 (Trending), Z = 1.0 (Not overheated)
    MarketMetrics metrics = new MarketMetrics(
        50, 1.5, -2.0, 60.0, 15.0, 0.5, -0.05, 1.0, LocalDate.now(),
        1.0, 0.6, 0.02, "TRENDING", 50.0, 0.6, 0.6, "TRENDING",
        0.0, 0.0, 0.0, 0.0, false, 0.0, 0.0,
        "STRESSED_NEUTRAL", 0.33, 0.33, 0.33
    );

    TacticalSignal signal = rebalanceEngine.evaluate(holding, target, metrics, 1000000, "123", Collections.emptyList(), Collections.emptyMap());

    assertEquals(SignalType.HOLD, signal.action());
    assertTrue(signal.justifications().stream().anyMatch(j -> j.contains("Wave Rider")));
}

@Test
void testEvaluate_DroppedTaxShield_TriggersHold() {
    systemicRiskMonitor.level = TailRiskLevel.NORMAL;

    AggregatedHolding holding = createHolding(100000);
    holding.setDaysToNextLtcg(20);
    holding.setLtcgAmount(0); // Forcing STCG lock
    holding.setStcgAmount(10000);
    holding.setStcgTaxEstimate(2000.0);

    StrategyTarget target = new StrategyTarget("ISIN123", "Test Fund", 0.0, 0.0, "ACTIVE", "core");

    // VT = 0.01 (1% annual drift cost). Drift amount = 10% of 1M = 100k. Drift cost = 100k * 0.01 = 1000.
    // Tax hit (2000) > Drift cost (1000) -> Should HOLD
    MarketMetrics metrics = new MarketMetrics(
        50, 1.5, -2.0, 60.0, 15.0, 0.5, -0.05, 0.5, LocalDate.now(),
        0.5, 0.5, 0.01, "RANDOM_WALK", 50.0, 0.5, 0.5, "RANDOM_WALK",
        0.0, 0.0, 0.0, 0.0, false, 0.0, 0.0,
        "STRESSED_NEUTRAL", 0.33, 0.33, 0.33
    );

    TacticalSignal signal = rebalanceEngine.evaluate(holding, target, metrics, 1000000, "123", Collections.emptyList(), Collections.emptyMap());

    assertEquals(SignalType.HOLD, signal.action());
    assertTrue(signal.justifications().stream().anyMatch(j -> j.contains("Tax Shield Active")));
}

    @Test
    void testEvaluate_RubberBandBuy_TriggersBuy() {
        systemicRiskMonitor.level = TailRiskLevel.NORMAL;

        AggregatedHolding holding = createHolding(50000);
        StrategyTarget target = new StrategyTarget("ISIN123", "Test Fund", 10.0, 5.0, "ACTIVE", "core");
        
        MarketMetrics metrics = new MarketMetrics(
            50, 1.5, -2.0, 60.0, 15.0, 0.5, -0.05, -2.5, LocalDate.now(),
            -2.5, 0.4, 0.02, "MEAN_REVERTING", 2.0, 0.4, 0.4, "MEAN_REVERTING",
            0.0, 0.0, 0.0, 0.0, false, 0.0, 0.0,
            "STRESSED_NEUTRAL", 0.33, 0.33, 0.33
        );

        TacticalSignal signal = rebalanceEngine.evaluate(holding, target, metrics, 1000000, "123", Collections.emptyList(), Collections.emptyMap());

        assertEquals(SignalType.BUY, signal.action());
        assertTrue(signal.justifications().stream().anyMatch(j -> j.contains("Rubber Band Buy")));
    }

    @Test
    void testEvaluate_WaveRiderOverride_PreventsSell() {
        systemicRiskMonitor.level = TailRiskLevel.NORMAL;

        AggregatedHolding holding = createHolding(150000);
        StrategyTarget target = new StrategyTarget("ISIN123", "Test Fund", 10.0, 5.0, "ACTIVE", "core");
        
        MarketMetrics metrics = new MarketMetrics(
            50, 1.5, -2.0, 60.0, 15.0, 0.5, -0.05, 1.5, LocalDate.now(),
            1.5, 0.6, 0.02, "TRENDING", 10.0, 0.6, 0.6, "TRENDING",
            0.0, 0.0, 0.0, 0.0, false, 0.0, 0.0,
            "STRESSED_NEUTRAL", 0.33, 0.33, 0.33
        );

        TacticalSignal signal = rebalanceEngine.evaluate(holding, target, metrics, 1000000, "123", Collections.emptyList(), Collections.emptyMap());

        assertEquals(SignalType.HOLD, signal.action());
        assertTrue(signal.justifications().stream().anyMatch(j -> j.contains("Wave Rider Override")));
    }

    @Test
    void testEvaluate_TaxShield_PreventsSell() {
        systemicRiskMonitor.level = TailRiskLevel.NORMAL;

        AggregatedHolding holding = createHolding(150000);
        holding.setDaysToNextLtcg(20);
        holding.setLtcgAmount(0);
        holding.setStcgAmount(20000);
        holding.setStcgTaxEstimate(4000.0);
        
        StrategyTarget target = new StrategyTarget("ISIN123", "Test Fund", 10.0, 5.0, "ACTIVE", "core");
        
        MarketMetrics metrics = new MarketMetrics(
            50, 1.5, -2.0, 60.0, 15.0, 0.5, -0.05, 2.5, LocalDate.now(),
            2.5, 0.5, 0.01, "RANDOM_WALK", 2.0, 0.5, 0.5, "RANDOM_WALK",
            0.0, 0.0, 0.0, 0.0, false, 0.0, 0.0,
            "STRESSED_NEUTRAL", 0.33, 0.33, 0.33
        );

        TacticalSignal signal = rebalanceEngine.evaluate(holding, target, metrics, 1000000, "123", Collections.emptyList(), Collections.emptyMap());

        assertEquals(SignalType.HOLD, signal.action());
        assertTrue(signal.justifications().stream().anyMatch(j -> j.contains("Tax Shield Active")));
    }
}
