package com.oreki.cas_injector.dashboard.event;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.oreki.cas_injector.core.event.CasIngestionCompletedEvent;
import com.oreki.cas_injector.dashboard.model.PortfolioDashboardReadModel;
import com.oreki.cas_injector.dashboard.repository.PortfolioDashboardReadModelRepository;
import com.oreki.cas_injector.dashboard.service.DashboardService;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class PortfolioSummaryUpdater {

    private final PortfolioDashboardReadModelRepository summaryRepo;
    private final DashboardService dashboardService;
    private final ObjectMapper objectMapper;

    @EventListener
    @Async("mathEngineExecutor")
    public void onIngestionComplete(CasIngestionCompletedEvent event) {
        String pan = event.getInvestorPan();
        log.info("📊 [CQRS] Updating Read Model for PAN: {}", pan);

        try {
            // 1. Compute the full dashboard (The "Query" side of the foundation)
            DashboardSummaryDTO dashboard = dashboardService.computeSummary(pan);

            // 2. Serialize to JSON
            String json = objectMapper.writeValueAsString(dashboard);

            // 3. Update the Read Model Table
            PortfolioDashboardReadModel readModel = PortfolioDashboardReadModel.builder()
                .investorPan(pan)
                .totalValue(dashboard.getCurrentValueAmount().doubleValue())
                .totalCost(dashboard.getCurrentInvestedAmount().doubleValue())
                .totalGains(dashboard.getTotalUnrealizedGain().doubleValue())
                .ltcgGains(dashboard.getTotalLTCG() != null ? dashboard.getTotalLTCG().doubleValue() : 0)
                .stcgGains(dashboard.getTotalSTCG() != null ? dashboard.getTotalSTCG().doubleValue() : 0)
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
