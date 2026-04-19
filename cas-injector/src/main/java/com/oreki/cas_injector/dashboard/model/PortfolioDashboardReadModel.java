package com.oreki.cas_injector.dashboard.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "portfolio_dashboard_read_model")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortfolioDashboardReadModel {

    @Id
    private String investorPan;

    private double totalValue;
    private double totalCost;
    private double totalGains;
    private double ltcgGains;
    private double stcgGains;
    private int daysToNextLtcg;
    private int portfolioAgeDays;
    private double estimatedTax;
    
    @jakarta.persistence.Column(columnDefinition = "TEXT")
    private String content; // JSON blob of DashboardSummaryDTO
    
    private LocalDateTime lastUpdatedAt;
}
