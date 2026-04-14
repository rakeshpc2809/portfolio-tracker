package com.oreki.cas_injector.dashboard.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * POJO for the materialized view mv_portfolio_summary.
 * Decoupled from JPA to avoid schema validation conflicts on startup.
 */
@Data
public class PortfolioSummary {
    private Long txnId;
    private String txnHash;
    private LocalDate transactionDate;
    private String transactionType;
    private BigDecimal amount;
    private BigDecimal units;
    private String description;
    private Long schemeId;
    private String schemeName;
    private String isin;
    private String amfiCode;
    private String assetCategory;
    private Long folioId;
    private String folioNumber;
    private String amc;
    private String investorPan;
    private String investorName;
}
