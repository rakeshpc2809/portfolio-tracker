package com.oreki.cas_injector.convictionmetrics.model;

import java.time.LocalDate;
import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "fund_metrics", uniqueConstraints = {
    @UniqueConstraint(name = "uq_fetch_date_scheme_code", columnNames = {"fetch_date", "scheme_code"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fetch_date", nullable = false)
    private LocalDate fetchDate;

    @Column(name = "scheme_code", nullable = false, length = 50)
    private String schemeCode;

    @Column(name = "scheme_name", length = 255)
    private String schemeName;

    private BigDecimal nav;

    @Column(name = "expense_ratio")
    private BigDecimal expenseRatio;

    @Column(name = "aum_cr")
    private BigDecimal aumCr;

    // Legacy/Unused columns still referenced by some native SQL
    @Column(name = "pe_ratio")
    private BigDecimal peRatio;

    @Column(name = "pb_ratio")
    private BigDecimal pbRatio;

    @Column(name = "coverage_pct")
    private BigDecimal coveragePct;
}
