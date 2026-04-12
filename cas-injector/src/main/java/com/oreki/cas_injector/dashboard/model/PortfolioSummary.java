package com.oreki.cas_injector.dashboard.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "mv_portfolio_summary")
@Immutable
@Data
public class PortfolioSummary {
    @Id
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
