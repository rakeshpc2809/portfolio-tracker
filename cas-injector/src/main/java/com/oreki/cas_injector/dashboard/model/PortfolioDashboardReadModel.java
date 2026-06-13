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

    @jakarta.persistence.Column(precision = 18, scale = 2)
    private java.math.BigDecimal totalValue;
    @jakarta.persistence.Column(precision = 18, scale = 2)
    private java.math.BigDecimal totalCost;
    @jakarta.persistence.Column(precision = 18, scale = 2)
    private java.math.BigDecimal totalGains;
    @jakarta.persistence.Column(precision = 18, scale = 2)
    private java.math.BigDecimal ltcgGains;
    @jakarta.persistence.Column(precision = 18, scale = 2)
    private java.math.BigDecimal stcgGains;
    private int daysToNextLtcg;
    private int portfolioAgeDays;
    @jakarta.persistence.Column(precision = 18, scale = 2)
    private java.math.BigDecimal estimatedTax;
    
    @jakarta.persistence.Column(columnDefinition = "TEXT")
    private String content; // JSON blob of DashboardSummaryDTO
    
    private java.time.LocalDateTime lastUpdatedAt;
}
