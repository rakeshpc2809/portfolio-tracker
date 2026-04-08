package com.oreki.cas_injector.backfill.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "fund_history", 
       uniqueConstraints = {@UniqueConstraint(columnNames = {"amfi_code", "nav_date"})},
       indexes = {@Index(columnList = "amfi_code, nav_date")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalNav {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "amfi_code")
    private String amfiCode;

    @Column(name = "nav_date")
    private LocalDate navDate;

    private BigDecimal nav;
}