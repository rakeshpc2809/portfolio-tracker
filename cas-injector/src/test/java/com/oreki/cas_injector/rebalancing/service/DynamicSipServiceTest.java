package com.oreki.cas_injector.rebalancing.service;

import com.oreki.cas_injector.core.utils.FundStatus;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.rebalancing.dto.SmartSipAllocation;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DynamicSipServiceTest {

    @Mock
    private RebalanceOrchestrator orchestrator;

    @Mock
    private SchemeRepository schemeRepository;

    @InjectMocks
    private DynamicSipService dynamicSipService;

    private final String pan = "TESTPAN123";

    @Test
    public void testCalculateSipSplit_StandardCase() {
        // Fund A: target 15%, actual 10% (deficit 5%), Z-Score = -1.5 (cheap -> 1.5x multiplier -> 7.5% adjusted deficit)
        TacticalSignal fundA = TacticalSignal.builder()
                .schemeName("Fund A")
                .amfiCode("101")
                .action(SignalType.HOLD)
                .plannedPercentage(15.0)
                .actualPercentage(10.0)
                .fundStatus(FundStatus.ACTIVE)
                .returnZScore(-1.5)
                .build();

        // Fund B: target 10%, actual 5% (deficit 5%), Z-Score = 1.2 (expensive -> 0.5x multiplier -> 2.5% adjusted deficit)
        TacticalSignal fundB = TacticalSignal.builder()
                .schemeName("Fund B")
                .amfiCode("102")
                .action(SignalType.HOLD)
                .plannedPercentage(10.0)
                .actualPercentage(5.0)
                .fundStatus(FundStatus.ACTIVE)
                .returnZScore(1.2)
                .build();

        // Fund C: target 10%, actual 10% (deficit 0%) -> should get 0 allocation
        TacticalSignal fundC = TacticalSignal.builder()
                .schemeName("Fund C")
                .amfiCode("103")
                .action(SignalType.HOLD)
                .plannedPercentage(10.0)
                .actualPercentage(10.0)
                .fundStatus(FundStatus.ACTIVE)
                .returnZScore(0.0)
                .build();

        // Fund D (Dropped): target 10%, actual 0% (deficit 10%) -> should be filtered out
        TacticalSignal fundD = TacticalSignal.builder()
                .schemeName("Fund D")
                .amfiCode("104")
                .action(SignalType.HOLD)
                .plannedPercentage(10.0)
                .actualPercentage(0.0)
                .fundStatus(FundStatus.DROPPED)
                .returnZScore(0.0)
                .build();

        // Fund E (Sell Signal): target 20%, actual 30% -> should be filtered out due to SELL signal
        TacticalSignal fundE = TacticalSignal.builder()
                .schemeName("Fund E")
                .amfiCode("105")
                .action(SignalType.SELL)
                .plannedPercentage(20.0)
                .actualPercentage(30.0)
                .fundStatus(FundStatus.ACTIVE)
                .returnZScore(0.0)
                .build();

        Scheme schemeA = new Scheme();
        schemeA.setIsin("ISIN-A");
        Scheme schemeB = new Scheme();
        schemeB.setIsin("ISIN-B");
        Scheme schemeC = new Scheme();
        schemeC.setIsin("ISIN-C");

        when(orchestrator.getTacticalSignals(pan)).thenReturn(List.of(fundA, fundB, fundC, fundD, fundE));
        when(schemeRepository.findFirstByAmfiCode("101")).thenReturn(Optional.of(schemeA));
        when(schemeRepository.findFirstByAmfiCode("102")).thenReturn(Optional.of(schemeB));
        when(schemeRepository.findFirstByAmfiCode("103")).thenReturn(Optional.of(schemeC));

        BigDecimal budget = BigDecimal.valueOf(10000.00);
        List<SmartSipAllocation> allocations = dynamicSipService.calculateSipSplit(pan, budget);

        // Active remaining funds: Fund A, Fund B, Fund C.
        // Deficits: A=5.0, B=5.0, C=0.0
        // Adjusted: A=7.5, B=2.5, C=0.0 -> Sum = 10.0
        // Normalization: A = 7.5/10.0 = 75%, B = 2.5/10.0 = 25%, C = 0%
        // Allocation: A = 7500.00, B = 2500.00, C = 0.00
        assertEquals(3, allocations.size());

        SmartSipAllocation allocA = allocations.stream().filter(a -> "101".equals(a.amfiCode())).findFirst().orElseThrow();
        assertEquals("Fund A", allocA.schemeName());
        assertEquals("ISIN-A", allocA.isin());
        assertEquals(BigDecimal.valueOf(7500.00).setScale(2, RoundingMode.HALF_UP), allocA.allocatedAmount());
        assertEquals(75.0, allocA.allocatedPercentage());
        assertEquals(-1.5, allocA.zScore());
        assertEquals(5.0, allocA.weightDeficit());
        assertEquals(7.5, allocA.adjustedDeficit());

        SmartSipAllocation allocB = allocations.stream().filter(b -> "102".equals(b.amfiCode())).findFirst().orElseThrow();
        assertEquals("Fund B", allocB.schemeName());
        assertEquals(BigDecimal.valueOf(2500.00).setScale(2, RoundingMode.HALF_UP), allocB.allocatedAmount());
        assertEquals(25.0, allocB.allocatedPercentage());
        assertEquals(1.2, allocB.zScore());
        assertEquals(5.0, allocB.weightDeficit());
        assertEquals(2.5, allocB.adjustedDeficit());

        SmartSipAllocation allocC = allocations.stream().filter(c -> "103".equals(c.amfiCode())).findFirst().orElseThrow();
        assertEquals(BigDecimal.valueOf(0.00).setScale(2, RoundingMode.HALF_UP), allocC.allocatedAmount());
        assertEquals(0.0, allocC.allocatedPercentage());
    }

    @Test
    public void testCalculateSipSplit_FallbackToTargets() {
        // When all active target funds have 0 deficit (e.g. all are overweight or on target),
        // we fallback to allocating budget proportionally to baseline target weights.

        TacticalSignal fundA = TacticalSignal.builder()
                .schemeName("Fund A")
                .amfiCode("101")
                .action(SignalType.HOLD)
                .plannedPercentage(20.0)
                .actualPercentage(20.0)
                .fundStatus(FundStatus.ACTIVE)
                .returnZScore(0.0)
                .build();

        TacticalSignal fundB = TacticalSignal.builder()
                .schemeName("Fund B")
                .amfiCode("102")
                .action(SignalType.HOLD)
                .plannedPercentage(30.0)
                .actualPercentage(35.0)
                .fundStatus(FundStatus.ACTIVE)
                .returnZScore(0.0)
                .build();

        when(orchestrator.getTacticalSignals(pan)).thenReturn(List.of(fundA, fundB));

        BigDecimal budget = BigDecimal.valueOf(5000.00);
        List<SmartSipAllocation> allocations = dynamicSipService.calculateSipSplit(pan, budget);

        // Sum of targets = 20 + 30 = 50
        // A share = 20/50 = 40% -> ₹2,000.00
        // B share = 30/50 = 60% -> ₹3,000.00
        assertEquals(2, allocations.size());

        SmartSipAllocation allocA = allocations.stream().filter(a -> "101".equals(a.amfiCode())).findFirst().orElseThrow();
        assertEquals(BigDecimal.valueOf(2000.00).setScale(2, RoundingMode.HALF_UP), allocA.allocatedAmount());
        assertEquals(40.0, allocA.allocatedPercentage());

        SmartSipAllocation allocB = allocations.stream().filter(b -> "102".equals(b.amfiCode())).findFirst().orElseThrow();
        assertEquals(BigDecimal.valueOf(3000.00).setScale(2, RoundingMode.HALF_UP), allocB.allocatedAmount());
        assertEquals(60.0, allocB.allocatedPercentage());
    }

    @Test
    public void testCalculateSipSplit_FallbackToEqualDistribution() {
        // When all active target funds have 0 deficit AND baseline targets are also all 0,
        // we distribute equally.

        TacticalSignal fundA = TacticalSignal.builder()
                .schemeName("Fund A")
                .amfiCode("101")
                .action(SignalType.HOLD)
                .plannedPercentage(0.0)
                .actualPercentage(10.0)
                .fundStatus(FundStatus.ACTIVE)
                .returnZScore(0.0)
                .build();

        TacticalSignal fundB = TacticalSignal.builder()
                .schemeName("Fund B")
                .amfiCode("102")
                .action(SignalType.HOLD)
                .plannedPercentage(0.0)
                .actualPercentage(20.0)
                .fundStatus(FundStatus.ACTIVE)
                .returnZScore(0.0)
                .build();

        when(orchestrator.getTacticalSignals(pan)).thenReturn(List.of(fundA, fundB));

        BigDecimal budget = BigDecimal.valueOf(10000.00);
        List<SmartSipAllocation> allocations = dynamicSipService.calculateSipSplit(pan, budget);

        // Equal split = 50% each
        assertEquals(2, allocations.size());

        SmartSipAllocation allocA = allocations.stream().filter(a -> "101".equals(a.amfiCode())).findFirst().orElseThrow();
        assertEquals(BigDecimal.valueOf(5000.00).setScale(2, RoundingMode.HALF_UP), allocA.allocatedAmount());
        assertEquals(50.0, allocA.allocatedPercentage());

        SmartSipAllocation allocB = allocations.stream().filter(b -> "102".equals(b.amfiCode())).findFirst().orElseThrow();
        assertEquals(BigDecimal.valueOf(5000.00).setScale(2, RoundingMode.HALF_UP), allocB.allocatedAmount());
        assertEquals(50.0, allocB.allocatedPercentage());
    }
}
