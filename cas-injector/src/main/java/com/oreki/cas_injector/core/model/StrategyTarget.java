package com.oreki.cas_injector.core.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "strategy_target")
public class StrategyTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investor_pan")
    private String investorPan;

    @Column(name = "amfi_code")
    private String amfiCode;

    @Column(name = "target_allocation_pct")
    private BigDecimal targetAllocationPct;

    @Column(name = "strategy_type")
    private String strategyType = "CORE";

    @Column(name = "rebalance_band")
    private BigDecimal rebalanceBand = new BigDecimal("5.00");

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated = LocalDateTime.now();

    private String source = "GOOGLE_SHEETS";
}
