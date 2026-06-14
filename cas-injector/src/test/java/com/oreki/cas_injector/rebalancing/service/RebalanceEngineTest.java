package com.oreki.cas_injector.rebalancing.service;

import com.oreki.cas_injector.backfill.model.HistoricalNav;
import com.oreki.cas_injector.backfill.repository.HistoricalNavRepository;
import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.rebalancing.dto.RebalanceActionDTO;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RebalanceEngineTest {

    @Mock
    private HistoricalNavRepository navRepo;

    @Mock
    private TaxLotRepository taxLotRepository;

    @Mock
    private NavService amfiService;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private RebalanceEngine rebalanceEngine;

    private String pan = "PAN123";
    private String amfiCode = "100123";
    private String isin = "INF123K01234";
    private String schemeName = "Test Equity Fund";

    @BeforeEach
    public void setUp() {
    }

    private List<HistoricalNav> createMockHistory(int count) {
        List<HistoricalNav> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(HistoricalNav.builder()
                    .amfiCode(amfiCode)
                    .navDate(LocalDate.now().minusDays(count - i))
                    .nav(BigDecimal.valueOf(100.0 + i))
                    .build());
        }
        return history;
    }

    @Test
    public void testBuySignal() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(50000.0))
                .units(BigDecimal.valueOf(500.0))
                .investedAmount(BigDecimal.valueOf(45000.0))
                .ltcgAmount(BigDecimal.ZERO)
                .stcgAmount(BigDecimal.ZERO)
                .daysToNextLtcg(0)
                .build();

        StrategyTarget target = new StrategyTarget(isin, schemeName, 10.0, 0.0, "ACTIVE", "EQUITY");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(0.0)
                .tailRiskLevel("LOW")
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        List<HistoricalNav> history = createMockHistory(60);
        when(navRepo.findByAmfiCodeOrderByNavDateAsc(amfiCode)).thenReturn(history);

        Map<String, Object> mockQuantResponse = Map.of(
                "rolling_z_score_252", -2.5,
                "hurst_exponent", 0.5
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockQuantResponse);

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("BUY", action.getSignal());
        assertEquals(BigDecimal.valueOf(500.0000).setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("Buy (EQUITY): Underweight"));
    }

    @Test
    public void testCriticalReviewSignal() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(50000.0))
                .units(BigDecimal.valueOf(500.0))
                .investedAmount(BigDecimal.valueOf(45000.0))
                .ltcgAmount(BigDecimal.ZERO)
                .stcgAmount(BigDecimal.ZERO)
                .daysToNextLtcg(0)
                .build();

        StrategyTarget target = new StrategyTarget(isin, schemeName, 10.0, 0.0, "ACTIVE", "EQUITY");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(0.0)
                .tailRiskLevel("LOW")
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        List<HistoricalNav> history = createMockHistory(60);
        when(navRepo.findByAmfiCodeOrderByNavDateAsc(amfiCode)).thenReturn(history);

        Map<String, Object> mockQuantResponse = Map.of(
                "rolling_z_score_252", -4.2,
                "hurst_exponent", 0.5
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockQuantResponse);

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("CRITICAL_REVIEW", action.getSignal());
        assertEquals(BigDecimal.ZERO.setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("Critical Review: Z-Score is extremely low"));
    }

    @Test
    public void testHoldWaveRiderSignal() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(150000.0))
                .units(BigDecimal.valueOf(1500.0))
                .investedAmount(BigDecimal.valueOf(130000.0))
                .ltcgAmount(BigDecimal.ZERO)
                .stcgAmount(BigDecimal.ZERO)
                .daysToNextLtcg(0)
                .build();

        StrategyTarget target = new StrategyTarget(isin, schemeName, 10.0, 0.0, "ACTIVE", "EQUITY");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(0.0)
                .tailRiskLevel("LOW")
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        List<HistoricalNav> history = createMockHistory(60);
        when(navRepo.findByAmfiCodeOrderByNavDateAsc(amfiCode)).thenReturn(history);

        Map<String, Object> mockQuantResponse = Map.of(
                "rolling_z_score_252", 2.5,
                "hurst_exponent", 0.60
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockQuantResponse);

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("HOLD", action.getSignal());
        assertEquals(BigDecimal.ZERO.setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("Hold (Wave Rider)"));
    }

    @Test
    public void testSellSignalFullHeadroom() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(150000.0))
                .units(BigDecimal.valueOf(1500.0))
                .investedAmount(BigDecimal.valueOf(130000.0))
                .ltcgAmount(BigDecimal.ZERO)
                .stcgAmount(BigDecimal.ZERO)
                .daysToNextLtcg(0)
                .build();

        StrategyTarget target = new StrategyTarget(isin, schemeName, 10.0, 0.0, "ACTIVE", "EQUITY");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(0.0)
                .tailRiskLevel("LOW")
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        List<HistoricalNav> history = createMockHistory(60);
        when(navRepo.findByAmfiCodeOrderByNavDateAsc(amfiCode)).thenReturn(history);

        Map<String, Object> mockQuantResponse = Map.of(
                "rolling_z_score_252", 2.5,
                "hurst_exponent", 0.5
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockQuantResponse);

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        Scheme mockScheme = Scheme.builder().amfiCode(amfiCode).isin(isin).name(schemeName).build();
        TaxLot lot = TaxLot.builder()
                .scheme(mockScheme)
                .buyDate(LocalDate.now().minusDays(400))
                .remainingUnits(BigDecimal.valueOf(1500.0))
                .costBasisPerUnit(BigDecimal.valueOf(60.0))
                .status("OPEN")
                .build();
        when(taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan("OPEN", amfiCode, pan))
                .thenReturn(List.of(lot));

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("SELL", action.getSignal());
        assertEquals(BigDecimal.valueOf(500.0000).setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("Est. LTCG") && action.getJustification().contains("fits within remaining tax headroom"));
    }

    @Test
    public void testSellSignalCappedHeadroom() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(150000.0))
                .units(BigDecimal.valueOf(1500.0))
                .investedAmount(BigDecimal.valueOf(130000.0))
                .ltcgAmount(BigDecimal.ZERO)
                .stcgAmount(BigDecimal.ZERO)
                .daysToNextLtcg(0)
                .build();

        StrategyTarget target = new StrategyTarget(isin, schemeName, 10.0, 0.0, "ACTIVE", "EQUITY");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(115000.0)
                .tailRiskLevel("LOW")
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        List<HistoricalNav> history = createMockHistory(60);
        when(navRepo.findByAmfiCodeOrderByNavDateAsc(amfiCode)).thenReturn(history);

        Map<String, Object> mockQuantResponse = Map.of(
                "rolling_z_score_252", 2.5,
                "hurst_exponent", 0.5
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockQuantResponse);

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        Scheme mockScheme = Scheme.builder().amfiCode(amfiCode).isin(isin).name(schemeName).build();
        TaxLot lot = TaxLot.builder()
                .scheme(mockScheme)
                .buyDate(LocalDate.now().minusDays(400))
                .remainingUnits(BigDecimal.valueOf(1500.0))
                .costBasisPerUnit(BigDecimal.valueOf(60.0))
                .status("OPEN")
                .build();
        when(taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan("OPEN", amfiCode, pan))
                .thenReturn(List.of(lot));

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("SELL", action.getSignal());
        assertEquals(BigDecimal.valueOf(250.0000).setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("Capped to fit remaining LTCG tax headroom"));
    }

    @Test
    public void testMandatoryExit() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(50000.0))
                .units(BigDecimal.valueOf(500.0))
                .investedAmount(BigDecimal.valueOf(45000.0))
                .build();

        StrategyTarget target = new StrategyTarget(isin, schemeName, 0.0, 0.0, "EXIT", "CORE");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(0.0)
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        Scheme mockScheme = Scheme.builder().amfiCode(amfiCode).isin(isin).name(schemeName).build();
        TaxLot lot = TaxLot.builder()
                .scheme(mockScheme)
                .buyDate(LocalDate.now().minusDays(400))
                .remainingUnits(BigDecimal.valueOf(500.0))
                .costBasisPerUnit(BigDecimal.valueOf(90.0))
                .status("OPEN")
                .build();
        when(taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan("OPEN", amfiCode, pan))
                .thenReturn(List.of(lot));

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("EXIT", action.getSignal());
        assertEquals(BigDecimal.valueOf(500.0000).setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("Exit (Mandatory)"));
    }

    @Test
    public void testClutterConsolidation() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(3000.0)) // < 5000
                .units(BigDecimal.valueOf(30.0))
                .investedAmount(BigDecimal.valueOf(2800.0))
                .build();

        StrategyTarget target = new StrategyTarget(isin, schemeName, 0.0, 0.0, "ACTIVE", "CORE");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(0.0)
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("SELL", action.getSignal());
        assertEquals(BigDecimal.valueOf(30.0000).setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("Sell (Clutter)"));
    }

    @Test
    public void testDeviationBelowThreshold() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(80000.0)) // 8.0% actual weight
                .units(BigDecimal.valueOf(800.0))
                .investedAmount(BigDecimal.valueOf(75000.0))
                .build();

        // Target is 10%, Core threshold is 5% -> drift is 2% which is within threshold
        StrategyTarget target = new StrategyTarget(isin, schemeName, 10.0, 0.0, "ACTIVE", "CORE");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(0.0)
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("HOLD", action.getSignal());
        assertEquals(BigDecimal.ZERO.setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("is within the rebalance threshold"));
    }

    @Test
    public void testTacticalBuyTrigger() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(70000.0)) // 7.0% actual weight
                .units(BigDecimal.valueOf(700.0))
                .investedAmount(BigDecimal.valueOf(65000.0))
                .build();

        // Target is 10%, Tactical threshold is 1.5% -> drift is 3% which is outside threshold
        StrategyTarget target = new StrategyTarget(isin, schemeName, 10.0, 0.0, "ACTIVE", "TACTICAL");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(0.0)
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        List<HistoricalNav> history = createMockHistory(60);
        when(navRepo.findByAmfiCodeOrderByNavDateAsc(amfiCode)).thenReturn(history);

        // Z-score is -1.8 which triggers buy since it's < -1.5
        Map<String, Object> mockQuantResponse = Map.of(
                "rolling_z_score_252", -1.8,
                "hurst_exponent", 0.5
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockQuantResponse);

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("BUY", action.getSignal());
        assertEquals(BigDecimal.valueOf(300.0000).setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("Buy (TACTICAL)"));
    }
}
