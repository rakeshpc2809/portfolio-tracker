package com.oreki.cas_injector.backfill.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "historical_nav", indexes = {@Index(columnList = "amfiCode, navDate")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalNav {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String amfiCode;
    private LocalDate navDate;
    private BigDecimal nav;
}