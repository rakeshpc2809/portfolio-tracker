package com.oreki.cas_injector.dashboard.event;

import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;

import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.oreki.cas_injector.core.event.CasIngestionCompletedEvent;
import com.oreki.cas_injector.dashboard.model.PortfolioDashboardReadModel;
import com.oreki.cas_injector.dashboard.repository.PortfolioDashboardReadModelRepository;
import com.oreki.cas_injector.dashboard.service.DashboardService;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.convictionmetrics.service.ConvictionScoringService;
import com.oreki.cas_injector.convictionmetrics.service.QuantitativeEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class PortfolioSummaryUpdater {

    private final PortfolioDashboardReadModelRepository summaryRepo;
    private final DashboardService dashboardService;
    private final ConvictionScoringService convictionScoringService;
    private final QuantitativeEngineService quantitativeEngineService;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("mathEngineExecutor")
    public void onIngestionComplete(CasIngestionCompletedEvent event) {
        String pan = event.getInvestorPan();
        log.info("📊 [CQRS] Updating Read Model for PAN: {}", pan);

        try {
            // 0. Refresh Materialized View to pick up newly committed transactions
            log.info("🔄 [CQRS] Refreshing materialized view mv_portfolio_summary...");
            dashboardService.refreshMaterializedView();

            // 1. Run Quantitative Engine and Scoring (The "Intelligence" side)
            log.info("🧮 [CQRS] Triggering scoring engine for PAN: {}", pan);
            quantitativeEngineService.runNightlyMathEngine();
            convictionScoringService.calculateAndSaveFinalScores(pan);

            // 2. Compute the full dashboard (The "Query" side of the foundation)
            DashboardSummaryDTO dashboard = dashboardService.computeSummary(pan);

            // 2. Serialize to JSON
            String json = objectMapper.writeValueAsString(dashboard);

            // 3. Update the Read Model Table
            PortfolioDashboardReadModel readModel = PortfolioDashboardReadModel.builder()
                .investorPan(pan)
                .totalValue(dashboard.getCurrentValueAmount())
                .totalCost(dashboard.getCurrentInvestedAmount())
                .totalGains(dashboard.getTotalUnrealizedGain())
                .ltcgGains(dashboard.getTotalLTCG() != null ? dashboard.getTotalLTCG() : BigDecimal.ZERO)
                .stcgGains(dashboard.getTotalSTCG() != null ? dashboard.getTotalSTCG() : BigDecimal.ZERO)
                .content(json)
                .lastUpdatedAt(LocalDateTime.now())
                .build();

            summaryRepo.save(readModel);
            log.info("✅ [CQRS] Read Model updated successfully for PAN: {}", pan);
            
        } catch (Exception e) {
            log.error("🚨 [CQRS] Failed to update Portfolio Summary for PAN: {}", pan, e);
        }
    }
}
