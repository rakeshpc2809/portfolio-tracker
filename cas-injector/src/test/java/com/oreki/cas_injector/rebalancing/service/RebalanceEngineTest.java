package com.oreki.cas_injector.rebalancing.service;

import com.oreki.cas_injector.backfill.model.HistoricalNav;
import com.oreki.cas_injector.backfill.repository.HistoricalNavRepository;
import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.rebalancing.dto.RebalanceActionDTO;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
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
                .currentValue(BigDecimal.valueOf(1500.0)) // < 0.2% of 1,000,000
                .units(BigDecimal.valueOf(15.0))
                .investedAmount(BigDecimal.valueOf(1400.0))
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
        assertEquals(BigDecimal.valueOf(15.0000).setScale(4), action.getUnitsToTransact());
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

    @Test
    public void testTacticalBuyGatedByHmmBear() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(70000.0)) // 7.0% actual weight
                .units(BigDecimal.valueOf(700.0))
                .investedAmount(BigDecimal.valueOf(65000.0))
                .build();

        StrategyTarget target = new StrategyTarget(isin, schemeName, 10.0, 0.0, "ACTIVE", "TACTICAL");

        MarketMetrics metric = new MarketMetrics(
            0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, null, null,
            50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0,
            0.0, 0.0,
            0.0, 0.5, 0.0, "RANDOM_WALK", 50.0,
            0.0, false,
            "VOLATILE_BEAR", 0.1, 0.7, 0.2, // hmmBearProb = 0.70 >= 0.6
            0.0, 1.0, 0.0, 0.0, 0.0
        );

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(0.0)
                .holdings(List.of(holding))
                .targets(List.of(target))
                .metrics(Map.of(amfiCode, metric))
                .amfiMap(Map.of(isin, amfiCode))
                .build();

        List<HistoricalNav> history = createMockHistory(60);
        when(navRepo.findByAmfiCodeOrderByNavDateAsc(amfiCode)).thenReturn(history);

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
        assertEquals("HOLD", action.getSignal());
        assertTrue(action.getJustification().contains("HMM Bear < 0.6 gating are not met"));
    }

    @Test
    public void testHardSellOverride() {
        AggregatedHolding holding = AggregatedHolding.builder()
                .isin(isin)
                .schemeName(schemeName)
                .currentValue(BigDecimal.valueOf(250000.0)) // 25.0% actual weight
                .units(BigDecimal.valueOf(2500.0))
                .investedAmount(BigDecimal.valueOf(200000.0))
                .build();

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

        List<HistoricalNav> history = createMockHistory(60);
        when(navRepo.findByAmfiCodeOrderByNavDateAsc(amfiCode)).thenReturn(history);

        // Hurst exponent > 0.55 indicates strong upward trend (normally Wave Rider HOLD)
        Map<String, Object> mockQuantResponse = Map.of(
                "rolling_z_score_252", 2.5,
                "hurst_exponent", 0.7
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockQuantResponse);

        SchemeDetailsDTO details = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", schemeName);
        when(amfiService.getLatestSchemeDetails(amfiCode)).thenReturn(details);

        Scheme mockScheme = Scheme.builder().amfiCode(amfiCode).isin(isin).name(schemeName).build();
        TaxLot lot = TaxLot.builder()
                .scheme(mockScheme)
                .buyDate(LocalDate.now().minusDays(400))
                .remainingUnits(BigDecimal.valueOf(2500.0))
                .costBasisPerUnit(BigDecimal.valueOf(80.0))
                .status("OPEN")
                .build();
        when(taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan("OPEN", amfiCode, pan))
                .thenReturn(List.of(lot));

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(1, actions.size());
        RebalanceActionDTO action = actions.get(0);
        assertEquals("SELL", action.getSignal());
        assertEquals(BigDecimal.valueOf(1500.0000).setScale(4), action.getUnitsToTransact());
        assertTrue(action.getJustification().contains("Hard Sell Override"));
    }

    @Test
    public void testLtcgDeductionOrder() {
        String isinActive = "INF209K01157";
        String amfiActive = "120503";
        String activeName = "Active Fund";

        String isinExit = "INF109KC13X2";
        String amfiExit = "100033";
        String exitName = "Dropped Fund";

        // Active fund holding: actualPct = 12% (120,000 out of 1,000,000)
        AggregatedHolding activeHolding = AggregatedHolding.builder()
                .isin(isinActive)
                .schemeName(activeName)
                .currentValue(BigDecimal.valueOf(120000.0))
                .units(BigDecimal.valueOf(1200.0))
                .investedAmount(BigDecimal.valueOf(60000.0))
                .build();

        // Dropped fund holding: actualPct = 5% (50,000 out of 1,000,000)
        AggregatedHolding exitHolding = AggregatedHolding.builder()
                .isin(isinExit)
                .schemeName(exitName)
                .currentValue(BigDecimal.valueOf(50000.0))
                .units(BigDecimal.valueOf(500.0))
                .investedAmount(BigDecimal.valueOf(25000.0))
                .build();

        // Active fund target: 10% target, SATELLITE threshold is 2% -> drift is 2% (triggers sell)
        StrategyTarget activeTarget = new StrategyTarget(isinActive, activeName, 10.0, 0.0, "ACTIVE", "SATELLITE");
        // Dropped fund target: marked as DROPPED
        StrategyTarget exitTarget = new StrategyTarget(isinExit, exitName, 0.0, 0.0, "DROPPED", "CORE");

        RebalanceEngine.RebalanceRequest req = RebalanceEngine.RebalanceRequest.builder()
                .pan(pan)
                .totalPortfolioValue(BigDecimal.valueOf(1000000.0))
                .fyLtcgAlreadyRealized(115000.0) // remaining headroom = 10,000
                .holdings(List.of(activeHolding, exitHolding))
                .targets(List.of(activeTarget, exitTarget))
                .metrics(new HashMap<>())
                .amfiMap(Map.of(isinActive, amfiActive, isinExit, amfiExit))
                .build();

        List<HistoricalNav> activeHistory = createMockHistory(60);
        when(navRepo.findByAmfiCodeOrderByNavDateAsc(amfiActive)).thenReturn(activeHistory);

        Map<String, Object> mockQuantResponse = Map.of(
                "rolling_z_score_252", 2.5,
                "hurst_exponent", 0.5
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockQuantResponse);

        SchemeDetailsDTO activeDetails = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", activeName);
        SchemeDetailsDTO exitDetails = new SchemeDetailsDTO(BigDecimal.valueOf(100.0), "EQUITY", exitName);
        when(amfiService.getLatestSchemeDetails(amfiActive)).thenReturn(activeDetails);
        when(amfiService.getLatestSchemeDetails(amfiExit)).thenReturn(exitDetails);

        Scheme mockActiveScheme = Scheme.builder().amfiCode(amfiActive).isin(isinActive).name(activeName).build();
        TaxLot activeLot = TaxLot.builder()
                .scheme(mockActiveScheme)
                .buyDate(LocalDate.now().minusDays(400))
                .remainingUnits(BigDecimal.valueOf(1200.0))
                .costBasisPerUnit(BigDecimal.valueOf(50.0)) // LTCG gain per unit is 50.0
                .status("OPEN")
                .build();
        when(taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan("OPEN", amfiActive, pan))
                .thenReturn(List.of(activeLot));

        Scheme mockExitScheme = Scheme.builder().amfiCode(amfiExit).isin(isinExit).name(exitName).build();
        TaxLot exitLot = TaxLot.builder()
                .scheme(mockExitScheme)
                .buyDate(LocalDate.now().minusDays(400))
                .remainingUnits(BigDecimal.valueOf(500.0))
                .costBasisPerUnit(BigDecimal.valueOf(50.0)) // LTCG gain per unit is 50.0
                .status("OPEN")
                .build();
        when(taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan("OPEN", amfiExit, pan))
                .thenReturn(List.of(exitLot));

        List<RebalanceActionDTO> actions = rebalanceEngine.generateSignals(req);

        assertNotNull(actions);
        assertEquals(2, actions.size());

        // Find active action
        RebalanceActionDTO activeAction = actions.stream()
                .filter(a -> a.getIsin().equals(isinActive))
                .findFirst().orElseThrow();
        assertEquals("SELL", activeAction.getSignal());
        // Headroom is 10,000. LTCG gain per unit is 50.0. 10,000 / 50.0 = 200.0 units allowed.
        assertEquals(BigDecimal.valueOf(200.0000).setScale(4), activeAction.getUnitsToTransact());

        // Find exit action
        RebalanceActionDTO exitAction = actions.stream()
                .filter(a -> a.getIsin().equals(isinExit))
                .findFirst().orElseThrow();
        assertEquals("EXIT", exitAction.getSignal());
        // Exit is mandatory, so headroom is NOT capping it. It should sell all 500 units.
        assertEquals(BigDecimal.valueOf(500.0000).setScale(4), exitAction.getUnitsToTransact());
    }
}
