package com.oreki.cas_injector.goals.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "portfolio_goal")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortfolioGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investor_pan", nullable = false)
    private String investorPan;

    @Column(nullable = false)
    private String name;

    @Column(name = "target_amount", nullable = false)
    private BigDecimal targetAmount;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "current_allocation", precision = 19, scale = 2)
    private BigDecimal currentAllocation;

    private String priority; // HIGH, MEDIUM, LOW
    
    @Column(name = "risk_profile")
    private String riskProfile; // AGGRESSIVE, MODERATE, CONSERVATIVE
}
